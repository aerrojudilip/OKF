package io.github.okf;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Core structure checks, not a semantic verifier or an attestation executor. */
public final class OkfValidator {
    public List<String> validate(Path bundle) throws IOException {
        if (!Files.isDirectory(bundle)) throw new IOException("Bundle must be a directory: " + bundle);
        List<String> errors = new ArrayList<>();
        try (var walk = Files.walk(bundle)) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                try {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Expected a regular Markdown file");
                    validateDocument(file, bundle, Frontmatter.parse(Files.readString(file)));
                } catch (Exception e) { errors.add(bundle.relativize(file) + ": " + e.getMessage()); }
            }
        }
        return List.copyOf(errors);
    }

    private static void validateDocument(Path file, Path bundle, Frontmatter.Document doc) {
        String name = file.getFileName().toString();
        if (name.equals("index.md")) {
            if (doc.present() && (!file.getParent().equals(bundle) || !doc.metadata().keySet().equals(Set.of("okf_version"))))
                throw new IllegalArgumentException("Only a root index may have frontmatter, containing okf_version");
            if (doc.present() && (!(doc.metadata().get("okf_version") instanceof String version) || version.isBlank()))
                throw new IllegalArgumentException("okf_version must be a non-empty string");
            boolean heading = false, entry = false;
            for (String line : doc.body().lines().toList()) {
                if (line.isBlank()) continue;
                if (line.matches("#{1,6}\\s+.+")) heading = true;
                else if (heading && line.matches("\\s*[-*+] \\[.+]\\(.+\\)(?:.*)")) entry = true;
                else throw new IllegalArgumentException("Index must contain headings and Markdown link list entries");
            }
            if (!heading || !entry) throw new IllegalArgumentException("Index needs a heading and at least one linked entry");
        } else if (name.equals("log.md")) {
            if (doc.present()) throw new IllegalArgumentException("Log must not have frontmatter");
            LocalDate previous = null;
            boolean entry = false;
            for (String line : doc.body().lines().toList()) {
                if (line.isBlank() || line.startsWith("# ")) continue;
                if (line.startsWith("## ")) {
                    String dateText = line.substring(3).strip();
                    if (!dateText.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Log date must use YYYY-MM-DD");
                    LocalDate date = LocalDate.parse(dateText);
                    if (previous != null && date.isAfter(previous)) throw new IllegalArgumentException("Log dates must be newest first");
                    previous = date;
                } else if (previous != null && line.matches("[-*+] .+")) entry = true;
                else throw new IllegalArgumentException("Log needs date headings and flat list entries");
            }
            if (previous == null || !entry) throw new IllegalArgumentException("Log needs at least one dated entry");
        } else if (!doc.present() || !(doc.metadata().get("type") instanceof String type) || type.isBlank()) {
            throw new IllegalArgumentException("Concept requires YAML frontmatter with a non-empty string type");
        }
    }
}
