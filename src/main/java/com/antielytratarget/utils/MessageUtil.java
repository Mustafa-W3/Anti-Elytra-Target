package com.antielytratarget.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageUtil {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int COMPONENT_CACHE_LIMIT = 512;
    private static volatile ComponentCache componentCache = new ComponentCache();

    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacySection();

    private static final LegacyComponentSerializer AMPERSAND_SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

private static final Pattern HEX_PATTERN =
            Pattern.compile("&#([0-9a-fA-F]{6})");

private static final Pattern HEX_ANGLE_PATTERN =
            Pattern.compile("<#([0-9a-fA-F]{6})>");

    private MessageUtil() {}

public static Component colorize(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        ComponentCache cache = componentCache;
        Component cached = cache.components.get(text);
        if (cached != null) return cached;

        Component parsed = colorizeUncached(text);
        Component existing = cache.components.putIfAbsent(text, parsed);
        if (existing != null) return existing;

        cache.insertionOrder.add(text);
        trimComponentCache(cache);
        return parsed;
    }

    private static Component colorizeUncached(String text) {
        String processed = processHexColors(text);
        processed = convertLegacyToMiniMessage(processed);
        try {
            return MINI.deserialize(processed);
        } catch (Exception ignored) {
            return AMPERSAND_SERIALIZER.deserialize(text.replace('\u00a7', '&'));
        }
    }

    public static void clearComponentCache() {
        componentCache = new ComponentCache();
    }

    static int componentCacheSize() {
        return componentCache.components.size();
    }

    private static void trimComponentCache(ComponentCache cache) {
        while (cache.components.size() > COMPONENT_CACHE_LIMIT) {
            String oldest = cache.insertionOrder.poll();
            if (oldest == null) return;
            cache.components.remove(oldest);
        }
    }

    private static final class ComponentCache {
        private final Map<String, Component> components = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<String> insertionOrder =
                new ConcurrentLinkedQueue<>();
    }

public static String legacyColorize(String text) {
        if (text == null || text.isEmpty()) return "";
        String processed = processHexColors(text);
        processed = convertLegacyToMiniMessage(processed);
        try {
            return LEGACY_SERIALIZER.serialize(MINI.deserialize(processed));
        } catch (Exception ignored) {
            return text.replace('&', '\u00a7');
        }
    }

public static void send(CommandSender recipient, String message) {
        if (recipient == null || message == null) return;
        recipient.sendMessage(colorize(message));
    }

public static void send(CommandSender recipient, String message,
                            Map<String, String> placeholders) {
        if (recipient == null || message == null) return;
        String processed = applyPlaceholders(message, placeholders);
        recipient.sendMessage(colorize(processed));
    }

public static String applyPlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null) return text;
        String result = text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

public static Component fromLegacy(String legacyText) {
        if (legacyText == null || legacyText.isEmpty()) return Component.empty();
        return LEGACY_SERIALIZER.deserialize(legacyText);
    }

public static String stripColors(String text) {
        if (text == null) return "";
        return text.replaceAll("§[0-9a-fk-orA-FK-OR]", "")
                   .replaceAll("&#[0-9a-fA-F]{6}", "")
                   .replaceAll("<[^>]+>", "");
    }

