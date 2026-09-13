package io.github.okf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.ToXMLContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;

final class DocumentExtractor {
    static final Set<String> SUPPORTED = Set.of("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
            "html", "htm", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp");
    record Content(String body, Map<String, Object> sourceMetadata) {}
    private final long maxBytes;
    private final int maxChars;
    private final ConverterOptions options;
    static final Set<String> IMAGES = Set.of("png", "jpg", "jpeg", "tif", "tiff", "bmp");
    DocumentExtractor(long maxBytes, int maxChars) {
        this(new ConverterOptions(maxBytes, maxChars, false, "auto", "eng", "tesseract", 120, 300, true, true));
    }
    DocumentExtractor(ConverterOptions options) {
        this.options = options; this.maxBytes = options.maxBytes(); this.maxChars = options.maxChars();
    }
    boolean supports(Path file) { return SUPPORTED.contains(extension(file)) || (options.ocrEnabled() && IMAGES.contains(extension(file))); }

    static String extension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    Content extract(Path path) throws Exception {
        if (Files.size(path) > maxBytes) throw new IOException("Input exceeds --max-bytes: " + path);
        String ext = extension(path);
        if (!supports(path)) throw new IOException("Unsupported format (image OCR may be disabled): " + path);
        String body;
        Map<String, Object> sourceMetadata = Map.of();
        if (options.ocrEnabled() && (ext.equals("pdf") || IMAGES.contains(ext))) {
            var ocrExtractor = new OcrExtractor(options);
            body = ext.equals("pdf") ? ocrExtractor.pdf(path) : fence(ocrExtractor.image(path), "text");
        } else if (Set.of("txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml").contains(ext)) {
            String text = Files.readString(path);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            if (text.length() > maxChars) throw new IOException("Input exceeds --max-chars: " + path);
            if (ext.equals("md") || ext.equals("markdown")) {
                var parsed = Frontmatter.parse(text);
                body = parsed.body();
                sourceMetadata = parsed.metadata();
            } else if (ext.equals("csv") || ext.equals("tsv")) {
                body = options.tablesEnabled() ? table(text, ext.equals("tsv") ? '\t' : ',') : fence(text, "text");
            } else body = fence(text, ext.equals("txt") ? "text" : ext);
        } else {
            var metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, path.getFileName().toString());
            var xml = new ToXMLContentHandler();
            org.xml.sax.ContentHandler handler = options.tablesEnabled()
                    ? new WriteOutContentHandler(xml, maxChars) : new BodyContentHandler(maxChars);
            var context = new ParseContext();
            var pdf = new PDFParserConfig();
            pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
            context.set(PDFParserConfig.class, pdf);
            var ocr = new TesseractOCRConfig();
            ocr.setSkipOcr(true);
            context.set(TesseractOCRConfig.class, ocr);
            context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                public boolean shouldParseEmbedded(Metadata m) { return false; }
                public void parseEmbedded(java.io.InputStream in, org.xml.sax.ContentHandler h, Metadata m, boolean html) {}
            });
            try (var in = Files.newInputStream(path)) {
                new AutoDetectParser().parse(in, handler, metadata, context);
            }
            String text = options.tablesEnabled() ? MarkdownTables.fromXhtml(xml.toString(), options.firstRowHeader(), maxChars) : handler.toString().strip();
            if (text.isBlank()) throw new IOException("No readable text extracted (scanned/encrypted/empty document?): " + path);
            body = "## Extracted content\n\n" + (options.tablesEnabled() ? text : fence(text, "text"));
        }
        if (body.length() > maxChars) throw new IOException("Rendered content exceeds --max-chars: " + path);
        return new Content(body, sourceMetadata);
    }

    static String fence(String text, String language) {
        int max = 0, run = 0;
        for (char ch : text.toCharArray()) { run = ch == '`' ? run + 1 : 0; max = Math.max(max, run); }
        String marker = "`".repeat(Math.max(3, max + 1));
        return marker + language + "\n" + text.stripTrailing() + "\n" + marker + "\n";
    }

    private String table(String text, char delimiter) throws IOException {
        try (var parser = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get().parse(new java.io.StringReader(text))) {
            var records = parser.getRecords();
            return MarkdownTables.table(records.stream().map(r -> r.toList()).toList(), options.firstRowHeader(), maxChars);
        }
    }

    static String escapeCell(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "\\\\").replace("|", "&#124;").replace("`", "&#96;")
                .replace("*", "\\*").replace("_", "\\_").replace("[", "\\[").replace("]", "\\]")
                .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>");
    }
}
