package io.github.okf;

import java.net.URI;
import java.nio.file.*;
import java.util.*;

final class SectionBundle {
    static int write(Path root, Path directory, List<SectionSplitter.Section> sections, Map<String, Object> base) throws Exception {
        var paths = new ArrayList<Path>();
        var groupIds = new LinkedHashMap<String, String>();
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            String group = section.context().isEmpty() ? "Overview" : section.context().getFirst();
            String folder = groupIds.computeIfAbsent(group, k -> String.format(Locale.ROOT, "%03d-%s", groupIds.size() + 1, SectionSplitter.slug(k)));
            paths.add(directory.resolve(folder).resolve(String.format(Locale.ROOT, "%04d-%s.md", i + 1, SectionSplitter.slug(section.title()))));
        }
        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);
            Map<String, Object> metadata = new LinkedHashMap<>(base);
            metadata.put("title", section.title());
            metadata.put("document", base.get("title"));
            metadata.put("description", SectionSplitter.preview(section.body()));
            metadata.put("section_path", section.context());
            metadata.put("section_number", i + 1);
            metadata.put("section_count", sections.size());
            metadata.put("split_method", section.method());
            if (section.pageStart() > 0) {
                metadata.put("source_page_start", section.pageStart());
                metadata.put("source_page_end", section.pageEnd());
            }
            StringBuilder body = new StringBuilder("# " + section.title().replaceAll("[\\r\\n]", " ") + "\n\n");
            body.append("Document: ").append(DocumentExtractor.escapeCell(base.get("title").toString()))
                    .append(SourceAttribution.reference(metadata)).append("\n\n");
            if (!section.context().isEmpty()) body.append("Context: ").append(String.join(" > ", section.context())).append("\n\n");
            if (section.pageStart() > 0) body.append("Source PDF pages: ").append(section.pageStart()).append('–').append(section.pageEnd()).append(" (1-based).\n\n");
            body.append(section.body().strip()).append("\n\n---\n\n");
            body.append("[Topic index](").append(link(paths.get(i).getParent(), root.resolve("index.md"))).append(')');
            if (i > 0) body.append(" · [Previous section](").append(link(paths.get(i).getParent(), paths.get(i - 1))).append(')');
            if (i + 1 < paths.size()) body.append(" · [Next section](").append(link(paths.get(i).getParent(), paths.get(i + 1))).append(')');
            body.append("\n\n").append(SourceAttribution.definition(metadata, section.pageStart()));
            Files.createDirectories(paths.get(i).getParent());
            Files.writeString(paths.get(i), Frontmatter.write(metadata, body.toString()), StandardOpenOption.CREATE_NEW);
        }
        return sections.size();
    }
    static String link(Path from, Path to) throws Exception {
        return new URI(null, null, from.relativize(to).toString().replace('\\', '/'), null).toASCIIString().replace("(", "%28").replace(")", "%29");
    }
}
