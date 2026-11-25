package com.typeomega.planomega.export;

import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VirtualRecipeNode;
import com.typeomega.planomega.recipe.RecipeEntry;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Generates {@code current vs proposed} diffs, KubeJS recipe scripts, and TypeOmega Java
 * {@code GTRecipeBuilder} skeletons from {@link VirtualRecipeNode}s in the sandbox.
 */
public final class SandboxDiffExporter {

    public static final String DEFAULT_KUBEJS_SCRIPT_RELATIVE_PATH = "kubejs/server_scripts/planomega_sandbox.js";
    public static final String KUBEJS_RELOAD_COMMAND = "/kubejs reload server_scripts";

    public record KubeJsSaveResult(
            boolean saved,
            boolean requiresOverwriteConfirmation,
            Path scriptPath,
            @Nullable Path backupPath,
            String reloadCommand,
            String message
    ) {
    }

    private SandboxDiffExporter() {
    }

    public static String getKubeJsReloadCommand() {
        return KUBEJS_RELOAD_COMMAND;
    }

    public static Path resolveDefaultKubeJsScriptPath(Path gameDir) {
        return gameDir.resolve(DEFAULT_KUBEJS_SCRIPT_RELATIVE_PATH);
    }

    public static boolean kubeJsScriptExists(Path gameDir) {
        return Files.exists(resolveDefaultKubeJsScriptPath(gameDir));
    }

    /**
     * Saves the generated KubeJS server script directly to
     * {@code <gameDir>/kubejs/server_scripts/planomega_sandbox.js}.
     * <p>
     * If the target file already exists and {@code confirmOverwrite} is {@code false},
     * no files are modified and {@link KubeJsSaveResult#requiresOverwriteConfirmation()} is {@code true}.
     * When {@code confirmOverwrite} is {@code true} and the file exists, a {@code .bak} copy is
     * written alongside it before overwriting.
     */
    public static KubeJsSaveResult saveKubeJsScript(
            PlanGraph graph,
            Path gameDir,
            boolean confirmOverwrite
    ) throws IOException {
        return saveKubeJsScriptToFile(graph, resolveDefaultKubeJsScriptPath(gameDir), confirmOverwrite);
    }

    /**
     * Saves the generated KubeJS server script directly to {@code targetPath}, creating a {@code .bak}
     * backup if {@code targetPath} already exists and {@code confirmOverwrite} is {@code true}.
     */
    public static KubeJsSaveResult saveKubeJsScriptToFile(
            PlanGraph graph,
            Path targetPath,
            boolean confirmOverwrite
    ) throws IOException {
        boolean exists = Files.exists(targetPath);
        if (exists && !confirmOverwrite) {
            return new KubeJsSaveResult(
                    false,
                    true,
                    targetPath,
                    null,
                    KUBEJS_RELOAD_COMMAND,
                    "Target script already exists; overwrite confirmation required."
            );
        }

        Path parent = targetPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path backupPath = null;
        if (exists) {
            backupPath = targetPath.resolveSibling(targetPath.getFileName().toString() + ".bak");
            Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        }

        String script = toKubeJsScript(graph);
        Files.writeString(targetPath, script, StandardCharsets.UTF_8);

        return new KubeJsSaveResult(
                true,
                false,
                targetPath,
                backupPath,
                KUBEJS_RELOAD_COMMAND,
                backupPath != null
                        ? "Saved KubeJS script (backup created). Run " + KUBEJS_RELOAD_COMMAND
                        : "Saved KubeJS script. Run " + KUBEJS_RELOAD_COMMAND
        );
    }

    public static List<VirtualRecipeNode> collectSandboxNodes(PlanGraph graph) {
        List<VirtualRecipeNode> list = new ArrayList<>();
        for (PlanNode node : graph.nodes().values()) {
            if (node instanceof VirtualRecipeNode v) {
                list.add(v);
            }
        }
        return list;
    }

