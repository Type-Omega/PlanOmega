package com.typeomega.planomega;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangParityTest {

    private static final Path LANG_DIR = Path.of("src", "main", "resources", "assets", "planomega", "lang");
    private static final List<String> LOCALES = List.of("en_us.json", "pt_br.json", "zh_cn.json");

    @Test
    void allLangFilesHaveExactKeyParityAndNonEmptyValues() throws IOException {
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        Set<String> unionKeys = new TreeSet<>();

        for (String file : LOCALES) {
            Path path = LANG_DIR.resolve(file);
            assertTrue(Files.exists(path), "Missing lang file: " + path);
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                assertTrue(root != null && root.isJsonObject(), file + " must be a JSON object");
                JsonObject obj = root.getAsJsonObject();
                Map<String, String> entries = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    String key = entry.getKey();
                    assertTrue(entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString(),
                            file + " key '" + key + "' must have a string value");
                    String value = entry.getValue().getAsString();
                    assertFalse(value.isBlank(), file + " key '" + key + "' must not be blank");
                    entries.put(key, value);
                }
                assertFalse(entries.isEmpty(), file + " must not be empty");
                loaded.put(file, entries);
                unionKeys.addAll(entries.keySet());
            }
        }

        for (String file : LOCALES) {
            Set<String> keys = new TreeSet<>(loaded.get(file).keySet());
            Set<String> missing = new TreeSet<>(unionKeys);
            missing.removeAll(keys);
            assertEquals(Set.of(), missing, file + " is missing keys compared to other locales");
        }
    }
}
