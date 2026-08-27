package com.cookedchickencodecoord.ohmyskyblockirc.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

final class IrcCommands {
    private IrcCommands() {}

    static void register(IrcConfig config, NolsticeConnection connection) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("irc")
                        .then(ClientCommands.literal("connect").executes(context -> {
                            connection.connect();
                            return 1;
                        }))
                        .then(ClientCommands.literal("disconnect").executes(context -> {
                            connection.disconnect(false);
                            context.getSource().sendFeedback(Component.literal("§7[§dirc§7]§r 已断开连接。"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("status").executes(context -> {
                            context.getSource().sendFeedback(Component.literal("§7[§dirc§7]§r 状态：" + connection.state()));
                            return 1;
                        }))
                        .then(ClientCommands.literal("send")
                                .then(ClientCommands.argument("message", StringArgumentType.greedyString()).executes(context -> {
                                    connection.sendMessage(StringArgumentType.getString(context, "message"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("users").executes(context -> {
                            connection.requestUsers();
                            return 1;
                        }))
                        .then(ClientCommands.literal("server")
                                .then(ClientCommands.argument("endpoint", StringArgumentType.greedyString()).executes(context -> {
                                    config.endpoint = StringArgumentType.getString(context, "endpoint");
                                    config.save();
                                    context.getSource().sendFeedback(Component.literal("§7[§dirc§7]§r 选择的服务器已切换为：" + config.endpoint));
                                    return 1;
                                })))
                        //.then(ClientCommands.literal("username")
                        //        .then(ClientCommands.argument("username", StringArgumentType.word()).executes(context -> {
                        //            config.username = StringArgumentType.getString(context, "username");
                        //            config.save();
                        //            context.getSource().sendFeedback(Component.literal("[IRC] IRC 用户名已改为：" + config.username));
                        //            return 1;
                        //        })))
                        .then(ClientCommands.literal("reload").executes(context -> {
                            // Config is loaded once at client initialization; reconnect after changing the file.
                            context.getSource().sendFeedback(Component.literal("§7[§dirc§7]§r 请在修改配置文件后重连，当前连接不会重载。"));
                            return 1;
                        }))
        ));
    }
}
