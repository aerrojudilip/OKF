package io.github.okf;

import java.nio.file.*;
import java.util.*;

/** On-demand lexical ranking of Markdown files; no persistent database or external service. */
public final class KeywordSearch {
    public record Hit(String path, String title, String excerpt, double score) {}
    private record Entry(Path path, String title, String text, Map<String, Integer> terms, int length) {}
    private static final Set<String> STOP = Set.of("a", "an", "the", "is", "are", "how", "do", "does", "i", "to", "of", "in", "for", "and", "or", "with", "what", "can", "it", "on", "my", "be");

    public List<Hit> search(Path root, String query, int limit) throws Exception {
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Bundle must be a directory");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("--limit must be 1..100");
        Set<String> terms = frequencies(query).keySet();
        if (terms.isEmpty()) throw new IllegalArgumentException("Query needs searchable keywords");
        var entries = new ArrayList<Entry>();
        var documentFrequency = new HashMap<String, Integer>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && p.toString().endsWith(".md")).sorted().toList()) {
                if (Set.of("index.md", "log.md").contains(path.getFileName().toString())) continue;
                var doc = Frontmatter.parse(Files.readString(path));
                if (Set.of("Navigation", "Retrieval Guide").contains(Objects.toString(doc.metadata().get("type"), ""))) continue;
                String title = Objects.toString(doc.metadata().get("title"), path.getFileName().toString());
                String body = doc.body();
                // Exclude generated navigation; source body remains unchanged on disk.
                int nav = body.lastIndexOf("\n---\n\n[Topic index]");
                if (nav >= 0) body = body.substring(0, nav);
                var counts = frequencies(body);
                var entry = new Entry(path, title, body, counts, counts.values().stream().mapToInt(Integer::intValue).sum());
                entries.add(entry);
                for (String term : terms) if (counts.containsKey(term)) documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double average = entries.stream().mapToInt(Entry::length).average().orElse(1);
        var hits = new ArrayList<Hit>();
        for (Entry entry : entries) {
            double score = 0;
            var titleTerms = frequencies(entry.title());
            for (String term : terms) {
                int tf = entry.terms().getOrDefault(term, 0);
                if (tf == 0) continue;
                double idf = Math.log(1 + (entries.size() - documentFrequency.getOrDefault(term, 0) + 0.5) / (documentFrequency.getOrDefault(term, 0) + 0.5));
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * entry.length() / Math.max(1, average)));
                if (titleTerms.containsKey(term)) score += 2 * idf;
            }
            if (score > 0) hits.add(new Hit(root.relativize(entry.path()).toString().replace('\\', '/'), entry.title(), excerpt(entry.text(), terms), score));
        }
        return hits.stream().sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::path)).limit(limit).toList();
    }
    private static Map<String, Integer> frequencies(String text) {
        Map<String, Integer> counts = new HashMap<>();
        for (String term : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (term.length() > 1 && !STOP.contains(term)) counts.merge(term, 1, Integer::sum);
        return counts;
    }
    private static String excerpt(String text, Set<String> terms) {
        String plain = text.replaceAll("\\s+", " ").strip();
        String lower = plain.toLowerCase(Locale.ROOT);
        int found = terms.stream().mapToInt(lower::indexOf).filter(i -> i >= 0).min().orElse(0);
        int start = Math.max(0, found - 70), end = Math.min(plain.length(), start + 400);
        return (start > 0 ? "…" : "") + plain.substring(start, end) + (end < plain.length() ? "…" : "");
    }
}
