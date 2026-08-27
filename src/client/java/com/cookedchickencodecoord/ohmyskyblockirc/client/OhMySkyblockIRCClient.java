package com.cookedchickencodecoord.ohmyskyblockirc.client;

import com.cookedchickencodecoord.ohmyskyblockirc.OhMySkyblockIRC;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

public final class OhMySkyblockIRCClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        IrcConfig config = IrcConfig.load(configDir);
        //InstallIdStore.ensure(config);

        Minecraft minecraft = Minecraft.getInstance();
        NolsticeConnection connection = new NolsticeConnection(config, minecraft);
        IrcCommands.register(config, connection);

        OhMySkyblockIRC.LOGGER.info("OhMySkyblock IRC client initialized for Minecraft 26.2");
    }
}
