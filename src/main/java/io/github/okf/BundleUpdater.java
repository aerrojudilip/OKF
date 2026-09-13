package io.github.okf;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Updates managed outputs in a staged copy and retains the previous bundle as a backup. */
public final class BundleUpdater {
    public record Result(int refreshed, int unchanged, int removed, boolean initialized, Path backup) {}
    private final ConverterOptions options;
    public BundleUpdater(ConverterOptions options) { this.options = options; }

    public Result update(Path input, Path output) throws Exception {
        input = input.toAbsolutePath().normalize(); output = output.toAbsolutePath().normalize();
        if (!Files.exists(input) || Files.isSymbolicLink(input)) throw new IOException("Source must exist and not be a symbolic link");
        if (!Files.isDirectory(output, NOFOLLOW_LINKS)) throw new IOException("Existing bundle required; use convert for the first run");
        Path root = Files.isDirectory(input) ? input : input.getParent();
        if (output.toRealPath().startsWith(input.toRealPath()) || input.toRealPath().startsWith(output.toRealPath()))
            throw new IOException("Source and output must be separate trees for updates");
        Path lockPath = output.resolveSibling("." + output.getFileName() + ".update.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another update is running");
            return updateLocked(input, root, output);
        }
    }

    private Result updateLocked(Path input, Path root, Path output) throws Exception {
        Map<String, String> before = snapshot(output);
        var previous = UpdateManifest.load(output);
        if (previous != null && !input.toUri().toString().equals(previous.values.getProperty("input")))
            throw new IOException("Source path differs from this bundle's tracked input");
        List<Path> sources;
        var extractor = new DocumentExtractor(options);
        if (Files.isDirectory(input)) {
            try (var walk = Files.walk(input)) { sources = walk.filter(p -> Files.isRegularFile(p, NOFOLLOW_LINKS) && extractor.supports(p)).sorted().toList(); }
        } else sources = extractor.supports(input) ? List.of(input) : List.of();
        Map<String, String> sourceStamps = new TreeMap<>();
        for (Path source : sources) sourceStamps.put(UpdateManifest.relative(root, source), UpdateManifest.sourceStamp(source));
        Set<String> changed = new TreeSet<>(), removed = new TreeSet<>();
        boolean rebuild = previous == null || !UpdateManifest.fingerprint(options).equals(previous.values.getProperty("pipeline"));
        if (previous != null) {
            removed.addAll(previous.sources()); removed.removeAll(sourceStamps.keySet());
            for (String managed : previous.outputs()) {
                Path path = inside(output, managed);
                if (Files.exists(path) && !UpdateManifest.hash(path).equals(previous.values.getProperty("output." + UpdateManifest.encode(managed))))
                    throw new IOException("Generated file was edited manually; preserve/revert that edit before updating: " + managed);
            }
        }
        for (var source : sourceStamps.entrySet()) {
            if (rebuild || !source.getValue().equals(previous.values.getProperty("source." + UpdateManifest.encode(source.getKey())))
                    || previous.outputsFor(source.getKey()).stream().anyMatch(p -> !Files.exists(output.resolve(p)))) changed.add(source.getKey());
        }
        boolean missingCatalog = !Files.exists(output.resolve("index.md")) || (options.splittingEnabled() && !Files.exists(output.resolve("retrieval-guide.md")));
        if (changed.isEmpty() && removed.isEmpty() && !rebuild && !missingCatalog) return new Result(0, sources.size(), 0, false, null);

        Path stage = Files.createTempDirectory(output.getParent(), ".okf-update-");
        try {
            copy(output, stage);
            if (previous == null) {
                // Older versions did not track hashes. Recognize only our emitted source concepts;
                // the entire previous bundle is retained at publication for recovery.
                for (String relative : before.keySet()) {
                    if (!relative.endsWith(".md") || relative.equals("index.md") || relative.equals("retrieval-guide.md")) continue;
                    var doc = Frontmatter.parse(Files.readString(inside(stage, relative)));
                    Object generated = doc.metadata().get("generated");
                    if (!(generated instanceof Map<?, ?> by) || !"okf-converter/1.0.0".equals(by.get("by"))) continue;
                    Object raw = doc.metadata().get("sources");
                    if (!(raw instanceof List<?> list) || list.isEmpty() || !(list.getFirst() instanceof Map<?, ?> src)) continue;
                    String resource = Objects.toString(src.get("resource"), "");
                    if (!resource.startsWith(root.toUri().toString())) throw new IOException("Legacy bundle includes a different source root: " + resource);
                    Files.delete(inside(stage, relative));
                }
            } else {
                var refresh = new TreeSet<>(changed); refresh.addAll(removed);
                for (String source : refresh) for (String file : previous.outputsFor(source)) Files.deleteIfExists(inside(stage, file));
            }
            pruneEmpty(stage);
            Files.deleteIfExists(stage.resolve("index.md"));
            Files.deleteIfExists(stage.resolve("retrieval-guide.md"));
            Files.deleteIfExists(stage.resolve(UpdateManifest.FILE));
            var converter = new BundleConverter(options);
            for (Path source : sources) if (changed.contains(UpdateManifest.relative(root, source))) converter.writeSource(source, root, stage);
            // Keep an explanatory concept when the tracked source directory becomes empty.
            if (options.splittingEnabled() || sources.isEmpty()) BundleConverter.writeRetrievalGuide(stage);
            BundleConverter.createIndex(stage);
            var errors = new OkfValidator().validate(stage);
            if (!errors.isEmpty()) throw new IOException("Updated bundle failed validation: " + errors);
            for (Path source : sources) if (!sourceStamps.get(UpdateManifest.relative(root, source)).equals(UpdateManifest.sourceStamp(source)))
                throw new IOException("Source changed while converting; retry: " + source);
            UpdateManifest.capture(input, root, sources, stage, options).save(stage);
            if (!before.equals(snapshot(output))) throw new IOException("Bundle changed during update; retry without concurrent edits");
            Path backup = output.resolveSibling(output.getFileName() + ".backup-" + UUID.randomUUID());
            Files.move(output, backup);
            try { Files.move(stage, output); }
            catch (Exception failure) {
                try { Files.move(backup, output); } catch (Exception restore) { failure.addSuppressed(restore); }
                throw failure;
            }
            return new Result(changed.size(), sources.size() - changed.size(), removed.size(), previous == null, backup);
        } finally { if (Files.exists(stage)) deleteTree(stage); }
    }

    private static Path inside(Path root, String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("Invalid tracked output path: " + relative);
        return path;
    }
    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> files = new TreeMap<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted().toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symlinks in bundles are not supported for updates: " + path);
                if (Files.isRegularFile(path)) files.put(UpdateManifest.relative(root, path), UpdateManifest.hash(path));
            }
        }
        return files;
    }
    private static void copy(Path source, Path target) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path file : walk.sorted().toList()) {
                Path destination = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
    private static void pruneEmpty(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path dir : walk.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
                if (dir.equals(root)) continue;
                try (var entries = Files.list(dir)) { if (entries.findAny().isPresent()) continue; }
                Files.delete(dir);
            }
        }
    }
    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) { for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
}
