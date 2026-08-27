package com.cookedchickencodecoord.ohmyskyblockirc.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;

final class Protocol {
    static final int WORK = 0;
    static final int COMPLETE_WORK = 1;
    static final int KEY_IN = 2;
    static final int KEY_OUT = 3;
    static final int AUTH_FINISH = 4;
    static final int IDENTIFY_CLIENT = 5;
    static final int IDENTIFY_PLAYER = 6;
    static final int SERVER_MESSAGE = 7;
    static final int ERROR = 8;
    static final int PING = 9;
    static final int ANNOUNCEMENT = 10;
    static final int JOIN = 11;
    static final int LEAVE = 12;
    static final int MESSAGE = 13;
    static final int LIST_USERS = 14;
    static final int CONNECTED_USER_LIST = 15;
    static final int FORCE_QUIT = 16;
    static final int KICKED = 17;

    private Protocol() {}

    static String pack(int opcode, String data, String clientKeyOrNull) {
        JsonObject inner = new JsonObject();
        inner.addProperty("o", opcode);
        inner.addProperty("d", data == null ? "" : data);
        inner.addProperty("s", true);

        String payload = inner.toString();
        if (clientKeyOrNull != null && !clientKeyOrNull.isEmpty()) {
            JsonObject outer = new JsonObject();
            outer.addProperty("e", StringCipher.encrypt(payload, clientKeyOrNull));
            payload = outer.toString();
        }
        return StringCipher.encode(payload);
    }

    static Packet unpack(String raw, String serverKeyOrNull) {
        String decoded = StringCipher.decode(raw);
        JsonObject object = JsonParser.parseString(decoded).getAsJsonObject();
        if (serverKeyOrNull != null && !serverKeyOrNull.isEmpty() && object.has("e")) {
            decoded = StringCipher.decrypt(object.get("e").getAsString(), serverKeyOrNull);
            object = JsonParser.parseString(decoded).getAsJsonObject();
        }

        int opcode = object.get("o").getAsInt();
        String data = object.has("d") && !object.get("d").isJsonNull() ? object.get("d").getAsString() : "";
        boolean success = !object.has("s") || object.get("s").getAsBoolean();
        return new Packet(opcode, data, success);
    }

    static String indexedJson(String... values) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < values.length; i++) {
            object.addProperty(Integer.toString(i), values[i] == null ? "" : values[i]);
        }
        return object.toString();
    }

    static Map<String, UserInfo> parseUsers(String data) {
        Map<String, UserInfo> result = new LinkedHashMap<>();
        if (data == null || data.isBlank()) return result;

        JsonObject root = JsonParser.parseString(data).getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject user = entry.getValue().getAsJsonObject();
            result.put(entry.getKey(), new UserInfo(
                    string(user, "0"),
                    string(user, "1"),
                    string(user, "2"),
                    string(user, "3")
            ));
        }
        return result;
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    record Packet(int opcode, String data, boolean success) {}
    record UserInfo(String clientName, String displayName, String playerName, String xuid) {}
}