private static String processHexColors(String text) {
        boolean hasAmpersandHex = text.indexOf("&#") >= 0;
        boolean hasAngleHex = text.indexOf("<#") >= 0;
        if (!hasAmpersandHex && !hasAngleHex) return text;

        if (hasAmpersandHex) {
            Matcher matcher = HEX_PATTERN.matcher(text);
            StringBuilder sb = new StringBuilder(text.length() + 16);
            while (matcher.find()) {
                matcher.appendReplacement(sb, "<color:#" + matcher.group(1) + ">");
            }
            matcher.appendTail(sb);
            text = sb.toString();
        }

        if (hasAngleHex) {
            Matcher matcher = HEX_ANGLE_PATTERN.matcher(text);
            StringBuilder sb = new StringBuilder(text.length() + 16);
            while (matcher.find()) {
                matcher.appendReplacement(sb, "<color:#" + matcher.group(1) + ">");
            }
            matcher.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

private static String convertLegacyToMiniMessage(String text) {
        if (text.indexOf('&') < 0 && text.indexOf('\u00a7') < 0) return text;

        StringBuilder result = new StringBuilder(text.length() + 16);
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char marker = chars[i];
            if ((marker == '&' || marker == '\u00a7') && i + 1 < chars.length) {
                String hexTag = readLegacyHex(chars, i);
                if (hexTag != null) {
                    result.append(hexTag);
                    i += 13;
                    continue;
                }

                char code = chars[i + 1];
                String tag = legacyToTag(code);
                if (tag != null) {
                    result.append(tag);
                    i++;
                    continue;
                }
            }
            result.append(marker);
        }
        return result.toString();
    }

private static String readLegacyHex(char[] chars, int start) {
        if (start + 13 >= chars.length) return null;
        char marker = chars[start];
        if ((marker != '&' && marker != '\u00a7')
                || Character.toLowerCase(chars[start + 1]) != 'x') {
            return null;
        }

        StringBuilder hex = new StringBuilder(6);
        for (int i = start + 2; i <= start + 12; i += 2) {
            if (chars[i] != marker || !isHex(chars[i + 1])) return null;
            hex.append(chars[i + 1]);
        }
        return "<color:#" + hex + ">";
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')
                || (c >= 'A' && c <= 'F');
    }

private static String legacyToTag(char code) {
        return switch (Character.toLowerCase(code)) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default  -> null;
        };
    }

    public static CompiledTemplate compileTemplate(
            String template, Collection<String> placeholderNames) {
        return new CompiledTemplate(template, placeholderNames);
    }

    public static final class CompiledTemplate {

        private static final String MARKER_PREFIX = "\uE000aet:";
        private static final String MARKER_SUFFIX = "\uE001";

        private final String source;
        private final Component component;
        private final List<PlaceholderSlot> slots;
        private final Map<String, PlaceholderSlot> slotsByMarker;
        private final Pattern markerPattern;
        private final boolean fallbackParsing;

        private CompiledTemplate(String template, Collection<String> placeholderNames) {
            this.source = template == null ? "" : template;

            String compiledSource = this.source;
            List<PlaceholderSlot> found = new ArrayList<>();
            boolean fallback = false;
            if (placeholderNames != null) {
                for (String name : placeholderNames) {
                    if (name == null || name.isEmpty()) continue;
                    String placeholder = "{" + name + "}";
                    if (compiledSource.indexOf(placeholder) < 0) continue;
                    if (requiresFallbackParsing(this.source, placeholder)) {
                        fallback = true;
                        break;
                    }

                    String marker = MARKER_PREFIX + name + MARKER_SUFFIX;
                    compiledSource = compiledSource.replace(placeholder, marker);
                    found.add(new PlaceholderSlot(name, marker));
                }
            }

            this.fallbackParsing = fallback;
            this.slots = fallback ? List.of() : List.copyOf(found);
            this.component = fallback ? Component.empty() : MessageUtil.colorize(compiledSource);
            if (fallback || found.isEmpty()) {
                this.slotsByMarker = Map.of();
                this.markerPattern = null;
            } else {
                Map<String, PlaceholderSlot> markerSlots = new HashMap<>(found.size());
                StringBuilder pattern = new StringBuilder();
                for (PlaceholderSlot slot : found) {
                    if (!pattern.isEmpty()) pattern.append('|');
                    pattern.append(Pattern.quote(slot.marker()));
                    markerSlots.put(slot.marker(), slot);
                }
                this.slotsByMarker = Map.copyOf(markerSlots);
                this.markerPattern = Pattern.compile(pattern.toString());
            }
        }

        public Component render(Map<String, String> placeholders) {
            return render(placeholders, Map.of());
        }

        public Component render(Map<String, String> placeholders,
                                Map<String, Component> componentPlaceholders) {
            Map<String, String> values = placeholders == null ? Map.of() : placeholders;
            Map<String, Component> components =
                    componentPlaceholders == null ? Map.of() : componentPlaceholders;
            if (fallbackParsing || requiresRuntimeParsing(values, components)) {
                return MessageUtil.colorize(MessageUtil.applyPlaceholders(source, values));
            }
            if (markerPattern == null) return component;

            return component.replaceText(TextReplacementConfig.builder()
                    .match(markerPattern)
                    .replacement((match, ignored) -> {
                PlaceholderSlot slot = slotsByMarker.get(match.group());
                String value = values.get(slot.name());
                Component replacement = components.get(slot.name());
                if (replacement == null) {
                    replacement = Component.text(value != null
                            ? value : "{" + slot.name() + "}");
                }
                return replacement;
            }).build());
        }

        public boolean usesFallbackParsing() {
            return fallbackParsing;
        }

        private boolean requiresRuntimeParsing(
                Map<String, String> values,
                Map<String, Component> componentPlaceholders) {
            for (PlaceholderSlot slot : slots) {
                if (componentPlaceholders.get(slot.name()) != null) continue;
                String value = values.get(slot.name());
                if (value != null && (value.indexOf('<') >= 0
                        || value.indexOf('&') >= 0
                        || value.indexOf('\u00a7') >= 0)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean requiresFallbackParsing(
                String text, String placeholder) {
            int offset = 0;
            while ((offset = text.indexOf(placeholder, offset)) >= 0) {
                if (isInsideMiniMessageTag(text, offset)
                        || isInsideLengthSensitiveTag(text, offset)) {
                    return true;
                }
                offset += placeholder.length();
            }
            return false;
        }

        private static boolean isInsideMiniMessageTag(String text, int offset) {
            int lastOpen = text.lastIndexOf('<', offset);
            if (lastOpen < 0) return false;
            int tagEnd = findTagEnd(text, lastOpen);
            return tagEnd < 0 || tagEnd >= offset;
        }

        private static boolean isInsideLengthSensitiveTag(
                String text, int offset) {
            String lower = text.toLowerCase(Locale.ROOT);
            int colorChangingDepth = 0;
            int cursor = 0;
            while (cursor < offset) {
                int tagStart = lower.indexOf('<', cursor);
                if (tagStart < 0 || tagStart >= offset) break;
                int tagEnd = findTagEnd(lower, tagStart);
                if (tagEnd < 0 || tagEnd >= offset) break;

                String body = lower.substring(tagStart + 1, tagEnd).trim();
                boolean closing = body.startsWith("/");
                if (closing) body = body.substring(1).trim();
                int nameEnd = 0;
                while (nameEnd < body.length()) {
                    char c = body.charAt(nameEnd);
                    if (c == ':' || c == '/' || Character.isWhitespace(c)) break;
                    nameEnd++;
                }
                String name = body.substring(0, nameEnd);
                boolean selfClosing = body.endsWith("/");
                if ("reset".equals(name)) {
                    colorChangingDepth = 0;
                } else if ("gradient".equals(name)
                        || "rainbow".equals(name)
                        || "pride".equals(name)
                        || "transition".equals(name)) {
                    if (closing) {
                        colorChangingDepth = Math.max(0, colorChangingDepth - 1);
                    } else if (!selfClosing) {
                        colorChangingDepth++;
                    }
                }
                cursor = tagEnd + 1;
            }
            return colorChangingDepth > 0;
        }

        private static int findTagEnd(String text, int tagStart) {
            char quote = 0;
            boolean escaped = false;
            for (int i = tagStart + 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (quote != 0) {
                    if (c == quote) quote = 0;
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '>') {
                    return i;
                }
            }
            return -1;
        }

        private record PlaceholderSlot(String name, String marker) {
        }
    }
}
