package io.github.okf;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic heading/block segmentation; preserves complete tables and fenced code blocks. */
final class SectionSplitter {
    record Section(String title, List<String> context, String body, int pageStart, int pageEnd, String method) {}
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*$");

    static List<Section> markdown(String body, String title, int target) {
        var sections = new ArrayList<Section>();
        var context = new ArrayList<String>();
        String current = title;
        StringBuilder text = new StringBuilder();
        String fence = null;
        for (String line : body.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.stripLeading();
            var heading = HEADING.matcher(line);
            if (fence == null && heading.matches()) {
                if (!text.toString().isBlank()) sections.add(new Section(current, List.copyOf(context), text.toString(), 0, 0, "heading"));
                text.setLength(0);
                int level = heading.group(1).length();
                while (context.size() >= level) context.removeLast();
                current = heading.group(2);
                context.add(current);
            }
            text.append(line).append('\n');
            if (fence == null && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) fence = marker(trimmed);
            else if (fence != null && trimmed.matches(Pattern.quote(fence.charAt(0) + "") + "{" + fence.length() + ",}\\s*")) fence = null;
        }
        if (!text.toString().isBlank()) sections.add(new Section(current, List.copyOf(context), text.toString(), 0, 0, "heading"));
        if (sections.isEmpty()) sections.add(new Section(title, List.of(title), "_Empty source document._", 0, 0, "document"));
        return sections.stream().flatMap(s -> split(s, target).stream()).toList();
    }

    static List<Section> split(Section section, int target) {
        List<String> blocks = new ArrayList<>();
        for (String block : blocks(section.body())) {
            String trimmed = block.stripLeading();
            if (block.length() > target && !trimmed.startsWith("```") && !trimmed.startsWith("~~~") && !trimmed.startsWith("|")) {
                // Long extracted prose often has line breaks but no empty paragraph separators.
                StringBuilder prose = new StringBuilder();
                for (String line : block.split("(?<=\\n)|(?<=[.!?])(?=\\s)")) {
                    if (!prose.isEmpty() && prose.length() + line.length() > target) { blocks.add(prose.toString()); prose.setLength(0); }
                    prose.append(line);
                }
                if (!prose.isEmpty()) blocks.add(prose.toString());
            } else blocks.add(block);
        }
        var parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (String block : blocks) {
            if (!current.isEmpty() && current.length() + block.length() > target) { parts.add(current.toString().strip()); current.setLength(0); }
            current.append(block).append("\n\n");
        }
        if (!current.toString().isBlank()) parts.add(current.toString().strip());
        var result = new ArrayList<Section>();
        for (int i = 0; i < parts.size(); i++) result.add(new Section(section.title() + (parts.size() > 1 ? " — part " + (i + 1) : ""),
                section.context(), parts.get(i), section.pageStart(), section.pageEnd(), section.method()));
        return result;
    }

    private static List<String> blocks(String body) {
        var blocks = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        String fence = null;
        boolean table = false;
        for (String line : body.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (fence == null && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                flush(blocks, current); fence = marker(trimmed); table = false;
            } else if (fence == null) {
                boolean row = trimmed.startsWith("|");
                if (row != table || trimmed.isEmpty()) flush(blocks, current);
                table = row;
            }
            current.append(line).append('\n');
            if (fence != null && trimmed.matches(Pattern.quote(fence.charAt(0) + "") + "{" + fence.length() + ",}\\s*") && !current.toString().strip().equals(trimmed)) {
                fence = null; flush(blocks, current);
            }
        }
        flush(blocks, current);
        return blocks;
    }
    private static void flush(List<String> blocks, StringBuilder current) {
        if (!current.toString().isBlank()) blocks.add(current.toString()); current.setLength(0);
    }
    private static String marker(String text) { int end = 0; while (end < text.length() && text.charAt(end) == text.charAt(0)) end++; return text.substring(0, end); }

    static String slug(String title) {
        String value = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-|-$", "");
        if (value.isBlank()) value = "section";
        return value.substring(0, Math.min(65, value.length()));
    }
    static String preview(String text) {
        String plain = text.replaceAll("(?m)^#+\\s+.*$", "").replaceAll("[`|#*]", " ").replaceAll("\\s+", " ").strip();
        return plain.substring(0, Math.min(200, plain.length()));
    }
}
