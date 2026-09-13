package io.github.okf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTest {
    @TempDir Path temp;
    private ConverterOptions options() throws Exception {
        Path config = temp.resolve("config.properties");
        Files.writeString(config, "ocr.enabled=false\nsplitting.enabled=true\n");
        return ConverterOptions.load(config);
    }
    private List<Path> content(Path root, String source) throws Exception {
        try (var files = Files.walk(root.resolve(source + ".sections"))) {
            return files.filter(p -> p.toString().endsWith(".md")).sorted().toList();
        }
    }

    @Test void refreshesChangedAddedDeletedSourcesAndKeepsUnchangedContent() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input")), output = temp.resolve("bundle");
        Files.writeString(input.resolve("a.md"), "# Alpha\n\nOriginal text.\n");
        Files.writeString(input.resolve("b.md"), "# Beta\n\nUntouched content.\n");
        Files.writeString(input.resolve("c.md"), "# Removed\n\nDelete this document.\n");
        var options = options(); new BundleConverter(options).convert(input, output);
        Path unchanged = content(output, "b.md").getFirst();
        String before = Files.readString(unchanged);
        var timestamp = Files.getLastModifiedTime(unchanged);
        var updater = new BundleUpdater(options);
        var noChange = updater.update(input, output);
        assertEquals(0, noChange.refreshed()); assertEquals(3, noChange.unchanged()); assertNull(noChange.backup());
        Files.writeString(input.resolve("a.md"), "# Alpha\n\nUpdated text.\n\n## New section\n\nNew evidence.\n");
        Files.delete(input.resolve("c.md"));
        Files.writeString(input.resolve("d.txt"), "Added document.");
        Files.writeString(output.resolve("personal.md"), "---\ntype: Note\n---\nMy separate note.\n");
        var changed = updater.update(input, output);
        assertEquals(2, changed.refreshed()); assertEquals(1, changed.unchanged()); assertEquals(1, changed.removed());
        assertEquals(2, content(output, "a.md").size());
        assertEquals(before, Files.readString(unchanged)); assertEquals(timestamp, Files.getLastModifiedTime(unchanged));
        assertFalse(Files.exists(output.resolve("c.md.sections")));
        assertTrue(Files.exists(output.resolve("personal.md")));
        assertTrue(Files.exists(changed.backup().resolve("c.md.sections")));
        assertTrue(new OkfValidator().validate(output).isEmpty());
        assertEquals(0, updater.update(input, output).refreshed());
    }

    @Test void failedConversionAndManualEditsDoNotOverwriteLiveBundle() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input")), output = temp.resolve("bundle");
        Path source = input.resolve("a.md"); Files.writeString(source, "# Alpha\n\nOriginal.");
        var options = options(); new BundleConverter(options).convert(input, output);
        Path concept = content(output, "a.md").getFirst(); String before = Files.readString(concept);
        String state = Files.readString(output.resolve(UpdateManifest.FILE));
        Files.writeString(source, "---\ntype: [broken\n---\nFailure");
        assertThrows(Exception.class, () -> new BundleUpdater(options).update(input, output));
        assertEquals(before, Files.readString(concept)); assertEquals(state, Files.readString(output.resolve(UpdateManifest.FILE)));
        Files.writeString(source, "# Alpha\n\nNew content.");
        Files.writeString(concept, before + "\nMy edit.\n");
        Exception error = assertThrows(Exception.class, () -> new BundleUpdater(options).update(input, output));
        assertTrue(error.getMessage().contains("edited manually"));
        assertTrue(Files.readString(concept).contains("My edit."));
    }

    @Test void bootstrapsOldBundleAndRepairsMissingOutput() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input")), output = temp.resolve("bundle");
        Files.writeString(input.resolve("a.md"), "# Alpha\n\nOriginal.");
        var options = options(); new BundleConverter(options).convert(input, output);
        Files.delete(output.resolve(UpdateManifest.FILE));
        var updater = new BundleUpdater(options);
        var bootstrap = updater.update(input, output);
        assertTrue(bootstrap.initialized()); assertEquals(1, bootstrap.refreshed());
        assertTrue(Files.exists(bootstrap.backup()));
        Path concept = content(output, "a.md").getFirst(); Files.delete(concept);
        assertEquals(1, updater.update(input, output).refreshed()); assertTrue(Files.exists(concept));
        Files.delete(output.resolve("index.md"));
        assertEquals(0, updater.update(input, output).refreshed());
        assertTrue(Files.exists(output.resolve("index.md")));
    }

    @Test void settingsChangeRebuildsAndEmptySourceDirectoryRemovesStaleConcepts() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input")), output = temp.resolve("bundle");
        Path source = input.resolve("a.md"); Files.writeString(source, "# Alpha\n\nOriginal.");
        var options = options(); new BundleConverter(options).convert(input, output);
        var changed = options.withLimits(10_000_000L, null);
        assertEquals(1, new BundleUpdater(changed).update(input, output).refreshed());
        Files.delete(source);
        assertEquals(1, new BundleUpdater(changed).update(input, output).removed());
        assertFalse(Files.exists(output.resolve("a.md.sections")));
        assertTrue(new OkfValidator().validate(output).isEmpty());
    }
}
