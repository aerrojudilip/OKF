package io.github.okf;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Internal conversion state; content hashes are also used to protect manually edited outputs. */
final class UpdateManifest {
    static final String FILE = ".okf-converter.properties";
    final Properties values = new Properties();
    static String encode(String name) { return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    static String decode(String name) { return new String(Base64.getUrlDecoder().decode(name), java.nio.charset.StandardCharsets.UTF_8); }
    static String relative(Path root, Path file) { return root.relativize(file).toString().replace('\\', '/'); }

    static String hash(Path file) throws Exception {
        try (var in = Files.newInputStream(file)) { return hash(in); }
    }
    private static String hash(InputStream in) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = new byte[65536]; int n;
        while ((n = in.read(bytes)) >= 0) digest.update(bytes, 0, n);
        return HexFormat.of().formatHex(digest.digest());
    }
    static String fingerprint(ConverterOptions options) throws Exception {
        StringBuilder signature = new StringBuilder("manifest-v1\n" + options + "\n");
        for (Class<?> type : List.of(BundleConverter.class, DocumentExtractor.class, OcrExtractor.class, PdfSections.class,
                SectionSplitter.class, SectionBundle.class, MarkdownTables.class, SourceAttribution.class, Frontmatter.class)) {
            try (var in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                if (in == null) throw new IOException("Missing converter class: " + type);
                signature.append(hash(in));
            }
        }
        return hash(new ByteArrayInputStream(signature.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    static String sourceStamp(Path path) throws Exception { return hash(path) + ":" + Files.getLastModifiedTime(path).toInstant(); }

    static UpdateManifest capture(Path input, Path sourceRoot, List<Path> sources, Path bundle, ConverterOptions options) throws Exception {
        var manifest = new UpdateManifest();
        manifest.values.setProperty("format", "1");
        manifest.values.setProperty("input", input.toAbsolutePath().normalize().toUri().toString());
        manifest.values.setProperty("pipeline", fingerprint(options));
        for (Path source : sources) {
            String relative = relative(sourceRoot, source), key = encode(relative);
            manifest.values.setProperty("source." + key, sourceStamp(source));
            Path generated = bundle.resolve(relative + (options.splittingEnabled() ? ".sections" : ".md"));
            List<Path> outputs;
            if (Files.isDirectory(generated)) {
                try (var walk = Files.walk(generated)) { outputs = walk.filter(Files::isRegularFile).sorted().toList(); }
            } else outputs = List.of(generated);
            for (Path output : outputs) {
                if (!output.toString().endsWith(".md")) continue;
                var metadata = Frontmatter.parse(Files.readString(output)).metadata();
                Object provenance = metadata.get("sources");
                if (!(provenance instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> origin)
                        || !source.toUri().toASCIIString().equals(origin.get("resource"))) continue;
                String outputKey = encode(relative(bundle, output));
                manifest.values.setProperty("output." + outputKey, hash(output));
                manifest.values.setProperty("owner." + outputKey, key);
            }
        }
        for (String name : List.of("index.md", "retrieval-guide.md")) {
            if (Files.exists(bundle.resolve(name))) manifest.values.setProperty("output." + encode(name), hash(bundle.resolve(name)));
        }
        return manifest;
    }
    static UpdateManifest load(Path bundle) throws IOException {
        Path state = bundle.resolve(FILE);
        if (!Files.exists(state)) return null;
        var manifest = new UpdateManifest();
        try (var reader = Files.newBufferedReader(state)) { manifest.values.load(reader); }
        if (!"1".equals(manifest.values.getProperty("format"))) throw new IOException("Unsupported update manifest format");
        return manifest;
    }
    void save(Path bundle) throws IOException {
        try (var writer = Files.newBufferedWriter(bundle.resolve(FILE))) { values.store(writer, "Generated conversion state. Do not edit."); }
    }
    Set<String> sources() { return keys("source."); }
    Set<String> outputs() { return keys("output."); }
    private Set<String> keys(String prefix) {
        Set<String> result = new TreeSet<>();
        for (String key : values.stringPropertyNames()) if (key.startsWith(prefix)) result.add(decode(key.substring(prefix.length())));
        return result;
    }
    List<String> outputsFor(String source) {
        return outputs().stream().filter(out -> encode(source).equals(values.getProperty("owner." + encode(out)))).toList();
    }
}
