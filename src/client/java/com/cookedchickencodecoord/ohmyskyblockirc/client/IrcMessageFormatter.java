package com.cookedchickencodecoord.ohmyskyblockirc.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Converts the server's § formatting into Minecraft Components without custom rendering. */
final class IrcMessageFormatter {
    private IrcMessageFormatter() {}

    static Component parse(String text) {
        if (text == null || text.isEmpty()) return Component.empty();

        MutableComponent result = Component.empty();
        StringBuilder current = new StringBuilder();
        StyleState style = new StyleState();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                if (current.length() > 0) {
                    result.append(Component.literal(current.toString()).withStyle(style.toStyle()));
                    current.setLength(0);
                }
                style.apply(Character.toLowerCase(text.charAt(++i)));
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.append(Component.literal(current.toString()).withStyle(style.toStyle()));
        }
        return result;
    }

    private static final class StyleState {
        int color = -1;
        boolean obfuscated;
        boolean bold;
        boolean strikethrough;
        boolean underlined;
        boolean italic;

        void apply(char code) {
            switch (code) {
                case '0' -> resetColor(0x000000);
                case '1' -> resetColor(0x0000AA);
                case '2' -> resetColor(0x00AA00);
                case '3' -> resetColor(0x00AAAA);
                case '4' -> resetColor(0xAA0000);
                case '5' -> resetColor(0xAA00AA);
                case '6' -> resetColor(0xFFAA00);
                case '7' -> resetColor(0xAAAAAA);
                case '8' -> resetColor(0x555555);
                case '9' -> resetColor(0x5555FF);
                case 'a' -> resetColor(0x55FF55);
                case 'b' -> resetColor(0x55FFFF);
                case 'c' -> resetColor(0xFF5555);
                case 'd' -> resetColor(0xFF55FF);
                case 'e' -> resetColor(0xFFFF55);
                case 'f' -> resetColor(0xFFFFFF);
                case 'k' -> obfuscated = true;
                case 'l' -> bold = true;
                case 'm' -> strikethrough = true;
                case 'n' -> underlined = true;
                case 'o' -> italic = true;
                case 'r' -> reset();
                default -> { }
            }
        }

        private void resetColor(int value) {
            color = value;
            obfuscated = false;
            bold = false;
            strikethrough = false;
            underlined = false;
            italic = false;
        }

        private void reset() {
            color = -1;
            obfuscated = false;
            bold = false;
            strikethrough = false;
            underlined = false;
            italic = false;
        }

        net.minecraft.network.chat.Style toStyle() {
            var style = net.minecraft.network.chat.Style.EMPTY;
            if (color >= 0) style = style.withColor(color);
            if (obfuscated) style = style.withObfuscated(true);
            if (bold) style = style.withBold(true);
            if (strikethrough) style = style.withStrikethrough(true);
            if (underlined) style = style.withUnderlined(true);
            if (italic) style = style.withItalic(true);
            return style;
        }
    }
}
