package io.github.okf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Immutable configuration. External UTF-8 properties override bundled defaults. */
public record ConverterOptions(long maxBytes, int maxChars, boolean ocrEnabled, String ocrMode,
        String ocrLanguage, String ocrExecutable, int ocrTimeoutSeconds, int ocrDpi,
        boolean tablesEnabled, boolean firstRowHeader, boolean splittingEnabled, int sectionTargetChars) {
    public ConverterOptions(long bytes, int chars, boolean ocr, String mode, String language, String executable,
            int timeout, int dpi, boolean tables, boolean header) {
        this(bytes, chars, ocr, mode, language, executable, timeout, dpi, tables, header, false, 6000);
    }
    public ConverterOptions {
        if (maxBytes <= 0 || maxChars <= 0) throw new IllegalArgumentException("Conversion limits must be positive");
        if (!Set.of("auto", "always").contains(ocrMode)) throw new IllegalArgumentException("ocr.mode must be auto or always");
        if (!ocrLanguage.matches("[A-Za-z0-9_]+(?:\\+[A-Za-z0-9_]+)*")) throw new IllegalArgumentException("Invalid ocr.language");
        if (ocrExecutable.isBlank()) throw new IllegalArgumentException("ocr.executable cannot be blank");
        if (ocrTimeoutSeconds < 1 || ocrTimeoutSeconds > 3600) throw new IllegalArgumentException("ocr.timeout.seconds must be 1..3600");
        if (ocrDpi < 72 || ocrDpi > 600) throw new IllegalArgumentException("ocr.pdf.dpi must be 72..600");
        if (sectionTargetChars < 500 || sectionTargetChars > 100000) throw new IllegalArgumentException("splitting.target.chars must be 500..100000");
    }

    public static ConverterOptions load(Path file) throws IOException {
        Properties properties = new Properties();
        try (var in = ConverterOptions.class.getResourceAsStream("/converter-defaults.properties")) {
            if (in == null) throw new IOException("Missing bundled converter defaults");
            properties.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (file != null) {
            Properties external = new Properties();
            try (var reader = Files.newBufferedReader(file)) { external.load(reader); }
            for (String key : external.stringPropertyNames()) {
                if (key.equals("index.max.entries")) continue; // Legacy setting: the root catalog is no longer paginated.
                if (!properties.containsKey(key)) throw new IllegalArgumentException("Unknown property: " + key);
                properties.setProperty(key, external.getProperty(key).strip());
            }
        }
        return new ConverterOptions(Long.parseLong(properties.getProperty("conversion.max.bytes")),
                Integer.parseInt(properties.getProperty("conversion.max.chars")), bool(properties, "ocr.enabled"),
                properties.getProperty("ocr.mode"), properties.getProperty("ocr.language"), properties.getProperty("ocr.executable"),
                Integer.parseInt(properties.getProperty("ocr.timeout.seconds")), Integer.parseInt(properties.getProperty("ocr.pdf.dpi")),
                bool(properties, "tables.enabled"), bool(properties, "tables.first.row.header"), bool(properties, "splitting.enabled"),
                Integer.parseInt(properties.getProperty("splitting.target.chars")));
    }

    private static boolean bool(Properties p, String key) {
        String value = p.getProperty(key);
        if (!Set.of("true", "false").contains(value)) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }

    public ConverterOptions withLimits(Long bytes, Integer chars) {
        return new ConverterOptions(bytes == null ? maxBytes : bytes, chars == null ? maxChars : chars,
                ocrEnabled, ocrMode, ocrLanguage, ocrExecutable, ocrTimeoutSeconds, ocrDpi, tablesEnabled, firstRowHeader,
                splittingEnabled, sectionTargetChars);
    }
}
