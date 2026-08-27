package com.cookedchickencodecoord.ohmyskyblockirc.client;

import com.cookedchickencodecoord.ohmyskyblockirc.OhMySkyblockIRC;
import net.minecraft.client.Minecraft;

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
import java.util.concurrent.atomic.AtomicLong;

final class NolsticeConnection implements WebSocket.Listener {
    enum State {
        DISCONNECTED,
        CONNECTING,
        HANDSHAKING,
        CONNECTED,
        KICKED
    }

    /*
     * Resource protection
     */

    // Maximum amount of chat messages waiting to be displayed.
    private static final int MAX_PENDING_CHAT_MESSAGES = 100;

    // Maximum amount of chat messages processed by one Minecraft task.
    // This prevents a large backlog from monopolizing the main thread.
    private static final int MAX_CHAT_MESSAGES_PER_TASK = 4;

    // Maximum size of one complete WebSocket text message.
    private static final int MAX_FRAME_CHARS = 64 * 1024;

    // Maximum amount of queued outbound frames.
    private static final int MAX_SEND_QUEUE_SIZE = 32;

    private final IrcConfig config;
    private final Minecraft minecraft;
    private final HttpClient httpClient;

    private final AtomicBoolean closeRequested = new AtomicBoolean();
    private final AtomicBoolean chatDrainScheduled = new AtomicBoolean();
    private final AtomicLong connectionGeneration = new AtomicLong();

    private final Queue<String> sendQueue = new ArrayDeque<>();

    /*
     * Incoming chat queue.
     *
     * Important:
     * Do NOT call minecraft.execute() once for every incoming packet.
     * Incoming packets are first stored here and drained in bounded batches.
     */
    private final ArrayDeque<String> pendingChatMessages = new ArrayDeque<>();

    private volatile State state = State.DISCONNECTED;
    private volatile WebSocket socket;

    private volatile String serverKey = "";
    private volatile String clientKey = "";
    private volatile boolean encrypted;
    private volatile boolean handshakeComplete;

    private volatile long lastMessageAt;
    private final ArrayDeque<Long> recentMessages = new ArrayDeque<>();

    /*
     * WebSocket fragmentation state.
     *
     * WebSocket.Listener callbacks for one connection are serialized,
     * but keeping access synchronized makes the lifetime explicit and safe.
     */
    private final Object receiveLock = new Object();
    private final StringBuilder receiveBuffer = new StringBuilder();

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
        if (state == State.CONNECTING
                || state == State.HANDSHAKING
                || state == State.CONNECTED) {
            notifyChat("§7[§dirc§7]§r 已经连接或正在连接。");
            return;
        }

        final URI uri;
        try {
            uri = URI.create(config.endpoint);
        } catch (IllegalArgumentException e) {
            notifyChat("§7[§dirc§7]§r 地址无效：" + config.endpoint);
            return;
        }

        if (!"ws".equalsIgnoreCase(uri.getScheme())
                && !"wss".equalsIgnoreCase(uri.getScheme())) {
            notifyChat("§7[§dirc§7]§r 只支持 ws:// 和 wss:// 地址。");
            return;
        }

        final long generation = connectionGeneration.incrementAndGet();

        closeRequested.set(false);
        resetConnectionState();

        state = State.CONNECTING;

