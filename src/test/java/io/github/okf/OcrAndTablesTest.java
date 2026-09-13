package io.github.okf;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class OcrAndTablesTest {
    @TempDir Path temp;

    private ConverterOptions settings(String overrides) throws Exception {
        Path config = temp.resolve("test.properties");
        Files.writeString(config, "splitting.enabled=false\n" + overrides);
        return ConverterOptions.load(config);
    }

    @Test void loadsOverridesAndRejectsTyposAndInvalidSettings() throws Exception {
        var options = settings("ocr.enabled=false\ntables.first.row.header=false\nocr.language=eng+deu\nocr.pdf.dpi=150\n");
        assertFalse(options.ocrEnabled()); assertFalse(options.firstRowHeader()); assertEquals(150, options.ocrDpi());
        assertEquals(300, options.withLimits(300L, null).maxBytes());
        for (String bad : new String[]{"ocr.enabld=true", "ocr.enabled=maybe", "ocr.mode=magic", "ocr.timeout.seconds=0", "ocr.pdf.dpi=1", "tables.enabled=no"}) {
            assertThrows(IllegalArgumentException.class, () -> settings(bad), bad);
        }
    }

    @Test void preservesNativeDocxAndSpreadsheetCells() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input"));
        try (var document = new XWPFDocument(); var out = Files.newOutputStream(input.resolve("table.docx"))) {
            document.createParagraph().createRun().setText("Before table");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Product"); table.getRow(0).getCell(1).setText("Count");
            table.getRow(1).getCell(0).setText("Widget"); table.getRow(1).getCell(1).setText("12");
            document.createParagraph().createRun().setText("After table"); document.write(out);
        }
        try (var workbook = new XSSFWorkbook(); var out = Files.newOutputStream(input.resolve("table.xlsx"))) {
            var sheet = workbook.createSheet("Inventory");
            var header = sheet.createRow(0); header.createCell(0).setCellValue("Product"); header.createCell(1).setCellValue("Count");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("Widget"); row.createCell(1).setCellValue(12);
            workbook.write(out);
        }
        Path output = temp.resolve("bundle");
        new BundleConverter(settings("ocr.enabled=false")).convert(input, output);
        for (String filename : new String[]{"table.docx.md", "table.xlsx.md"}) {
            String md = Files.readString(output.resolve(filename));
            assertTrue(md.contains("| Product | Count |"), md);
            assertTrue(md.contains("| Widget | 12 |"), md);
            assertTrue(md.contains("| --- | --- |"));
        }
        String doc = Files.readString(output.resolve("table.docx.md"));
        assertTrue(doc.indexOf("Before table") < doc.indexOf("| Widget"));
        assertTrue(doc.indexOf("After table") > doc.indexOf("| Widget"));
    }

    @Test void retainsMergedGridAndAllowsSyntheticHeadersAndDisabledTables() throws Exception {
        String html = "<h1>Results</h1><table><tr><td rowspan='2'>Group</td><td>A</td><td>B</td></tr>"
                + "<tr><td colspan='2'>Combined | value</td></tr></table>";
        String markdown = MarkdownTables.fromXhtml(html, false, 10000);
        assertTrue(markdown.contains("| Column 1 | Column 2 | Column 3 |"));
        assertTrue(markdown.contains("| Group | A | B |"));
        assertTrue(markdown.contains("|  | Combined &#124; value |  |"));
        Path source = temp.resolve("sample.csv"); Files.writeString(source, "A,B\n1,2\n");
        new BundleConverter(settings("tables.enabled=false\nocr.enabled=false")).convert(source, temp.resolve("plain"));
        assertTrue(Files.readString(temp.resolve("plain/sample.csv.md")).contains("```text\nA,B"));
        Path nativeHtml = temp.resolve("native.html"); Files.writeString(nativeHtml, html);
        new BundleConverter(settings("tables.first.row.header=false\nocr.enabled=false")).convert(nativeHtml, temp.resolve("html"));
        assertTrue(Files.readString(temp.resolve("html/native.html.md")).contains("| Column 1 | Column 2 | Column 3 |"));
    }

    @Test void cliLoadsExplicitPropertiesAndAppliesLimitOverride() throws Exception {
        Path source = temp.resolve("sample.csv"); Files.writeString(source, "A,B\n1,2\n");
        Path config = temp.resolve("cli.properties"); Files.writeString(config, "splitting.enabled=false\ntables.enabled=false\nconversion.max.bytes=1\n");
        var cli = new CommandLine(new Main());
        assertEquals(0, cli.execute("convert", source.toString(), "-o", temp.resolve("bundle").toString(), "--config", config.toString(), "--max-bytes", "1000"));
        assertTrue(Files.readString(temp.resolve("bundle/sample.csv.md")).contains("```text"));
    }

    @Test void reportsMissingOcrExecutableWithoutPublishing() throws Exception {
        Path source = temp.resolve("scan.png"); ImageIO.write(scan(), "png", source.toFile());
        var converter = new BundleConverter(settings("ocr.executable=" + temp.resolve("missing-tesseract").toString().replace('\\', '/') + "\n"));
        Exception error = assertThrows(Exception.class, () -> converter.convert(source, temp.resolve("failed")));
        assertTrue(error.getMessage().contains("scan.png"));
        assertFalse(Files.exists(temp.resolve("failed")));
    }

    @Test void autoUsesNativePdfTextWhileAlwaysRequiresOcr() throws Exception {
        Path source = temp.resolve("native.pdf");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(30, 500); content.showText("Readable native PDF text"); content.endText();
            }
            pdf.save(source.toFile());
        }
        String missing = "ocr.executable=" + temp.resolve("missing-tesseract").toString().replace('\\', '/') + "\n";
        new BundleConverter(settings(missing + "ocr.mode=auto\n")).convert(source, temp.resolve("auto"));
        assertTrue(Files.readString(temp.resolve("auto/native.pdf.md")).contains("Readable native PDF text"));
        var always = new BundleConverter(settings(missing + "ocr.mode=always\n"));
        assertThrows(Exception.class, () -> always.convert(source, temp.resolve("always")));
        assertFalse(Files.exists(temp.resolve("always")));
    }

    @Test void realTesseractRecognizesImageAndScannedPdf() throws Exception {
        boolean available;
        try {
            var probe = new ProcessBuilder("tesseract", "--version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            available = probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0;
            if (probe.isAlive()) probe.destroyForcibly();
        } catch (Exception e) { available = false; }
        Assumptions.assumeTrue(available, "Tesseract must be installed for OCR integration test");
        Path input = Files.createDirectory(temp.resolve("input"));
        BufferedImage raster = scan();
        ImageIO.write(raster, "png", input.resolve("scan.png").toFile());
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.drawImage(LosslessFactory.createFromImage(pdf, raster), 30, 500, 550, 180);
            }
            pdf.addPage(new PDPage());
            var sparse = new PDPage(); pdf.addPage(sparse);
            try (var content = new PDPageContentStream(pdf, sparse)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 6);
                content.setNonStrokingColor(Color.WHITE);
                content.newLineAtOffset(30, 30); content.showText("iv"); content.endText();
            }
            pdf.save(input.resolve("scan.pdf").toFile());
        }
        new BundleConverter(settings("ocr.pdf.dpi=150\n")).convert(input, temp.resolve("ocr"));
        for (String filename : new String[]{"scan.png.md", "scan.pdf.md"}) {
            String result = Files.readString(temp.resolve("ocr").resolve(filename));
            assertTrue(result.contains("INVOICE"), result);
            assertTrue(result.contains("12345"), result);
        }
        assertTrue(new OkfValidator().validate(temp.resolve("ocr")).isEmpty());
        String pdfText = Files.readString(temp.resolve("ocr/scan.pdf.md"));
        assertTrue(pdfText.contains("### Page 2\n\n_No readable text found on this page._"));
        assertTrue(pdfText.contains("### Page 3\n\n```text\niv\n```"), pdfText);
        Path blank = temp.resolve("blank.pdf");
        try (var pdf = new PDDocument()) { pdf.addPage(new PDPage()); pdf.save(blank.toFile()); }
        Exception failure = assertThrows(Exception.class, () -> new BundleConverter(settings("ocr.pdf.dpi=150\n"))
                .convert(blank, temp.resolve("blank-output")));
        assertTrue(failure.getMessage().contains("PDF contains no readable text after OCR"));
        assertFalse(Files.exists(temp.resolve("blank-output")));
    }

    private static BufferedImage scan() {
        var image = new BufferedImage(1600, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, 1600, 500);
            g.setColor(Color.BLACK); g.setFont(new Font("SansSerif", Font.BOLD, 80));
            g.drawString("INVOICE 12345", 90, 180);
            g.setFont(new Font("SansSerif", Font.PLAIN, 60)); g.drawString("Total amount 250 dollars", 90, 300);
        } finally { g.dispose(); }
        return image;
    }
}