    public static String toMarkdownDiff(PlanGraph graph, Function<String, Optional<RecipeEntry>> baseLookup) {
        List<VirtualRecipeNode> virtuals = collectSandboxNodes(graph);
        if (virtuals.isEmpty()) {
            return "# Plan Omega Sandbox Diff\n\nNo `VirtualRecipeNode` entries found in `" + graph.name() + "`.\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Plan Omega Sandbox Diff — ").append(graph.name()).append("\n\n");
        for (VirtualRecipeNode v : virtuals) {
            sb.append("## ").append(v.title()).append(" (`").append(v.id()).append("`)\n");
            Optional<RecipeEntry> baseOpt = v.baseRecipeId() != null ? baseLookup.apply(v.baseRecipeId()) : Optional.empty();
            if (baseOpt.isPresent()) {
                RecipeEntry b = baseOpt.get();
                sb.append("- **Base Recipe:** `").append(b.recipeId()).append("`\n");
                sb.append(String.format("- **EU/t:** `%d` -> `%d`\n", b.eut(), v.eut()));
                sb.append(String.format("- **Duration (ticks):** `%d` -> `%d`\n", b.durationTicks(), v.durationTicks()));
                sb.append("- **Inputs (Current):** ").append(formatPorts(b.inputs())).append('\n');
                sb.append("- **Inputs (Proposed):** ").append(formatPorts(v.inputs())).append('\n');
                sb.append("- **Outputs (Current):** ").append(formatPorts(b.outputs())).append('\n');
                sb.append("- **Outputs (Proposed):** ").append(formatPorts(v.outputs())).append('\n');
            } else {
                sb.append("- **Status:** New proposed recipe (`").append(v.recipeTypeId()).append("`)\n");
                sb.append(String.format("- **EU/t:** `%d` | **Duration:** `%d` ticks\n", v.eut(), v.durationTicks()));
                sb.append("- **Inputs:** ").append(formatPorts(v.inputs())).append('\n');
                sb.append("- **Outputs:** ").append(formatPorts(v.outputs())).append('\n');
            }
            if (!v.notes().isBlank()) {
                sb.append("- **Notes:** ").append(v.notes()).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String toKubeJsScript(PlanGraph graph) {
        List<VirtualRecipeNode> virtuals = collectSandboxNodes(graph);
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by Plan Omega Sandbox\n");
        sb.append("ServerEvents.recipes(event => {\n");
        Set<String> usedSlugs = new HashSet<>();
        for (VirtualRecipeNode v : virtuals) {
            if (v.baseRecipeId() != null) {
                sb.append("    event.remove({ id: '").append(escapeSingleQuote(v.baseRecipeId())).append("' });\n");
            }
            String typeMethod = sanitizeIdentifier(stripNamespace(v.recipeTypeId()), false);
            String slug = uniqueSlug(slugify(v.title()), usedSlugs);
            sb.append("    event.recipes.gtceu.").append(typeMethod).append("('planomega_").append(slug).append("')\n");
            for (ResourcePort in : v.inputs()) {
                if (in.resource().kind() == ResourceKind.ENERGY) {
                    continue;
                }
                long amount = Math.max(1L, Math.round(in.amount()));
                if (in.resource().kind() == ResourceKind.FLUID) {
                    String method = in.catalyst() ? "notConsumableFluid" : "inputFluids";
                    sb.append("        .").append(method).append("('")
                            .append(escapeSingleQuote(in.resource().id())).append(" ").append(amount).append("')\n");
                } else {
                    String method = in.catalyst() ? "notConsumable" : "itemInputs";
                    sb.append("        .").append(method).append("('")
                            .append(amount).append("x ").append(escapeSingleQuote(formatItemOrTagId(in))).append("')\n");
                }
            }
            for (ResourcePort out : v.outputs()) {
                if (out.resource().kind() == ResourceKind.ENERGY) {
                    continue;
                }
                long amount = Math.max(1L, Math.round(out.amount()));
                boolean chanced = out.chance() < 1.0 - 1e-6;
                int chanceBps = (int) Math.round(Math.min(1.0, Math.max(0.0, out.chance())) * 10000.0);
                int boostBps = (int) Math.round(Math.max(0.0, out.tierChanceBoost()) * 10000.0);
                if (out.resource().kind() == ResourceKind.FLUID) {
                    if (chanced) {
                        sb.append("        .chancedFluidOutput('")
                                .append(escapeSingleQuote(out.resource().id())).append(" ").append(amount)
                                .append("', ").append(chanceBps).append(", ").append(boostBps).append(")\n");
                    } else {
                        sb.append("        .outputFluids('")
                                .append(escapeSingleQuote(out.resource().id())).append(" ").append(amount).append("')\n");
                    }
                } else {
                    if (chanced) {
                        sb.append("        .chancedOutput('")
                                .append(amount).append("x ").append(escapeSingleQuote(formatItemOrTagId(out)))
                                .append("', ").append(chanceBps).append(", ").append(boostBps).append(")\n");
                    } else {
                        sb.append("        .itemOutputs('")
                                .append(amount).append("x ").append(escapeSingleQuote(formatItemOrTagId(out))).append("')\n");
                    }
                }
            }
            if (v.circuit() != null) {
                sb.append("        .circuit(").append(v.circuit()).append(")\n");
            }
            if (v.coilTemp() > 0) {
                sb.append("        .blastFurnaceTemp(").append(v.coilTemp()).append(")\n");
            }
            if (v.cleanroom()) {
                sb.append("        .cleanroom(CleanroomType.CLEANROOM)\n");
            }
            sb.append("        .duration(").append(Math.max(1, v.durationTicks())).append(")\n");
            sb.append("        .EUt(").append(v.eut()).append(");\n\n");
        }
        sb.append("});\n");
        return sb.toString();
    }

    public static String toTypeOmegaJava(PlanGraph graph) {
        List<VirtualRecipeNode> virtuals = collectSandboxNodes(graph);
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by Plan Omega Sandbox for TypeOmega\n");
        Set<String> usedSlugs = new HashSet<>();
        for (VirtualRecipeNode v : virtuals) {
            String typeConst = sanitizeIdentifier(stripNamespace(v.recipeTypeId()), true);
            String slug = uniqueSlug(slugify(v.title()), usedSlugs);
            sb.append("GTRecipeTypes.").append(typeConst).append(".recipeBuilder(\"").append(slug).append("\")\n");
            for (ResourcePort in : v.inputs()) {
                if (in.resource().kind() == ResourceKind.ENERGY) {
                    continue;
                }
                long amount = Math.max(1L, Math.round(in.amount()));
                if (in.resource().kind() == ResourceKind.FLUID) {
                    String method = in.catalyst() ? "notConsumableFluid" : "inputFluids";
                    sb.append("        .").append(method).append("(GTMaterials.get(\"")
                            .append(escapeDoubleQuote(stripNamespace(in.resource().id())))
                            .append("\").getFluid(").append(amount).append("))\n");
                } else {
                    String method = in.catalyst() ? "notConsumable" : "inputItems";
                    sb.append("        .").append(method).append("(\"")
                            .append(escapeDoubleQuote(formatItemOrTagId(in)))
                            .append("\", ").append(amount).append(")\n");
                }
            }
            for (ResourcePort out : v.outputs()) {
                if (out.resource().kind() == ResourceKind.ENERGY) {
                    continue;
                }
                long amount = Math.max(1L, Math.round(out.amount()));
                boolean chanced = out.chance() < 1.0 - 1e-6;
                int chanceBps = (int) Math.round(Math.min(1.0, Math.max(0.0, out.chance())) * 10000.0);
                int boostBps = (int) Math.round(Math.max(0.0, out.tierChanceBoost()) * 10000.0);
                if (out.resource().kind() == ResourceKind.FLUID) {
                    if (chanced) {
                        sb.append("        .chancedFluidOutput(GTMaterials.get(\"")
                                .append(escapeDoubleQuote(stripNamespace(out.resource().id())))
                                .append("\").getFluid(").append(amount).append("), ")
                                .append(chanceBps).append(", ").append(boostBps).append(")\n");
                    } else {
                        sb.append("        .outputFluids(GTMaterials.get(\"")
                                .append(escapeDoubleQuote(stripNamespace(out.resource().id())))
                                .append("\").getFluid(").append(amount).append("))\n");
                    }
                } else {
                    if (chanced) {
                        sb.append("        .chancedOutput(\"")
                                .append(escapeDoubleQuote(formatItemOrTagId(out)))
                                .append("\", ").append(amount).append(", ")
                                .append(chanceBps).append(", ").append(boostBps).append(")\n");
                    } else {
                        sb.append("        .outputItems(\"")
                                .append(escapeDoubleQuote(formatItemOrTagId(out)))
                                .append("\", ").append(amount).append(")\n");
                    }
                }
            }
            if (v.circuit() != null) {
                sb.append("        .circuitMeta(").append(v.circuit()).append(")\n");
            }
            if (v.coilTemp() > 0) {
                sb.append("        .blastFurnaceTemp(").append(v.coilTemp()).append(")\n");
            }
            if (v.cleanroom()) {
                sb.append("        .cleanroom(CleanroomType.CLEANROOM)\n");
            }
            sb.append("        .duration(").append(Math.max(1, v.durationTicks())).append(")\n");
            sb.append("        .EUt(").append(v.eut()).append(")\n");
            sb.append("        .save(provider);\n\n");
        }
        return sb.toString();
    }

    private static String formatPorts(List<ResourcePort> ports) {
        if (ports.isEmpty()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        for (ResourcePort p : ports) {
            parts.add((long) p.amount() + " " + p.resource().id());
        }
        return String.join(", ", parts);
    }

    private static String formatItemOrTagId(ResourcePort port) {
        String id = port.resource().id();
        if (port.resource().kind() == ResourceKind.TAG && !id.startsWith("#")) {
            return "#" + id;
        }
        return id;
    }

    private static String sanitizeIdentifier(String raw, boolean upper) {
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) {
            cleaned = "recipe_" + cleaned;
        }
        return upper ? cleaned.toUpperCase(Locale.ROOT) : cleaned.toLowerCase(Locale.ROOT);
    }

    private static String uniqueSlug(String base, Set<String> used) {
        if (used.add(base)) {
            return base;
        }
        int idx = 2;
        while (!used.add(base + "_" + idx)) {
            idx++;
        }
        return base + "_" + idx;
    }

    private static String escapeSingleQuote(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String escapeDoubleQuote(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static String slugify(String raw) {
        String s = raw.toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isBlank() ? "virtual_recipe" : s;
    }
}
