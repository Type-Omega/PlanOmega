package com.typeomega.planomega.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ResourcePort;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java recipe parsing, chance calculation, fusion/assembly-line classification, and
 * JSON tag extraction helpers for {@link GTCeuRecipeExtractor}.
 * <p>
 * Kept free of Minecraft/Forge/GTCEu runtime dependencies so it can be unit-tested directly
 * in JUnit 5 without a full client bootstrap.
 */
public final class GTCeuRecipeParsingHelpers {

    public static final long FUSION_MK1_MAX_EU = 160_000_000L;
    public static final long FUSION_MK2_MAX_EU = 320_000_000L;
    public static final long FUSION_MK3_MAX_EU = 640_000_000L;

    public enum ChanceMode {
        OR,
        AND,
        XOR,
        FIRST,
        NONE
    }

    public record ChanceInput(int chance, int maxChance, int tierChanceBoost) {
    }

    private GTCeuRecipeParsingHelpers() {
    }

    public static boolean isCatalystInput(PortDirection direction, int chance) {
        return direction == PortDirection.INPUT && chance == 0;
    }

    public static double computeTierChanceBoost(int tierChanceBoost, int maxChance) {
        return maxChance > 0 ? (double) tierChanceBoost / maxChance : 0.0;
    }

    public static double[] computeEffectiveChances(List<ChanceInput> contents, ChanceMode mode) {
        double[] result = new double[contents.size()];
        if (contents.isEmpty()) {
            return result;
        }
        ChanceMode effectiveMode = mode == null ? ChanceMode.OR : mode;

        if (effectiveMode == ChanceMode.NONE) {
            Arrays.fill(result, 1.0);
            return result;
        }

        if (effectiveMode == ChanceMode.XOR) {
            double chancedSum = 0.0;
            for (ChanceInput c : contents) {
                if (c.maxChance() > 0 && c.chance() > 0 && c.chance() < c.maxChance()) {
                    chancedSum += (double) c.chance() / c.maxChance();
                }
            }
            double norm = chancedSum > 1.0 ? chancedSum : 1.0;
            for (int i = 0; i < contents.size(); i++) {
                ChanceInput c = contents.get(i);
                if (c.maxChance() > 0 && c.chance() > 0 && c.chance() < c.maxChance()) {
                    result[i] = Math.min(1.0, ((double) c.chance() / c.maxChance()) / norm);
                } else {
                    result[i] = 1.0;
                }
            }
            return result;
        }

        if (effectiveMode == ChanceMode.FIRST) {
            double remainingProb = 1.0;
            for (int i = 0; i < contents.size(); i++) {
                ChanceInput c = contents.get(i);
                if (c.maxChance() > 0 && c.chance() > 0 && c.chance() < c.maxChance()) {
                    double p = Math.min(1.0, (double) c.chance() / c.maxChance());
                    result[i] = remainingProb * p;
                    remainingProb *= (1.0 - p);
                } else {
                    result[i] = 1.0;
                }
            }
            return result;
        }

        for (int i = 0; i < contents.size(); i++) {
            ChanceInput c = contents.get(i);
            result[i] = c.maxChance() > 0 && c.chance() > 0
                    ? Math.min(1.0, (double) c.chance() / c.maxChance())
                    : 1.0;
        }
        return result;
    }

    /**
     * Extracts a tag ID from a serialized Minecraft/Forge/GTCEu/KubeJS ingredient JSON element,
     * supporting direct {@code {"tag": "..."}} and wrapped {@code ingredient}/{@code base}/{@code value}
     * objects as well as single-element {@code children} arrays.
     */
    public static @Nullable String extractTagFromJson(@Nullable JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return null;
        }
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("tag") && obj.get("tag").isJsonPrimitive()) {
            String tag = obj.get("tag").getAsString();
            if (!tag.isBlank()) {
                return tag.startsWith("#") ? tag.substring(1) : tag;
            }
        }
        for (String wrapperKey : List.of("ingredient", "base", "value")) {
            if (obj.has(wrapperKey) && obj.get(wrapperKey).isJsonObject()) {
                String nested = extractTagFromJson(obj.get(wrapperKey));
                if (nested != null) {
                    return nested;
                }
            }
        }
        if (obj.has("children") && obj.get("children").isJsonArray()) {
            JsonArray children = obj.getAsJsonArray("children");
            if (children.size() == 1) {
                return extractTagFromJson(children.get(0));
            }
        }
        return null;
    }

    /**
     * Classifies a GTCEu fusion recipe's {@code eu_to_start} into Mk 1/2/3 (or 0 if not a fusion recipe).
     */
    public static int classifyFusionMk(long euToStart) {
        if (euToStart <= 0L) {
            return 0;
        }
        if (euToStart <= FUSION_MK1_MAX_EU) {
            return 1;
        }
        if (euToStart <= FUSION_MK2_MAX_EU) {
            return 2;
        }
        return 3;
    }

    /**
     * Returns the minimum {@link com.typeomega.planomega.model.VoltageTier} index required for a
     * fusion recipe based on {@code eu_to_start}: Mk1 = LuV (6), Mk2 = ZPM (7), Mk3 = UV (8).
     */
    public static int computeFusionMinTier(long euToStart) {
        return switch (classifyFusionMk(euToStart)) {
            case 1 -> 6; // LuV
            case 2 -> 7; // ZPM
            case 3 -> 8; // UV
            default -> 0;
        };
    }

    public static long resolveSignedEut(long inputEu, long outputEu) {
        if (outputEu > 0L && inputEu <= 0L) {
            return -outputEu;
        }
        return Math.max(0L, inputEu);
    }

    public static String buildTitle(List<ResourcePort> outputs, List<ResourcePort> inputs, String machineLabel) {
        if (!outputs.isEmpty()) {
            String suffix = outputs.size() > 1 ? " +" + (outputs.size() - 1) : "";
            return outputs.get(0).displayLabel() + suffix + " (" + machineLabel + ")";
        }
        if (!inputs.isEmpty()) {
            return inputs.get(0).displayLabel() + " (" + machineLabel + ")";
        }
        return machineLabel;
    }

    public static String prettyMachineName(String typeId) {
        int colon = typeId.indexOf(':');
        String path = colon >= 0 ? typeId.substring(colon + 1) : typeId;
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /**
     * Enriches a recipe's search blob with GTCEu metadata tokens for research, fusion Mk tier,
     * assembly line, and non-consumable catalyst flags.
     */
    public static String buildExtendedSearchBlob(
            String baseBlob,
            @Nullable Collection<String> researchIds,
            long fusionStartEu,
            boolean isAssemblyLine,
            boolean hasCatalyst
    ) {
        StringBuilder sb = new StringBuilder(baseBlob == null ? "" : baseBlob.toLowerCase(Locale.ROOT));
        if (researchIds != null && !researchIds.isEmpty()) {
            sb.append(" research");
            for (String id : researchIds) {
                if (id != null && !id.isBlank()) {
                    sb.append(" research:").append(id.toLowerCase(Locale.ROOT));
                }
            }
        }
        int fusionMk = classifyFusionMk(fusionStartEu);
        if (fusionMk > 0) {
            sb.append(" fusion mk").append(fusionMk);
        }
        if (isAssemblyLine) {
            sb.append(" assembly_line assline");
        }
        if (hasCatalyst) {
            sb.append(" catalyst not_consumable");
        }
        return sb.toString().trim();
    }
}
