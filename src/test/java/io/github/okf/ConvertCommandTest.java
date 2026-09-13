package io.github.okf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConvertCommandTest {
    @TempDir Path temp;

    @Test void repeatedConvertCreatesThenUpdatesAndPreservesNoOp() throws Exception {
        Path input = Files.createDirectory(temp.resolve("input")), output = temp.resolve("bundle"), config = temp.resolve("config.properties");
        Files.writeString(config, "ocr.enabled=false\nsplitting.enabled=false\nconversion.max.bytes=1\n");
        Files.writeString(input.resolve("a.txt"), "First version");
        String[] args = {"convert", input.toString(), "-o", output.toString(), "--config", config.toString(), "--max-bytes", "10000"};
        var log = new StringWriter();
        var cli = Main.commandLine().setOut(new PrintWriter(log, true));
        assertEquals(0, cli.execute(args));
        Path result = output.resolve("a.txt.md");
        String original = Files.readString(result);
        var modified = Files.getLastModifiedTime(result);
        assertEquals(0, cli.execute(args));
        assertTrue(log.toString().contains("Refreshed 0 source(s), unchanged 1"));
        assertEquals(original, Files.readString(result));
        assertEquals(modified, Files.getLastModifiedTime(result));
        Files.writeString(input.resolve("a.txt"), "Second version");
        assertEquals(0, cli.execute(args));
        assertTrue(Files.readString(result).contains("Second version"));
        assertTrue(log.toString().contains("Previous bundle retained"));
        assertTrue(new OkfValidator().validate(output).isEmpty());
    }

    @Test void reportsInvalidDestinationWithoutStackTraceOrOverwriting() throws Exception {
        Path input = temp.resolve("source.txt"), output = temp.resolve("bundle"), config = temp.resolve("config.properties");
        Files.writeString(input, "Content"); Files.writeString(output, "Keep this file"); Files.writeString(config, "ocr.enabled=false\n");
        var error = new StringWriter();
        var cli = Main.commandLine().setErr(new PrintWriter(error, true));
        assertEquals(1, cli.execute("convert", input.toString(), "-o", output.toString(), "--config", config.toString()));
        assertTrue(error.toString().startsWith("Error: "));
        assertFalse(error.toString().contains("at io.github"));
        assertEquals("Keep this file", Files.readString(output));
    }
}
