package io.github.okf;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "okf", mixinStandardHelpOptions = true, version = "okf-converter 1.0.0",
        description = "Convert local documents into an Open Knowledge Format v0.2 bundle.",
        subcommands = {Main.Convert.class, Main.Update.class, Main.Validate.class, Main.Search.class})
public final class Main {
    public static void main(String[] args) { System.exit(commandLine().execute(args)); }

    static CommandLine commandLine() {
        return new CommandLine(new Main()).setExecutionExceptionHandler((error, command, parsed) -> {
            command.getErr().println("Error: " + error.getMessage());
            return 1;
        });
    }

    private static void reportUpdate(CommandLine command, BundleUpdater.Result result) {
        command.getOut().printf("Refreshed %d source(s), unchanged %d, removed %d.%n", result.refreshed(), result.unchanged(), result.removed());
        if (result.initialized()) command.getOut().println("Initialized change tracking for this older bundle.");
        if (result.backup() != null) command.getOut().println("Previous bundle retained at " + result.backup());
    }

    @Command(name = "update", mixinStandardHelpOptions = true, description = "Refresh changed sources in an existing bundle and rebuild its single catalog.")
    static final class Update implements Callable<Integer> {
        @Parameters(index = "0") Path input;
        @Option(names = {"-o", "--output"}, required = true) Path output;
        @Option(names = "--config") Path config;
        @Spec Model.CommandSpec spec;
        public Integer call() throws Exception {
            Path settings = config != null ? config : (java.nio.file.Files.exists(Path.of("converter.properties")) ? Path.of("converter.properties") : null);
            var result = new BundleUpdater(ConverterOptions.load(settings)).update(input, output);
            reportUpdate(spec.commandLine(), result);
            return 0;
        }
    }

    @Command(name = "convert", mixinStandardHelpOptions = true,
            description = "Create a bundle, or incrementally update it when the output already exists.")
    static final class Convert implements Callable<Integer> {
        @Parameters(index = "0", description = "Source file or folder") Path input;
        @Option(names = {"-o", "--output"}, required = true) Path output;
        @Option(names = "--config", description = "UTF-8 properties file (default: ./converter.properties if present)") Path config;
        @Option(names = "--max-bytes", description = "Override maximum input bytes per file") Long maxBytes;
        @Option(names = "--max-chars", description = "Override maximum extracted characters per file") Integer maxChars;
        @Spec Model.CommandSpec spec;
        public Integer call() throws Exception {
            Path settings = config != null ? config : (java.nio.file.Files.exists(Path.of("converter.properties")) ? Path.of("converter.properties") : null);
            var options = ConverterOptions.load(settings).withLimits(maxBytes, maxChars);
            if (java.nio.file.Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                reportUpdate(spec.commandLine(), new BundleUpdater(options).update(input, output));
                return 0;
            }
            var result = new BundleConverter(options).convert(input, output);
            result.skipped().forEach(p -> spec.commandLine().getErr().println("Skipped unsupported file or symbolic link: " + p));
            spec.commandLine().getOut().printf("Converted %d file(s) into %d content section(s) at %s%n", result.converted(), result.sections(), output.toAbsolutePath());
            return 0;
        }
    }

    @Command(name = "search", mixinStandardHelpOptions = true, description = "Rank local content sections by keywords; no vector database needed.")
    static final class Search implements Callable<Integer> {
        @Parameters(index = "0") Path bundle;
        @Parameters(index = "1", description = "Quoted question or keywords") String query;
        @Option(names = "--limit", defaultValue = "8") int limit;
        @Spec Model.CommandSpec spec;
        public Integer call() throws Exception {
            var hits = new KeywordSearch().search(bundle, query, limit);
            for (var hit : hits) spec.commandLine().getOut().printf("%s%n  %s%n  %s%n%n", hit.path(), hit.title(), hit.excerpt());
            if (hits.isEmpty()) spec.commandLine().getOut().println("No matching content sections. Try different keywords.");
            return 0;
        }
    }

    @Command(name = "validate", mixinStandardHelpOptions = true,
            description = "Check core OKF structure (frontmatter, type, index and log conventions).")
    static final class Validate implements Callable<Integer> {
        @Parameters(index = "0") Path bundle;
        @Spec Model.CommandSpec spec;
        public Integer call() throws Exception {
            var errors = new OkfValidator().validate(bundle);
            errors.forEach(spec.commandLine().getErr()::println);
            if (errors.isEmpty()) spec.commandLine().getOut().println("Core OKF structure is valid.");
            return errors.isEmpty() ? 0 : 1;
        }
    }
}
