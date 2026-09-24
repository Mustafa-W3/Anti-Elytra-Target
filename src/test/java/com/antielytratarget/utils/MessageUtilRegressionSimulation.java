package com.antielytratarget.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MessageUtilRegressionSimulation {

    private static final Set<String> PLACEHOLDERS = Set.of(
            "player", "uuid", "ping", "gamemode", "world",
            "x", "y", "z", "check", "value", "flags", "window",
            "stage", "victim", "time", "location");

    private MessageUtilRegressionSimulation() {
    }

    public static void main(String[] args) {
        templateMatchesLegacyRendering();
        placeholdersInsideTagsUseCompatibilityPath();
        formattedDynamicValuesUseCompatibilityPath();
        componentCacheRemainsBounded();
        System.out.println("MessageUtil regression simulations passed.");
    }

    private static void templateMatchesLegacyRendering() {
        Map<String, String> values = sampleValues();
        assertEquivalent(
                "<gray>&l[&x&F&F&0&0&0&0&lAET<gray>&l] <reset>"
                        + "<yellow>{player} <dark_gray>| <red>{check} "
                        + "<dark_gray>| <gray>{flags}x <dark_gray>| <gray>{location}",
                values, Map.of());
        assertEquivalent(
                "<gray>Username: <white>{player}\n"
                        + "<gray>Ping: <green>{ping}ms\n"
                        + "<gray>Position: <white>{world} <yellow>{x}<gray>, "
                        + "<yellow>{y}<gray>, <yellow>{z}\n"
                        + "<gray>Stage: {stage}\n"
                        + "<gray>Victim: <white>{victim}",
                values, Map.of());
        assertEquivalent(
                "<bold>{player}</bold> "
                        + "<hover:show_text:'static hover'><yellow>{check}</yellow></hover>",
                values, Map.of());
        assertEquivalent("<gray>{stage} tail", values, Map.of());
    }

    private static void placeholdersInsideTagsUseCompatibilityPath() {
        Map<String, String> values = sampleValues();
        assertFallbackEquivalent(
                "<click:run_command:'/aet tpa {player}'>"
                + "<hover:show_text:'Inspect {player}'><yellow>{player}</yellow></hover>"
                + "</click>",
                values);
        assertFallbackEquivalent(
                "<gradient:red:gold><bold>{player}</bold></gradient>",
                values);
        assertFallbackEquivalent(
                "<pride:transgender>{player}</pride>",
                values);
        assertFallbackEquivalent(
                "<transition:red:blue:0.5>{player}</transition>",
                values);
    }

    private static void assertFallbackEquivalent(
            String source, Map<String, String> values) {
        MessageUtil.CompiledTemplate template =
                MessageUtil.compileTemplate(source, PLACEHOLDERS);
        if (!template.usesFallbackParsing()) {
            throw new AssertionError(
                    "A context-sensitive placeholder must use fallback parsing: " + source);
        }

        Component expected = MessageUtil.colorize(
                MessageUtil.applyPlaceholders(source, values));
        Component actual = template.render(values);
        if (!semanticTrace(expected).equals(semanticTrace(actual))) {
            throw new AssertionError(
                    "Fallback template rendering changed the Component: " + source);
        }
    }

    private static void componentCacheRemainsBounded() {
        MessageUtil.clearComponentCache();
        for (int i = 0; i < 700; i++) {
            MessageUtil.colorize("<gray>cache-entry-" + i);
        }
        if (MessageUtil.componentCacheSize() > 512) {
            throw new AssertionError("Component cache exceeded its configured bound");
        }
        MessageUtil.clearComponentCache();
    }

    private static void formattedDynamicValuesUseCompatibilityPath() {
        String source = "<gray>{player} tail";
        Map<String, String> values = sampleValues();
        values.put("player", "&cFormatted");
        MessageUtil.CompiledTemplate template =
                MessageUtil.compileTemplate(source, PLACEHOLDERS);
        if (template.usesFallbackParsing()) {
            throw new AssertionError("A normal template should compile to the fast path");
        }

        Component expected = MessageUtil.colorize(
                MessageUtil.applyPlaceholders(source, values));
        Component actual = template.render(values);
        if (!semanticTrace(expected).equals(semanticTrace(actual))) {
            throw new AssertionError(
                    "Formatted dynamic value changed legacy parsing semantics");
        }
    }

    private static void assertEquivalent(String source,
                                         Map<String, String> values,
                                         Map<String, Component> components) {
        MessageUtil.CompiledTemplate template =
                MessageUtil.compileTemplate(source, PLACEHOLDERS);
        if (template.usesFallbackParsing()) {
            throw new AssertionError("Normal text placeholders unexpectedly used fallback parsing");
        }

        Component expected = MessageUtil.colorize(
                MessageUtil.applyPlaceholders(source, values));
        Component actual = template.render(values, components);
        List<StyledRun> expectedTrace = semanticTrace(expected);
        List<StyledRun> actualTrace = semanticTrace(actual);
        if (!expectedTrace.equals(actualTrace)) {
            throw new AssertionError("Compiled rendering changed output for: " + source
                    + "\nexpected=" + expectedTrace + "\nactual=" + actualTrace);
        }
    }

    private static List<StyledRun> semanticTrace(Component component) {
        StyledTraceListener listener = new StyledTraceListener();
        ComponentFlattener.basic().flatten(component, listener);
        return List.copyOf(listener.runs);
    }

    private record StyledRun(String text, Style style) {
    }

    private static final class StyledTraceListener implements FlattenerListener {

        private final List<StyledRun> runs = new ArrayList<>();
        private final Deque<Style> parents = new ArrayDeque<>();
        private Style current = Style.empty();

        @Override
        public void pushStyle(Style style) {
            parents.push(current);
            current = style.merge(current, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        }

        @Override
        public void component(String text) {
            if (text.isEmpty()) return;
            int segmentStart = 0;
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != '\n') continue;
                append(text.substring(segmentStart, i), current);

append("\n", Style.empty());
                segmentStart = i + 1;
            }
            append(text.substring(segmentStart), current);
        }

        private void append(String text, Style style) {
            if (text.isEmpty()) return;
            int lastIndex = runs.size() - 1;
            if (lastIndex >= 0 && runs.get(lastIndex).style().equals(style)) {
                StyledRun previous = runs.get(lastIndex);
                runs.set(lastIndex, new StyledRun(previous.text() + text, style));
            } else {
                runs.add(new StyledRun(text, style));
            }
        }

        @Override
        public void popStyle(Style style) {
            current = parents.pop();
        }
    }

    private static Map<String, String> sampleValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", "TestPlayer");
        values.put("uuid", "00000000-0000-0000-0000-000000000001");
        values.put("ping", "42");
        values.put("gamemode", "SURVIVAL");
        values.put("world", "world");
        values.put("x", "12.5");
        values.put("y", "80.0");
        values.put("z", "-4.5");
        values.put("check", "UseItemRotationMismatch");
        values.put("value", "91.2500");
        values.put("flags", "7");
        values.put("window", "3");
        values.put("stage", "<red>BLOCK");
        values.put("victim", "Target");
        values.put("time", "15:42:00");
        values.put("location", "world x=12.5 y=80.0 z=-4.5");
        return values;
    }
}
