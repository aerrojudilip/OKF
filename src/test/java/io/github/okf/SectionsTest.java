package io.github.okf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.*;

class SectionsTest {
    @TempDir Path temp;
    private ConverterOptions options() throws Exception {
        Path config = temp.resolve("sections.properties");
        Files.writeString(config, "ocr.enabled=false\nsplitting.enabled=true\nsplitting.target.chars=500\nindex.max.entries=5\n");
        return ConverterOptions.load(config);
    }

    @Test void keepsCodeAndTablesWholeAndRetainsAllProse() {
        String code = "```java\n# This is not a heading\n" + "long example line\n".repeat(80) + "```";
        String table = "| Name | Value |\n| --- | --- |\n" + "| Item | Value |\n".repeat(70);
        String input = "# Setup\n\nStart installation.\n\n" + code + "\n\n" + table + "\n\n## Authentication\n\nSet a password.\n";
        var sections = SectionSplitter.markdown(input, "Guide", 500);
        assertTrue(sections.stream().anyMatch(s -> s.body().contains(code)));
        assertTrue(sections.stream().anyMatch(s -> s.body().contains(table.stripTrailing())));
        assertTrue(sections.stream().anyMatch(s -> s.title().equals("Authentication") && s.context().equals(List.of("Setup", "Authentication"))));
        assertFalse(sections.stream().anyMatch(s -> s.title().contains("not a heading")));
        assertTrue(sections.stream().anyMatch(s -> s.body().contains("Start installation.")));
        assertTrue(sections.stream().anyMatch(s -> s.body().contains("Set a password.")));
    }

    @Test void writesTopicsNavigationAndSearchesWithoutVectorStore() throws Exception {
        Path source = temp.resolve("guide.md");
        StringBuilder input = new StringBuilder("# Administration\n\nProduct administration overview.\n\n");
        for (int i = 0; i < 32; i++) input.append("## Topic ").append(i).append("\n\nGeneric setup instructions for feature ").append(i).append(".\n\n");
        input.append("## Configure password policies\n\nSet password expiration to 90 days in the security settings.\n");
        Files.writeString(source, input);
        Path out = temp.resolve("bundle");
        var result = new BundleConverter(options()).convert(source, out);
        assertEquals(1, result.converted()); assertEquals(34, result.sections());
        assertFalse(Files.exists(out.resolve("guide.md.md")));
        assertTrue(new OkfValidator().validate(out).isEmpty());
        List<Path> markdown;
        try (var files = Files.walk(out)) { markdown = files.filter(p -> p.toString().endsWith(".md")).toList(); }
        assertEquals(List.of(out.resolve("index.md")), markdown.stream().filter(p -> p.getFileName().toString().equals("index.md")).toList());
        assertFalse(markdown.stream().anyMatch(p -> p.getFileName().toString().startsWith("navigation-")));
        String catalog = Files.readString(out.resolve("index.md"));
        assertEquals(35, catalog.lines().filter(l -> l.startsWith("* ")).count()); // Content plus retrieval guide.
        assertTrue(catalog.contains("## guide.md\n\n### Administration"));
        var linkPattern = java.util.regex.Pattern.compile("\\]\\(([^)]+)\\)");
        for (Path path : markdown) {
            var links = linkPattern.matcher(Files.readString(path));
            while (links.find()) {
                var uri = new java.net.URI(links.group(1));
                if (uri.isAbsolute()) continue;
                Path target = path.getParent().resolve(uri.getPath()).normalize();
                assertTrue(Files.exists(target), path + " -> " + target);
                if (target.getFileName().toString().equals("index.md")) assertEquals(out.resolve("index.md"), target);
            }
        }
        var hits = new KeywordSearch().search(out, "How do I configure password policies?", 3);
        assertEquals("Configure password policies", hits.getFirst().title());
        assertTrue(Files.exists(out.resolve(hits.getFirst().path())));
        assertTrue(hits.getFirst().excerpt().contains("90 days"));
        var doc = Frontmatter.parse(Files.readString(out.resolve(hits.getFirst().path())));
        assertEquals("guide.md", doc.metadata().get("document"));
        assertTrue(doc.body().contains("[Previous section]"));
        var citedSource = (Map<?, ?>) ((List<?>) doc.metadata().get("sources")).getFirst();
        String label = "[^" + citedSource.get("id") + "]";
        assertTrue(doc.body().contains("guide.md" + label));
        assertTrue(doc.body().contains(label + ": [guide.md]("));
    }

    @Test void pdfBookmarksSplitTopicsWithinSamePageWithPageCitations() throws Exception {
        Path source = temp.resolve("manual.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(18); content.newLineAtOffset(30, 700);
                for (String line : List.of("Product Guide", "Installation", "Install the application package.", "Security", "Set the administrator password.")) {
                    content.showText(line); content.newLine();
                }
                content.endText();
            }
            var outline = new PDDocumentOutline(); pdf.getDocumentCatalog().setDocumentOutline(outline);
            for (String title : List.of("Installation", "Security")) {
                var item = new PDOutlineItem(); item.setTitle(title); item.setDestination(page); outline.addLast(item);
            }
            pdf.save(source.toFile());
        }
        var sections = PdfSections.extract(source, options());
        var install = sections.stream().filter(s -> s.title().equals("Installation")).findFirst().orElseThrow();
        var security = sections.stream().filter(s -> s.title().equals("Security")).findFirst().orElseThrow();
        assertTrue(install.body().contains("Install the application"));
        assertFalse(install.body().contains("administrator password"));
        assertTrue(security.body().contains("administrator password"));
        assertEquals(1, security.pageStart()); assertEquals(1, security.pageEnd());
        assertEquals("pdf-bookmark", security.method());
        Path out = temp.resolve("bundle");
        assertEquals(sections.size(), new BundleConverter(options()).convert(source, out).sections());
        assertTrue(new OkfValidator().validate(out).isEmpty());
        var hit = new KeywordSearch().search(out, "administrator password", 1).getFirst();
        assertTrue(Files.readString(out.resolve(hit.path())).contains("#page=1)"));
    }

    @Test void longProseSplitsWithoutDroppingText() {
        String input = "A sentence containing meaningful context and details.\n".repeat(120);
        var parts = SectionSplitter.split(new SectionSplitter.Section("Topic", List.of("Chapter", "Topic"), input, 4, 8, "pdf-bookmark"), 500);
        assertTrue(parts.size() > 1);
        String joined = String.join("", parts.stream().map(SectionSplitter.Section::body).toList());
        assertEquals(input.replaceAll("\\s", ""), joined.replaceAll("\\s", ""));
        assertTrue(parts.stream().allMatch(s -> s.body().length() <= 500 && s.pageStart() == 4 && s.pageEnd() == 8));
    }
}
