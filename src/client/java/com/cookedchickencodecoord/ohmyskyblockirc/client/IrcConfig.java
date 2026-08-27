package com.cookedchickencodecoord.ohmyskyblockirc.client;

import com.cookedchickencodecoord.ohmyskyblockirc.OhMySkyblockIRC;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

final class IrcConfig {
    private static final String FILE_NAME = "ohmyskyblockirc.properties";

    String endpoint = "ws://irc.sbhypixel.net/ws";
    String username = "";
    String clientName = "OhMySkyblockIRC";
    String installId = "";
    boolean autoReconnect = true;
    int reconnectDelaySeconds = 5;
    long minimumMessageIntervalMs = 1100;
    int maxMessagesPerWindow = 5;
    int messageWindowSeconds = 10;

    private final Path path;

    private IrcConfig(Path path) {
        this.path = path;
    }

    static IrcConfig load(Path configDir) {
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            OhMySkyblockIRC.LOGGER.warn("Could not create config directory", e);
        }

        IrcConfig config = new IrcConfig(configDir.resolve(FILE_NAME));
        if (Files.isRegularFile(config.path)) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(config.path)) {
                properties.load(in);
                config.endpoint = properties.getProperty("endpoint", config.endpoint).trim();
                config.username = properties.getProperty("username", config.username).trim();
                config.clientName = properties.getProperty("clientName", config.clientName).trim();
                config.installId = properties.getProperty("installId", config.installId).trim();
                config.autoReconnect = Boolean.parseBoolean(properties.getProperty("autoReconnect", Boolean.toString(config.autoReconnect)));
                config.reconnectDelaySeconds = parseInt(properties, "reconnectDelaySeconds", config.reconnectDelaySeconds, 1, 300);
                config.minimumMessageIntervalMs = parseLong(properties, "minimumMessageIntervalMs", config.minimumMessageIntervalMs, 0, 60_000);
                config.maxMessagesPerWindow = parseInt(properties, "maxMessagesPerWindow", config.maxMessagesPerWindow, 1, 100);
                config.messageWindowSeconds = parseInt(properties, "messageWindowSeconds", config.messageWindowSeconds, 1, 3600);
            } catch (Exception e) {
                OhMySkyblockIRC.LOGGER.warn("Could not load config; defaults will be used", e);
            }
        }
        return config;
    }

    synchronized void save() {
        Properties properties = new Properties();
        properties.setProperty("endpoint", endpoint);
        properties.setProperty("username", username);
        properties.setProperty("clientName", clientName);
        properties.setProperty("installId", installId);
        properties.setProperty("autoReconnect", Boolean.toString(autoReconnect));
        properties.setProperty("reconnectDelaySeconds", Integer.toString(reconnectDelaySeconds));
        properties.setProperty("minimumMessageIntervalMs", Long.toString(minimumMessageIntervalMs));
        properties.setProperty("maxMessagesPerWindow", Integer.toString(maxMessagesPerWindow));
        properties.setProperty("messageWindowSeconds", Integer.toString(messageWindowSeconds));

        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(out, "OhMySkyblock IRC - Minecraft 26.2");
        } catch (IOException e) {
            OhMySkyblockIRC.LOGGER.warn("Could not save config", e);
        }
    }

    private static int parseInt(Properties properties, String key, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(properties.getProperty(key)), min, max);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseLong(Properties properties, String key, long fallback, long min, long max) {
        try {
            return Math.clamp(Long.parseLong(properties.getProperty(key)), min, max);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