        notifyChat("§7[§dirc§7]§r 正在连接 " + uri + " …");

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(uri, this)
                .whenComplete((ws, error) -> {
                    /*
                     * This connection attempt may already be obsolete.
                     * For example, the player disconnected and started another
                     * connection before this async operation completed.
                     */
                    if (connectionGeneration.get() != generation) {
                        if (ws != null) {
                            ws.sendClose(
                                    WebSocket.NORMAL_CLOSURE,
                                    "obsolete"
                            ).exceptionally(closeError -> null);
                        }
                        return;
                    }

                    if (error != null) {
                        state = State.DISCONNECTED;
                        notifyChat("§7[§dirc§7]§r 连接失败：" + rootMessage(error));
                        return;
                    }

                    socket = ws;

                    if (state == State.CONNECTING) {
                        state = State.HANDSHAKING;
                    }
                });
    }

    void disconnect(boolean allowReconnect) {
        /*
         * Invalidate callbacks from the old connection immediately.
         */
        connectionGeneration.incrementAndGet();

        if (!allowReconnect) {
            closeRequested.set(true);
        }

        WebSocket ws = socket;

        socket = null;
        state = State.DISCONNECTED;

        clearNetworkBuffers();

        if (ws != null) {
            ws.sendClose(
                    WebSocket.NORMAL_CLOSURE,
                    "bye"
            ).exceptionally(error -> null);
        }
    }

    boolean sendMessage(String text) {
        if (state != State.CONNECTED || !handshakeComplete) {
            notifyChat("§7[§dirc§7]§r 服务器尚未连接，请先使用 /irc connect。");
            return false;
        }

        String message = text == null
                ? ""
                : text.trim()
                .replace("\r", "")
                .replace("\n", " ");

        if (message.isEmpty()) {
            notifyChat("§7[§dirc§7]§r 消息不能为空。");
            return false;
        }

        if (message.length() > 128) {
            notifyChat("§7[§dirc§7]§r 消息太长，服务器限制为 128 个字符。");
            return false;
        }

        long now = System.currentTimeMillis();

        synchronized (recentMessages) {
            while (!recentMessages.isEmpty()
                    && now - recentMessages.peekFirst()
                    >= config.messageWindowSeconds * 1000L) {
                recentMessages.removeFirst();
            }

            if (now - lastMessageAt < config.minimumMessageIntervalMs
                    || recentMessages.size() >= config.maxMessagesPerWindow) {
                notifyChat("§7[§dirc§7]§r 发送过快，请稍后再试。");
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
            notifyChat("§7[§dirc§7]§r 尚未连接。");
            return;
        }

        enqueueSend(Protocol.LIST_USERS, "");
    }

    private void enqueueSend(int opcode, String data) {
        String frame = Protocol.pack(
                opcode,
                data,
                encrypted ? clientKey : null
        );

        synchronized (sendQueue) {
            if (sendQueue.size() >= MAX_SEND_QUEUE_SIZE) {
                /*
                 * Never allow an unlimited outbound queue.
                 */
                OhMySkyblockIRC.LOGGER.warn(
                        "Dropping outbound IRC frame because send queue is full"
                );
                return;
            }

            sendQueue.add(frame);
            flushQueueLocked();
        }
    }

    private void flushQueueLocked() {
        WebSocket ws = socket;

        if (ws == null) {
            return;
        }

        while (!sendQueue.isEmpty()) {
            String frame = sendQueue.poll();

            if (frame == null) {
                break;
            }

            ws.sendText(frame, true)
                    .exceptionally(error -> {
                        handleFailure(ws, error);
                        return null;
                    });
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);

        /*
         * If another socket is already active, this callback belongs to
         * an obsolete connection.
         */
        WebSocket current = socket;

        if (current != null && current != webSocket) {
            webSocket.sendClose(
                    WebSocket.NORMAL_CLOSURE,
                    "obsolete"
            ).exceptionally(error -> null);
            return;
        }

        socket = webSocket;
        state = State.HANDSHAKING;

        webSocket.request(1);

        OhMySkyblockIRC.LOGGER.info(
                "Nolstice WebSocket opened"
        );
    }

    @Override
    public CompletionStage<?> onText(
            WebSocket webSocket,
            CharSequence data,
            boolean last
    ) {
        /*
         * Ignore messages from an obsolete WebSocket.
         */
        if (socket != webSocket) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        if (data == null) {
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        String raw = null;

        synchronized (receiveLock) {
            /*
             * Hard upper bound against malicious/accidental fragmented
             * WebSocket messages.
             */
            if (receiveBuffer.length() + data.length() > MAX_FRAME_CHARS) {
                receiveBuffer.setLength(0);

                OhMySkyblockIRC.LOGGER.warn(
                        "Nolstice WebSocket frame exceeded {} characters",
                        MAX_FRAME_CHARS
                );

                handleFailure(
                        webSocket,
                        new IllegalStateException("IRC frame too large")
                );

                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }

            receiveBuffer.append(data);

            /*
             * We don't actually need a separate 'fragmented' flag.
             * receiveBuffer containing data across callbacks is enough.
             */
            if (last) {
                raw = receiveBuffer.toString();
                receiveBuffer.setLength(0);
            }
        }

        if (raw != null) {
            processFrame(raw);
        }

        webSocket.request(1);

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<?> onClose(
            WebSocket webSocket,
            int statusCode,
            String reason
    ) {
        /*
         * Ignore close events from an old connection.
         */
        if (socket != webSocket) {
            return CompletableFuture.completedFuture(null);
        }

        socket = null;
        state = State.DISCONNECTED;

        clearNetworkBuffers();

        if (!closeRequested.get()) {
            notifyChat(
                    "§7[§dirc§7]§r 连接已关闭："
                            + (reason == null || reason.isBlank()
                            ? statusCode
                            : reason)
            );
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(
            WebSocket webSocket,
            Throwable error
    ) {
        handleFailure(webSocket, error);
    }

    private void processFrame(String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }

        /*
         * Defensive check even though onText() already limits it.
         */
        if (raw.length() > MAX_FRAME_CHARS) {
            handleFailure(
                    socket,
                    new IllegalStateException("IRC frame too large")
            );
            return;
        }

        try {
            Protocol.Packet packet = Protocol.unpack(
                    raw,
                    encrypted ? serverKey : null
            );

            if (!encrypted
                    && packet.opcode() != Protocol.KEY_OUT) {
                OhMySkyblockIRC.LOGGER.warn(
                        "Unexpected plaintext opcode {}",
                        packet.opcode()
                );
            }

            handlePacket(packet);
        } catch (Exception e) {
            notifyChat(
                    "§7[§dirc§7]§r 收到无法解析的数据："
                            + safeErrorMessage(e)
            );

            OhMySkyblockIRC.LOGGER.debug(
                    "Could not parse Nolstice frame",
                    e
            );
        }
    }

    private void handlePacket(Protocol.Packet packet) {
        switch (packet.opcode()) {
            case Protocol.KEY_OUT ->
                    handleKeyOut(packet.data());

            case Protocol.WORK ->
                    handleWork();

            case Protocol.AUTH_FINISH ->
                    handleAuthFinish();

            case Protocol.SERVER_MESSAGE,
                 Protocol.ANNOUNCEMENT,
                 Protocol.JOIN,
                 Protocol.LEAVE,
                 Protocol.MESSAGE,
                 Protocol.ERROR -> {
                if (packet.opcode() == Protocol.ERROR) {
                    notifyChat("[IRC] " + packet.data());
                } else {
                    notifyChat(packet.data());
                }
            }

            case Protocol.CONNECTED_USER_LIST ->
                    handleUserList(packet.data());

            case Protocol.PING -> {
                /*
                 * Nolstice server does not require a pong for this opcode.
                 */
            }

            case Protocol.KICKED ->
                    handleKicked(packet.data());

            case Protocol.FORCE_QUIT ->
                    handleForceQuit(packet.data());

            default -> {
            }
        }
    }

    private void handleKeyOut(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "server key is empty"
            );
        }

        serverKey = key;
        clientKey = randomKey();

        /*
         * KEY_IN must be sent in plaintext.
         */
        encrypted = false;

        enqueuePlain(
                Protocol.KEY_IN,
                clientKey
        );

        /*
         * Future packets are encrypted.
         */
        encrypted = true;
    }

    private void handleWork() {
        enqueueSend(
                Protocol.COMPLETE_WORK,
                ""
        );
    }

    private void handleAuthFinish() {
        String clientInfo = Protocol.indexedJson(
                config.clientName,
                config.installId
        );

        String playerName = currentMinecraftName();

        String username = config.username.isBlank()
                ? playerName
                : config.username;

        String playerInfo = Protocol.indexedJson(
                username,
                playerName,
                ""
        );

        enqueueSend(
                Protocol.IDENTIFY_CLIENT,
                clientInfo
        );

        enqueueSend(
                Protocol.IDENTIFY_PLAYER,
                playerInfo
        );

        handshakeComplete = true;
        state = State.CONNECTED;

        notifyChat(
                "§7[§dirc§7]§r 已完成握手。"
        );
    }

    private void handleUserList(String data) {
        Map<String, Protocol.UserInfo> users =
                Protocol.parseUsers(data);

        notifyChat(
                "§7[§dirc§7]§r 当前在线用户：" + users.size()
        );

        if (users.isEmpty()) {
            return;
        }

        int shown = 0;

        StringBuilder line =
                new StringBuilder("§7[§dirc§7]§r ");

        for (String name : users.keySet()) {
            if (shown++ >= 16) {
                line.append(" …");
                break;
            }

            if (shown > 1) {
                line.append(", ");
            }

            line.append(name);
        }

        notifyChat(line.toString());
    }

    private void handleKicked(String message) {
        state = State.KICKED;
        closeRequested.set(true);

        notifyChat(
                "§7[§dirc§7]§r 被踢出频道：" + message
        );

        disconnect(false);
    }

    private void handleForceQuit(String message) {
        closeRequested.set(true);

        notifyChat(
                "§7[§dirc§7]§r 游戏被远程关闭：" + message
        );

        disconnect(false);

        /*
         * This is intentionally a single Minecraft task and is not part
         * of the normal chat pipeline.
         */
        minecraft.execute(minecraft::stop);
    }

    private void enqueuePlain(int opcode, String data) {
        WebSocket ws = socket;

        if (ws == null) {
            return;
        }

        String frame = Protocol.pack(
                opcode,
                data,
                null
        );

        ws.sendText(frame, true)
                .exceptionally(error -> {
                    handleFailure(ws, error);
                    return null;
                });
    }

    private String currentMinecraftName() {
        if (minecraft.getUser() == null
                || minecraft.getUser().getName() == null) {
            return "Player";
        }

        return minecraft.getUser().getName();
    }

    private void handleFailure(
            WebSocket failedSocket,
            Throwable error
    ) {
        /*
         * Ignore errors from obsolete sockets.
         */
        if (failedSocket != null
                && socket != failedSocket) {
            return;
        }

        if (closeRequested.get()) {
            return;
        }

        WebSocket current = socket;

        /*
         * Invalidate the current connection.
         */
        connectionGeneration.incrementAndGet();

        socket = null;
        state = State.DISCONNECTED;

        clearNetworkBuffers();

        if (current != null) {
            current.sendClose(
                    WebSocket.NORMAL_CLOSURE,
                    "error"
            ).exceptionally(closeError -> null);
        }

        notifyChat(
                "§7[§dirc§7]§r 网络错误："
                        + rootMessage(error)
        );
    }

    private void clearNetworkBuffers() {
        synchronized (sendQueue) {
            sendQueue.clear();
        }

        synchronized (receiveLock) {
            receiveBuffer.setLength(0);
        }
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

        synchronized (receiveLock) {
            receiveBuffer.setLength(0);
        }

        /*
         * Drop stale pending UI messages when reconnecting.
         * Otherwise messages from the old connection can remain queued.
         */
        synchronized (pendingChatMessages) {
            pendingChatMessages.clear();
        }

        chatDrainScheduled.set(false);
    }

    private String randomKey() {
        final char[] chars =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                        .toCharArray();

        StringBuilder result =
                new StringBuilder(32);

        for (int i = 0; i < 32; i++) {
            result.append(
                    chars[
                            ThreadLocalRandom.current()
                                    .nextInt(chars.length)
                    ]
            );
        }

        return result.toString();
    }

    /**
     * Queue an IRC system message for Minecraft's main thread.
     *
     * IMPORTANT:
     * This method never schedules one task per IRC message.
     */
    private void notifyChat(String text) {
        if (text == null) {
            return;
        }

        synchronized (pendingChatMessages) {
            /*
             * Keep memory bounded.
             *
             * Drop the oldest message so newer status/messages remain visible.
             */
            if (pendingChatMessages.size()
                    >= MAX_PENDING_CHAT_MESSAGES) {
                pendingChatMessages.pollFirst();
            }

            pendingChatMessages.addLast(text);
        }

        scheduleChatDrain();
    }

    private void scheduleChatDrain() {
        /*
         * Only one Minecraft task is allowed to represent the entire queue.
         */
        if (!chatDrainScheduled.compareAndSet(false, true)) {
            return;
        }

        minecraft.execute(this::drainChatQueue);
    }

    private void drainChatQueue() {
        int processed = 0;

        while (processed < MAX_CHAT_MESSAGES_PER_TASK) {
            String text;

            synchronized (pendingChatMessages) {
                text = pendingChatMessages.pollFirst();
            }

            if (text == null) {
                break;
            }

            try {
                minecraft.gui.hud.getChat().addClientSystemMessage(
                        IrcMessageFormatter.parse(text)
                );
            } catch (Exception e) {
                OhMySkyblockIRC.LOGGER.warn(
                        "Failed to display IRC message",
                        e
                );
            }

            processed++;
        }

        boolean hasMore;

        synchronized (pendingChatMessages) {
            hasMore = !pendingChatMessages.isEmpty();

            if (!hasMore) {
                /*
                 * Release the scheduling flag while holding the same lock
                 * used by producers. This prevents a race where a producer
                 * adds a message exactly as the queue becomes empty.
                 */
                chatDrainScheduled.set(false);

                /*
                 * Handle the tiny race where another thread enqueued a message
                 * immediately before we released the flag.
                 */
                if (!pendingChatMessages.isEmpty()
                        && chatDrainScheduled.compareAndSet(false, true)) {
                    hasMore = true;
                }
            }
        }

        if (hasMore) {
            minecraft.execute(this::drainChatQueue);
        }
    }

    private static String safeErrorMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }

        String message = error.getMessage();

        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static String rootMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }

        Throwable current = error;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();

        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}