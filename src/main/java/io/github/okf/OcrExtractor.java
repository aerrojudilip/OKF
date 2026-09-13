package io.github.okf;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/** Page-level local OCR with bounded runtime and output. */
final class OcrExtractor {
    record Page(int number, String text) {}
    private final ConverterOptions options;
    OcrExtractor(ConverterOptions options) { this.options = options; }

    String pdf(Path source) throws Exception {
        StringBuilder output = new StringBuilder();
        for (Page page : pages(source)) {
            output.append("### Page ").append(page.number()).append("\n\n")
                    .append(!page.text().isBlank() ? DocumentExtractor.fence(page.text(), "text") : "_No readable text found on this page._\n").append('\n');
            if (output.length() > options.maxChars()) throw new IOException("PDF exceeds conversion.max.chars");
        }
        return output.toString();
    }

    java.util.List<Page> pages(Path source) throws Exception {
        try (var document = Loader.loadPDF(source.toFile())) {
            if (document.isEncrypted()) throw new IOException("Encrypted PDF is not supported");
            if (document.getNumberOfPages() == 0) throw new IOException("PDF has no pages");
            var renderer = new PDFRenderer(document);
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            var pages = new java.util.ArrayList<Page>();
            long totalChars = 0;
            boolean hasReadableText = false;
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                stripper.setStartPage(page + 1); stripper.setEndPage(page + 1);
                String text = stripper.getText(document);
                if (options.ocrEnabled() && (options.ocrMode().equals("always") || text.replaceAll("\\s", "").length() < 10)) {
                    // Refuse images whose uncompressed raster would be excessive before rendering.
                    var box = document.getPage(page).getCropBox();
                    double pixels = box.getWidth() * options.ocrDpi() / 72.0 * box.getHeight() * options.ocrDpi() / 72.0;
                    if (!Double.isFinite(pixels) || pixels > 40_000_000) throw new IOException("PDF OCR page exceeds 40 million pixels; lower ocr.pdf.dpi");
                    Path image = Files.createTempFile("okf-page-", ".png");
                    try {
                        var raster = renderer.renderImageWithDPI(page, options.ocrDpi());
                        try { ImageIO.write(raster, "png", image.toFile()); } finally { raster.flush(); }
                        String recognized;
                        try { recognized = recognize(image); }
                        catch (IOException e) { throw new IOException("PDF page " + (page + 1) + ": " + e.getMessage(), e); }
                        // A successful OCR process can legitimately return nothing for a blank,
                        // decorative or sparse page. Keep the existing text layer in that case.
                        if (!recognized.isBlank()) text = recognized;
                    } finally { Files.deleteIfExists(image); }
                }
                boolean readable = !text.isBlank();
                hasReadableText |= readable;
                pages.add(new Page(page + 1, text));
                totalChars += text.length();
                if (totalChars > options.maxChars()) throw new IOException("PDF exceeds conversion.max.chars");
            }
            if (!hasReadableText) throw new IOException("PDF contains no readable text after OCR");
            return java.util.List.copyOf(pages);
        }
    }

    String image(Path source) throws Exception {
        String content = recognize(source);
        if (content.isBlank()) throw new IOException("OCR found no readable text");
        return content;
    }

    private String recognize(Path source) throws Exception {
        Path work = Files.createTempDirectory("okf-ocr-");
        Process process = null;
        try {
            Path result = work.resolve("result");
            Path errors = work.resolve("stderr.txt");
            process = new ProcessBuilder(options.ocrExecutable(), source.toAbsolutePath().toString(), result.toString(),
                    "-l", options.ocrLanguage(), "-c", "preserve_interword_spaces=1")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(errors.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(options.ocrTimeoutSeconds());
            Path text = work.resolve("result.txt");
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() >= deadline) throw new IOException("OCR timed out after " + options.ocrTimeoutSeconds() + " seconds");
                if (Files.size(errors) > 1_000_000 || (Files.exists(text) && Files.size(text) > options.maxChars() * 4L))
                    throw new IOException("OCR output exceeds configured limits");
            }
            if (process.exitValue() != 0) {
                String details;
                try (var in = Files.newInputStream(errors)) { details = new String(in.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8); }
                throw new IOException("Tesseract failed (check executable/language data): " + details.strip());
            }
            if (!Files.exists(text) || Files.size(text) > options.maxChars() * 4L) throw new IOException("OCR output missing or too large");
            String content = Files.readString(text);
            if (content.length() > options.maxChars()) throw new IOException("OCR exceeds conversion.max.chars");
            return content;
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            try (var files = Files.walk(work)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
