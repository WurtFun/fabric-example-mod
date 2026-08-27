package com.cookedchickencodecoord.ohmyskyblockirc;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common, environment-safe entry point. All Minecraft client-only code lives in src/client. */
public final class OhMySkyblockIRC implements ModInitializer {
    public static final String MOD_ID = "ohmyskyblockirc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("OhMySkyblock IRC loaded.");
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
