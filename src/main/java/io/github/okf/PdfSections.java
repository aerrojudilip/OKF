package io.github.okf;

import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

/** Uses PDF bookmarks and their page destinations; falls back to page groups without an outline. */
final class PdfSections {
    private record Topic(String title, List<String> context, int page) {}
    private record Boundary(int offset, Topic topic) {}

    static List<SectionSplitter.Section> extract(Path source, ConverterOptions options) throws Exception {
        if (Files.size(source) > options.maxBytes()) throw new java.io.IOException("Input exceeds conversion.max.bytes: " + source);
        var pages = new OcrExtractor(options).pages(source);
        var topics = new ArrayList<Topic>();
        try (var pdf = Loader.loadPDF(source.toFile())) {
            var outline = pdf.getDocumentCatalog().getDocumentOutline();
            if (outline != null) collect(outline, pdf, List.of(), topics, 0);
        }
        int[] starts = new int[pages.size() + 1];
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            starts[i] = document.length();
            document.append(pages.get(i).text()).append("\n\n");
        }
        starts[pages.size()] = document.length();
        var boundaries = new TreeMap<Integer, Boundary>();
        String name = source.getFileName().toString();
        boundaries.put(0, new Boundary(0, new Topic("Document overview", List.of("Overview"), 1)));
        for (Topic topic : topics) {
            if (topic.page() < 1 || topic.page() > pages.size()) continue;
            String pageText = pages.get(topic.page() - 1).text();
            int location = findHeading(pageText, topic.title());
            int offset = starts[topic.page() - 1] + Math.max(0, location);
            // Same-page unmatched bookmarks share the page boundary: retain the broader topic.
            boundaries.putIfAbsent(offset, new Boundary(offset, topic));
        }
        if (topics.isEmpty()) {
            boundaries.clear();
            int first = 0, length = 0;
            for (int i = 0; i < pages.size(); i++) {
                if (i == first) {
                    String title = pages.get(i).text().lines().map(String::strip).filter(s -> s.length() > 8 && s.length() < 130).findFirst().orElse(name);
                    boundaries.put(starts[i], new Boundary(starts[i], new Topic(title + " (page " + (i + 1) + ")", List.of("Page groups"), i + 1)));
                }
                length += pages.get(i).text().length();
                if (length >= options.sectionTargetChars()) { first = i + 1; length = 0; }
            }
        }
        var ordered = new ArrayList<>(boundaries.values());
        var sections = new ArrayList<SectionSplitter.Section>();
        for (int i = 0; i < ordered.size(); i++) {
            var boundary = ordered.get(i);
            int end = i + 1 < ordered.size() ? ordered.get(i + 1).offset() : document.length();
            String text = document.substring(boundary.offset(), end).strip();
            if (text.isBlank()) continue;
            int pageStart = pageAt(starts, boundary.offset());
            int pageEnd = pageAt(starts, Math.max(boundary.offset(), end - 1));
            var section = new SectionSplitter.Section(boundary.topic().title(), boundary.topic().context(), text,
                    pageStart, pageEnd, topics.isEmpty() ? "page-group-fallback" : "pdf-bookmark");
            sections.addAll(SectionSplitter.split(section, options.sectionTargetChars()));
        }
        return sections;
    }

    private static int pageAt(int[] starts, int offset) {
        int page = Arrays.binarySearch(starts, offset);
        return page >= 0 ? Math.min(page + 1, starts.length - 1) : -page - 1;
    }
    private static void collect(PDOutlineNode parent, PDDocument pdf, List<String> context, List<Topic> topics, int depth) throws Exception {
        if (depth > 20) return;
        for (var item : parent.children()) {
            String title = Objects.toString(item.getTitle(), "Section").replaceAll("[\\p{Z}\\s]+", " ").strip();
            var path = new ArrayList<>(context); path.add(title);
            var destination = item.findDestinationPage(pdf);
            if (destination != null) topics.add(new Topic(title, List.copyOf(path), pdf.getPages().indexOf(destination) + 1));
            collect(item, pdf, path, topics, depth + 1);
        }
    }
    private static int findHeading(String page, String title) {
        String key = normalized(title);
        if (key.isBlank()) return -1;
        String[] lines = page.split("\n", -1);
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            String candidate = "";
            for (int count = 0; count < 3 && i + count < lines.length; count++) {
                candidate += lines[i + count];
                if (normalized(candidate).equals(key)) return offset;
            }
            offset += lines[i].length() + 1;
        }
        return -1;
    }
    private static String normalized(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
