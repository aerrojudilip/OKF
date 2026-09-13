package io.github.okf;

import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

/** Retains XHTML block order and native table grids; spans occupy blank continuation cells. */
final class MarkdownTables {
    static String fromXhtml(String xhtml, boolean firstRowHeader, int limit) {
        StringBuilder out = new StringBuilder();
        render(Jsoup.parse(xhtml).body(), out, firstRowHeader, limit);
        return out.toString().strip();
    }

    private static void render(Node node, StringBuilder out, boolean header, int limit) {
        if (node instanceof TextNode text) out.append(DocumentExtractor.escapeCell(text.text()));
        else if (node instanceof Element element) {
            String tag = element.normalName();
            if (Set.of("script", "style", "head").contains(tag)) return;
            if (tag.equals("table")) {
                out.append("\n\n").append(table(rows(element), header, limit)).append('\n');
                return;
            }
            boolean block = element.isBlock();
            if (block) out.append("\n\n");
            if (tag.matches("h[1-6]")) out.append("#".repeat(tag.charAt(1) - '0')).append(' ');
            if (tag.equals("li")) out.append("* ");
            if (tag.equals("br")) out.append("\n");
            for (Node child : element.childNodes()) render(child, out, header, limit);
            if (block) out.append("\n\n");
        }
        check(out, limit);
    }

    private static List<List<String>> rows(Element table) {
        var grid = new ArrayList<List<String>>();
        int row = 0;
        for (Element tr : table.select("tr")) {
            if (tr.closest("table") != table) continue;
            ensureRow(grid, row);
            int col = 0;
            for (Element cell : tr.children()) {
                if (!Set.of("td", "th").contains(cell.normalName())) continue;
                while (col < grid.get(row).size() && grid.get(row).get(col) != null) col++;
                int height = span(cell, "rowspan"), width = span(cell, "colspan");
                if ((long) (row + height) * (col + width) > 1_000_000) throw new IllegalArgumentException("Table grid exceeds one million cells");
                for (int r = row; r < row + height; r++) {
                    ensureRow(grid, r);
                    while (grid.get(r).size() < col + width) grid.get(r).add(null);
                    for (int c = col; c < col + width; c++) grid.get(r).set(c, r == row && c == col ? cell.wholeText().strip() : "");
                }
                col += width;
            }
            row++;
        }
        return grid;
    }

    private static int span(Element cell, String key) {
        if (!cell.hasAttr(key)) return 1;
        try {
            int value = Integer.parseInt(cell.attr(key));
            if (value < 1 || value > 1000) throw new IllegalArgumentException("Table span must be 1..1000");
            return value;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid table span", e); }
    }
    private static void ensureRow(List<List<String>> grid, int r) { while (grid.size() <= r) grid.add(new ArrayList<>()); }

    static String table(List<List<String>> rows, boolean firstRowHeader, int limit) {
        if (rows.isEmpty()) return "_Empty table._\n";
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        if (width == 0) return "_Empty table._\n";
        StringBuilder out = new StringBuilder();
        if (!firstRowHeader) {
            List<String> header = new ArrayList<>();
            for (int c = 1; c <= width; c++) header.add("Column " + c);
            appendRow(out, header, width); separator(out, width);
        }
        for (int r = 0; r < rows.size(); r++) {
            appendRow(out, rows.get(r), width);
            if (r == 0 && firstRowHeader) separator(out, width);
            check(out, limit);
        }
        return out.toString();
    }
    private static void appendRow(StringBuilder out, List<String> row, int width) {
        out.append('|');
        for (int c = 0; c < width; c++) out.append(' ').append(DocumentExtractor.escapeCell(c < row.size() && row.get(c) != null ? row.get(c) : "")).append(" |");
        out.append('\n');
    }
    private static void separator(StringBuilder out, int width) { out.append("| --- ".repeat(width)).append("|\n"); }
    private static void check(StringBuilder out, int limit) {
        if (out.length() > limit) throw new IllegalArgumentException("Rendered content exceeds conversion.max.chars");
    }
}
