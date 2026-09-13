package io.github.okf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Source-level attribution using the OKF v0.2 sources[].id / Markdown footnote convention. */
final class SourceAttribution {
    static String id(String resource, String body) throws Exception {
        String prefix = "okf-source-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(resource.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        String id = prefix;
        for (int suffix = 2; body.contains("[^" + id + "]"); suffix++) id = prefix + "-" + suffix;
        return id;
    }

    static String reference(Map<String, Object> metadata) {
        Map<?, ?> source = source(metadata);
        return "[^" + source.get("id") + "]";
    }

    static String definition(Map<String, Object> metadata, int page) {
        Map<?, ?> source = source(metadata);
        String uri = source.get("resource").toString().replace("(", "%28").replace(")", "%29");
        if (page > 0) uri += "#page=" + page;
        return reference(metadata) + ": [" + DocumentExtractor.escapeCell(source.get("title").toString()) + "](" + uri + ")\n";
    }

    private static Map<?, ?> source(Map<String, Object> metadata) {
        return (Map<?, ?>) ((List<?>) metadata.get("sources")).getFirst();
    }
}
