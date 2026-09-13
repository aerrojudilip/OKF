package io.github.okf;

import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class Frontmatter {
    record Document(Map<String, Object> metadata, String body, boolean present) {}

    static Document parse(String text) {
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (!text.startsWith("---\n")) return new Document(Map.of(), text, false);
        int end = text.indexOf("\n---\n", 3);
        if (end < 0 && text.endsWith("\n---")) end = text.length() - 4;
        if (end < 0) throw new IllegalArgumentException("Unclosed YAML frontmatter");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(5_000_000);
        Object loaded = new Yaml(new SafeConstructor(options)).load(text.substring(4, end));
        if (!(loaded instanceof Map<?, ?> map)) throw new IllegalArgumentException("Frontmatter must be a YAML mapping");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (!(k instanceof String key)) throw new IllegalArgumentException("Metadata keys must be strings");
            result.put(key, v);
        });
        return new Document(result, text.substring(Math.min(text.length(), end + 5)), true);
    }

    static String write(Map<String, Object> metadata, String body) {
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setSplitLines(false);
        return "---\n" + new Yaml(options).dump(metadata) + "---\n\n" + body.strip() + "\n";
    }
}
