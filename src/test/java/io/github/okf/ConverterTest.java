package io.github.okf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import picocli.CommandLine;

class ConverterTest {
    @TempDir Path temp;
    private BundleConverter converter() { return new BundleConverter(10_000_000, 1_000_000); }

    @Test void convertsMixedTreeAndAvoidsReservedNamesAndCollisions() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input"));
        Files.writeString(input.resolve("index.md"), "# Existing index\n");
        Files.writeString(input.resolve("log.md"), "Old log");
        Files.writeString(input.resolve("same.txt"), "Some text");
        Files.writeString(input.resolve("same.json"), "{\"a\":1}");
        Path nested = Files.createDirectory(input.resolve("nested (docs)"));
        Files.writeString(nested.resolve("a [b].csv"), "Name,Note\nA,\"pipe | and\nnewline\"\n");
        Files.write(input.resolve("image.png"), new byte[]{1, 2, 3});
        Path out = temp.resolve("bundle");
        var result = converter().convert(input, out);
        assertEquals(5, result.converted());
        assertEquals(1, result.skipped().size());
        assertTrue(Files.exists(out.resolve("index.md.md")));
        assertTrue(Files.exists(out.resolve("log.md.md")));
        assertTrue(Files.exists(out.resolve("same.txt.md")));
        assertTrue(Files.exists(out.resolve("same.json.md")));
        assertTrue(Files.readString(out.resolve("index.md")).contains("nested%20%28docs%29/"));
        assertTrue(Files.readString(out.resolve("nested (docs)/a [b].csv.md")).contains("pipe &#124; and<br>newline"));
        assertEquals("0.2", Frontmatter.parse(Files.readString(out.resolve("index.md"))).metadata().get("okf_version"));
        assertTrue(new OkfValidator().validate(out).isEmpty());
    }

    @Test void preservesSourceMetadataWithoutImportingVerificationClaims() throws Exception {
        Path source = temp.resolve("guide.md");
        Files.writeString(source, "---\r\ntype: Playbook\r\nverified: {by: 'human:alice'}\r\ncustom: keep\r\n---\r\n# Heading\r\n");
        Path out = temp.resolve("bundle");
        converter().convert(source, out);
        var doc = Frontmatter.parse(Files.readString(out.resolve("guide.md.md")));
        assertEquals("Reference", doc.metadata().get("type"));
        assertFalse(doc.metadata().containsKey("verified"));
        assertEquals("keep", ((Map<?, ?>) doc.metadata().get("source_metadata")).get("custom"));
        assertTrue(doc.body().stripLeading().startsWith("# Heading\n"));
    }

    @Test void neverOverwritesAndCleansUpFailedConversions() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input"));
        Files.writeString(input.resolve("a.txt"), "valid");
        Files.writeString(input.resolve("z.md"), "---\ntype: [unclosed\n---\nbody");
        Path out = temp.resolve("bundle");
        assertThrows(Exception.class, () -> converter().convert(input, out));
        assertFalse(Files.exists(out));
        try (var entries = Files.list(temp)) { assertFalse(entries.anyMatch(p -> p.getFileName().toString().startsWith(".okf-staging-"))); }
        Files.createDirectory(out);
        Files.writeString(out.resolve("keep.txt"), "untouched");
        assertThrows(Exception.class, () -> converter().convert(input, out));
        assertEquals("untouched", Files.readString(out.resolve("keep.txt")));
    }

    @Test void enforcesLimitsAndEscapesEmbeddedFences() throws Exception {
        Path source = temp.resolve("text.txt");
        Files.writeString(source, "hello\n```\nworld");
        Path out = temp.resolve("bundle");
        assertThrows(Exception.class, () -> new BundleConverter(3, 1000).convert(source, out));
        assertThrows(Exception.class, () -> new BundleConverter(1000, 3).convert(source, out));
        assertFalse(Files.exists(out));
        converter().convert(source, out);
        assertTrue(Files.readString(out.resolve("text.txt.md")).contains("````text\nhello\n```\nworld\n````"));
    }

    @Test void extractsPdfWordSpreadsheetAndSlides() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input"));
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700); stream.showText("PDF example content"); stream.endText();
            }
            pdf.save(input.resolve("sample.pdf").toFile());
        }
        try (var doc = new XWPFDocument(); var out = Files.newOutputStream(input.resolve("sample.docx"))) {
            doc.createParagraph().createRun().setText("Word example content"); doc.write(out);
        }
        try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(input.resolve("sample.xlsx"))) {
            book.createSheet("Products").createRow(0).createCell(0).setCellValue("Spreadsheet example content"); book.write(out);
        }
        try (var slides = new XMLSlideShow(); var out = Files.newOutputStream(input.resolve("sample.pptx"))) {
            slides.createSlide().createTextBox().setText("Slides example content"); slides.write(out);
        }
        Path out = temp.resolve("bundle");
        assertEquals(4, converter().convert(input, out).converted());
        for (String ext : new String[]{"pdf", "docx", "xlsx", "pptx"}) {
            assertTrue(Files.readString(out.resolve("sample." + ext + ".md")).contains("example content"), ext);
        }
        assertTrue(new OkfValidator().validate(out).isEmpty());
    }

    @Test void validatesCoreRulesWithoutRejectingUnknownTypesOrBrokenLinks() throws Exception {
        Path bundle = Files.createDirectory(temp.resolve("bundle"));
        Path concept = bundle.resolve("concept.md");
        Files.writeString(concept, "---\ntype: My Custom Type\nx-extra: yes\n---\n[Future](missing.md)\n");
        assertTrue(new OkfValidator().validate(bundle).isEmpty());
        Files.writeString(concept, "---\ntype: ''\n---\nBody");
        assertEquals(1, new OkfValidator().validate(bundle).size());
        Files.writeString(concept, "---\ntype: Reference\ntype: Duplicate\n---\nBody");
        assertEquals(1, new OkfValidator().validate(bundle).size());
    }

    @Test void validatesReservedFilesAndExitCodes() throws Exception {
        Path bundle = Files.createDirectory(temp.resolve("bundle"));
        Files.writeString(bundle.resolve("index.md"), "---\ntype: Reference\n---\n# Wrong\n");
        Files.writeString(bundle.resolve("log.md"), "# Log\n## 2026-99-99\n* Added a document\n");
        assertEquals(2, new OkfValidator().validate(bundle).size());
        var command = new CommandLine(new Main());
        command.setErr(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        assertEquals(1, command.execute("validate", bundle.toString()));
        assertEquals(2, command.execute("convert"));
        Files.writeString(bundle.resolve("index.md"), "# Concepts\n* [Future](future.md) - Planned\n");
        Files.writeString(bundle.resolve("log.md"), "# Log\n## 2026-09-12\n* Added a document\n## 2026-09-11\n* Created bundle\n");
        assertTrue(new OkfValidator().validate(bundle).isEmpty());
    }
}
