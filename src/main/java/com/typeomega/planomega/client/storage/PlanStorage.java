package com.typeomega.planomega.client.storage;

import com.typeomega.planomega.PlanOmega;
import com.typeomega.planomega.codec.DemoGraphFactory;
import com.typeomega.planomega.codec.PlanCodec;
import com.typeomega.planomega.model.PlanGraph;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local client-side persistence for saved plans and session autosave under {@code <gamedir>/planomega/plans/}.
 */
public final class PlanStorage {

    public static final String FILE_EXT = ".planomega.json";
    private static final String AUTOSAVE_FILE = "_autosave" + FILE_EXT;
    private static final int MAX_SLUG_LENGTH = 80;
    private static final Pattern RESERVED_WINDOWS_NAME = Pattern.compile("^(con|prn|aux|nul|com[1-9]|lpt[1-9])$");

    private PlanStorage() {
    }

    public static Path plansDir() {
        return FMLPaths.GAMEDIR.get().resolve("planomega").resolve("plans");
    }

    public static Path savePlan(PlanGraph graph) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Path dir = plansDir().normalize();
        Files.createDirectories(dir);
        String slug = sanitizeFileName(graph.name());
        Path target = dir.resolve(slug + FILE_EXT).normalize();
        if (!target.startsWith(dir)) {
            throw new IOException("Unsafe plan path: " + target);
        }
        Files.writeString(target, PlanCodec.toJson(graph), StandardCharsets.UTF_8);
        saveAutosave(graph);
        return target;
    }

    public static void saveAutosave(PlanGraph graph) {
        if (graph == null) {
            return;
        }
        try {
            Path dir = plansDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(AUTOSAVE_FILE), PlanCodec.toJson(graph), StandardCharsets.UTF_8);
        } catch (IOException e) {
            PlanOmega.LOGGER.warn("Failed to write Plan Omega autosave", e);
        }
    }

    public static PlanGraph loadPlan(Path file) throws IOException {
        if (file == null) {
            throw new IOException("Plan file path must not be null");
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        try {
            return PlanCodec.fromJson(content);
        } catch (RuntimeException e) {
            throw new IOException("Corrupted plan file '" + file.getFileName() + "': " + e.getMessage(), e);
        }
    }

    public static PlanGraph loadAutosaveOrDefault() {
        Path autosave = plansDir().resolve(AUTOSAVE_FILE);
        if (Files.isRegularFile(autosave)) {
            try {
                return loadPlan(autosave);
            } catch (Exception e) {
                PlanOmega.LOGGER.warn("Failed to load Plan Omega autosave, falling back to demo plan", e);
            }
        }
        return DemoGraphFactory.createSampleSulfuricAcidPlan();
    }

    public static List<SavedPlanEntry> listSavedPlans() {
        Path dir = plansDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<SavedPlanEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(FILE_EXT))
                    .filter(p -> !p.getFileName().toString().equals(AUTOSAVE_FILE))
                    .forEach(path -> {
                        try {
                            long modified = Files.getLastModifiedTime(path).toMillis();
                            String fileName = path.getFileName().toString();
                            String display = fileName.substring(0, fileName.length() - FILE_EXT.length());
                            entries.add(new SavedPlanEntry(path, display, modified));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            PlanOmega.LOGGER.warn("Failed to enumerate saved plans", e);
        }
        entries.sort(Comparator.comparingLong(SavedPlanEntry::lastModifiedMillis).reversed());
        return entries;
    }

    public static boolean deletePlan(Path path) throws IOException {
        if (path == null) {
            return false;
        }
        return Files.deleteIfExists(path);
    }

    public static String sanitizeFileName(String raw) {
        String base = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\-_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.length() > MAX_SLUG_LENGTH) {
            base = base.substring(0, MAX_SLUG_LENGTH).replaceAll("_+$", "");
        }
        if (base.isBlank() || base.equals("autosave") || base.equals("_autosave")) {
            return "plan_" + System.currentTimeMillis();
        }
        if (RESERVED_WINDOWS_NAME.matcher(base).matches()) {
            return "plan_" + base;
        }
        return base;
    }

    public record SavedPlanEntry(Path path, String name, long lastModifiedMillis) {
    }
}
