package com.cookedchickencodecoord.ohmyskyblockirc.client;

import com.cookedchickencodecoord.ohmyskyblockirc.OhMySkyblockIRC;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

final class NolsticeConnection implements WebSocket.Listener {
    enum State { DISCONNECTED, CONNECTING, HANDSHAKING, CONNECTED, KICKED }

    private final IrcConfig config;
    private final Minecraft minecraft;
    private final HttpClient httpClient;
    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final Queue<String> sendQueue = new ArrayDeque<>();

    private volatile State state = State.DISCONNECTED;
    private volatile WebSocket socket;
    private volatile String serverKey = "";
    private volatile String clientKey = "";
    private volatile boolean encrypted;
    private volatile boolean handshakeComplete;
    private volatile long lastMessageAt;
    private final ArrayDeque<Long> recentMessages = new ArrayDeque<>();
    private final StringBuilder receiveBuffer = new StringBuilder();
    private volatile boolean fragmented;

    NolsticeConnection(IrcConfig config, Minecraft minecraft) {
        this.config = config;
        this.minecraft = minecraft;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    State state() {
        return state;
    }

    void connect() {
        if (state == State.CONNECTING || state == State.HANDSHAKING || state == State.CONNECTED) {
            notifyChat("[IRC] 已经连接或正在连接。");
            return;
        }

        final URI uri;
        try {
            uri = URI.create(config.endpoint);
        } catch (IllegalArgumentException e) {
            notifyChat("[IRC] 地址无效：" + config.endpoint);
            return;
        }

        if (!"ws".equalsIgnoreCase(uri.getScheme()) && !"wss".equalsIgnoreCase(uri.getScheme())) {
            notifyChat("[IRC] 只支持 ws:// 和 wss:// 地址。");
            return;
        }

        closeRequested.set(false);
        resetConnectionState();
        state = State.CONNECTING;
        notifyChat("[IRC] 正在连接 " + uri + " …");

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(uri, this)
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        state = State.DISCONNECTED;
                        notifyChat("[IRC] 连接失败：" + rootMessage(error));
                        return;
                    }
                    socket = ws;
                    state = State.HANDSHAKING;
                });
    }

    void disconnect(boolean allowReconnect) {
        if (!allowReconnect) closeRequested.set(true);
        WebSocket ws = socket;
        socket = null;
        state = State.DISCONNECTED;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(error -> null);
        }
    }

    boolean sendMessage(String text) {
        if (state != State.CONNECTED || !handshakeComplete) {
            notifyChat("[IRC] 尚未连接，请先使用 /irc connect。");
            return false;
        }

        String message = text == null ? "" : text.trim().replace("\r", "").replace("\n", " ");
        if (message.isEmpty()) {
            notifyChat("[IRC] 消息不能为空。");
            return false;
        }
        if (message.length() > 128) {
            notifyChat("[IRC] 消息太长，服务器限制为 128 个字符。");
            return false;
        }

        long now = System.currentTimeMillis();
        synchronized (recentMessages) {
            while (!recentMessages.isEmpty() && now - recentMessages.peekFirst() >= config.messageWindowSeconds * 1000L) {
                recentMessages.removeFirst();
            }
            if (now - lastMessageAt < config.minimumMessageIntervalMs || recentMessages.size() >= config.maxMessagesPerWindow) {
                notifyChat("[IRC] 发送过快，请稍后再试。");
                return false;
            }
            lastMessageAt = now;
            recentMessages.addLast(now);
        }

        enqueueSend(Protocol.MESSAGE, message);
        return true;
    }

    void requestUsers() {
        if (state != State.CONNECTED) {
            notifyChat("[IRC] 尚未连接。");
            return;
        }
        enqueueSend(Protocol.LIST_USERS, "");
    }

    private void enqueueSend(int opcode, String data) {
        String frame = Protocol.pack(opcode, data, encrypted ? clientKey : null);
        synchronized (sendQueue) {
            sendQueue.add(frame);
            flushQueueLocked();
        }
    }

    private void flushQueueLocked() {
        WebSocket ws = socket;
        if (ws == null) return;
        while (!sendQueue.isEmpty()) {
            String frame = sendQueue.poll();
            if (frame == null) break;
            ws.sendText(frame, true).exceptionally(error -> {
                handleFailure(error);
                return null;
            });
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        socket = webSocket;
        state = State.HANDSHAKING;
        webSocket.request(1);
        OhMySkyblockIRC.LOGGER.info("Nolstice WebSocket opened");
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        receiveBuffer.append(data);
        fragmented = !last;
        if (last) {
            String raw = receiveBuffer.toString();
            receiveBuffer.setLength(0);
            processFrame(raw);
        }
        webSocket.request(1);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        socket = null;
        if (state != State.KICKED) {
            state = State.DISCONNECTED;
            notifyChat("[IRC] 连接已关闭：" + (reason == null || reason.isBlank() ? statusCode : reason));
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        handleFailure(error);
    }

    private void processFrame(String raw) {
        try {
            Protocol.Packet packet = Protocol.unpack(raw, encrypted ? serverKey : null);
            if (!encrypted && packet.opcode() != Protocol.KEY_OUT) {
                OhMySkyblockIRC.LOGGER.warn("Unexpected plaintext opcode {}", packet.opcode());
            }
            handlePacket(packet);
        } catch (Exception e) {
            notifyChat("[IRC] 收到无法解析的数据：" + e.getMessage());
            OhMySkyblockIRC.LOGGER.debug("Could not parse Nolstice frame", e);
        }
    }

    private void handlePacket(Protocol.Packet packet) {
        switch (packet.opcode()) {
            case Protocol.KEY_OUT -> handleKeyOut(packet.data());
            case Protocol.WORK -> handleWork();
            case Protocol.AUTH_FINISH -> handleAuthFinish();
            case Protocol.SERVER_MESSAGE, Protocol.ANNOUNCEMENT, Protocol.JOIN, Protocol.LEAVE, Protocol.MESSAGE, Protocol.ERROR -> {
                if (packet.opcode() == Protocol.ERROR) notifyChat("[IRC] " + packet.data());
                else notifyChat(packet.data());
            }
            case Protocol.CONNECTED_USER_LIST -> handleUserList(packet.data());
            case Protocol.PING -> { /* Nolstice server does not require a pong for this opcode. */ }
            case Protocol.KICKED -> handleKicked(packet.data());
            case Protocol.FORCE_QUIT -> handleForceQuit(packet.data());
            default -> { }
        }
    }

    private void handleKeyOut(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("server key is empty");
        }
        serverKey = key;
        clientKey = randomKey();
        encrypted = false;
        enqueuePlain(Protocol.KEY_IN, clientKey);
        encrypted = true;
    }

    private void handleWork() {
        enqueueSend(Protocol.COMPLETE_WORK, "");
    }

    private void handleAuthFinish() {
        String clientInfo = Protocol.indexedJson(config.clientName, config.installId);
        String playerName = currentMinecraftName();
        String username = config.username.isBlank() ? playerName : config.username;
        String playerInfo = Protocol.indexedJson(username, playerName, "");

        enqueueSend(Protocol.IDENTIFY_CLIENT, clientInfo);
        enqueueSend(Protocol.IDENTIFY_PLAYER, playerInfo);
        handshakeComplete = true;
        state = State.CONNECTED;
        notifyChat("[IRC] 已完成握手。");
    }

    private void handleUserList(String data) {
        Map<String, Protocol.UserInfo> users = Protocol.parseUsers(data);
        notifyChat("[IRC] 当前在线用户：" + users.size());
        if (users.isEmpty()) return;
        int shown = 0;
        StringBuilder line = new StringBuilder("[IRC] ");
        for (String name : users.keySet()) {
            if (shown++ >= 16) {
                line.append(" …");
                break;
            }
            if (shown > 1) line.append(", ");
            line.append(name);
        }
        notifyChat(line.toString());
    }

    private void handleKicked(String message) {
        state = State.KICKED;
        closeRequested.set(true);
        notifyChat("[IRC] 已被踢出：" + message);
        disconnect(false);
    }

    private void handleForceQuit(String message) {
        closeRequested.set(true);
        notifyChat("[IRC] 服务器要求退出游戏：" + message);
        disconnect(false);
        minecraft.execute(minecraft::stop);
    }

    private void enqueuePlain(int opcode, String data) {
        WebSocket ws = socket;
        if (ws == null) return;
        String frame = Protocol.pack(opcode, data, null);
        ws.sendText(frame, true).exceptionally(error -> {
            handleFailure(error);
            return null;
        });
    }

    private String currentMinecraftName() {
        if (minecraft.getUser() == null || minecraft.getUser().getName() == null) return "Player";
        return minecraft.getUser().getName();
    }

    private void handleFailure(Throwable error) {
        if (closeRequested.get()) return;
        socket = null;
        state = State.DISCONNECTED;
        notifyChat("[IRC] 网络错误：" + rootMessage(error));
    }

    private void resetConnectionState() {
        synchronized (sendQueue) {
            sendQueue.clear();
        }
        serverKey = "";
        clientKey = "";
        encrypted = false;
        handshakeComplete = false;
        lastMessageAt = 0;
        synchronized (recentMessages) {
            recentMessages.clear();
        }
        receiveBuffer.setLength(0);
        fragmented = false;
    }

    private String randomKey() {
        final char[] chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
        StringBuilder result = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            result.append(chars[ThreadLocalRandom.current().nextInt(chars.length)]);
        }
        return result.toString();
    }

    private void notifyChat(String text) {
        minecraft.execute(() -> minecraft.gui.hud.getChat().addClientSystemMessage(IrcMessageFormatter.parse(text)));
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
