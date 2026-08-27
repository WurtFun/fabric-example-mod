package com.cookedchickencodecoord.ohmyskyblockirc.client;

import java.util.UUID;

final class InstallIdStore {
    private InstallIdStore() {}

    static String ensure(IrcConfig config) {
        if (config.installId != null && !config.installId.isBlank()) {
            return config.installId;
        }
        config.installId = UUID.randomUUID().toString();
        config.save();
        return config.installId;
    }
}
