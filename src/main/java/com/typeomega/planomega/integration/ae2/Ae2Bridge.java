package com.typeomega.planomega.integration.ae2;

import com.typeomega.planomega.PlanOmega;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.model.ResourcePort;
import net.minecraftforge.fml.ModList;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Optional, crash-safe bridge to Applied Energistics 2 ({@code ae2}).
 * <p>
 * This class deliberately has zero direct {@code appeng.*} imports; all AE2 classloading is
 * isolated inside {@link Ae2GridAccessor} and guarded behind {@link #isAvailable()}.
 */
public final class Ae2Bridge {

    private static volatile Ae2NetworkSnapshot cachedSnapshot = Ae2NetworkSnapshot.EMPTY;

    private Ae2Bridge() {
    }

    /**
     * Status of a single resource in an AE2 ME Network.
     */
    public record Ae2ResourceStatus(
            ResourceKey resource,
            long storedAmount,
            boolean craftable
    ) {
        public boolean isAvailable() {
            return storedAmount > 0L || craftable;
        }
    }

    /**
     * Immutable point-in-time snapshot of an AE2 ME Network's stored items/fluids and registered autocrafting patterns.
     * Keys are {@link ResourceKey#canonicalId()} strings (e.g. {@code item:minecraft:iron_ingot}, {@code fluid:gtceu:oxygen}).
     */
    public record Ae2NetworkSnapshot(
            Map<String, Long> storedAmounts,
            Set<String> craftableCanonicalIds,
            String sourceLabel,
            long capturedAtMs
    ) {
        public static final Ae2NetworkSnapshot EMPTY =
                new Ae2NetworkSnapshot(Map.of(), Set.of(), "none", 0L);

        public Ae2NetworkSnapshot {
            storedAmounts = storedAmounts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(storedAmounts));
            craftableCanonicalIds = craftableCanonicalIds == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(craftableCanonicalIds));
            sourceLabel = sourceLabel == null ? "unknown" : sourceLabel;
        }

        public boolean isEmpty() {
            return storedAmounts.isEmpty() && craftableCanonicalIds.isEmpty();
        }

        public long getStoredAmount(ResourceKey key) {
            if (key == null) {
                return 0L;
            }
            return storedAmounts.getOrDefault(key.canonicalId(), 0L);
        }

        public boolean isCraftable(ResourceKey key) {
            if (key == null) {
                return false;
            }
            return craftableCanonicalIds.contains(key.canonicalId());
        }

        public Ae2ResourceStatus statusOf(ResourceKey key) {
            Objects.requireNonNull(key, "key");
            return new Ae2ResourceStatus(key, getStoredAmount(key), isCraftable(key));
        }

        /**
         * Queries all item and fluid resources referenced in {@code graph} against this snapshot.
         * Returns a map keyed by {@link ResourceKey#canonicalId()} for resources that either have
         * stored inventory ({@code > 0}) or a registered AE2 autocrafting pattern.
         */
        public Map<String, Ae2ResourceStatus> queryPlanResources(PlanGraph graph) {
            if (graph == null || isEmpty()) {
                return Map.of();
            }
            Map<String, Ae2ResourceStatus> result = new LinkedHashMap<>();
            for (PlanNode node : graph.nodes().values()) {
                if (!(node instanceof ProcessNode processNode)) {
                    continue;
                }
                for (ResourcePort port : processNode.inputs()) {
                    collectPortStatus(port, result);
                }
                for (ResourcePort port : processNode.outputs()) {
                    collectPortStatus(port, result);
                }
            }
            return Collections.unmodifiableMap(result);
        }

        /**
         * Returns the canonical IDs of all resources in {@code graph} that have AE2 autocrafting patterns.
         */
        public Set<String> findCraftableResources(PlanGraph graph) {
            if (graph == null || craftableCanonicalIds.isEmpty()) {
                return Set.of();
            }
            Set<String> craftable = new LinkedHashSet<>();
            for (Map.Entry<String, Ae2ResourceStatus> entry : queryPlanResources(graph).entrySet()) {
                if (entry.getValue().craftable()) {
                    craftable.add(entry.getKey());
                }
            }
            return Collections.unmodifiableSet(craftable);
        }

        /**
         * Returns the canonical IDs of all resources in {@code graph} with {@code storedAmount > 0} in AE2.
         */
        public Set<String> findAvailableResources(PlanGraph graph) {
            if (graph == null || storedAmounts.isEmpty()) {
                return Set.of();
            }
            Set<String> available = new LinkedHashSet<>();
            for (Map.Entry<String, Ae2ResourceStatus> entry : queryPlanResources(graph).entrySet()) {
                if (entry.getValue().storedAmount() > 0L) {
                    available.add(entry.getKey());
                }
            }
            return Collections.unmodifiableSet(available);
        }

        private void collectPortStatus(ResourcePort port, Map<String, Ae2ResourceStatus> out) {
            ResourceKey key = port.resource();
            if (key.kind() != ResourceKind.ITEM && key.kind() != ResourceKind.FLUID) {
                return;
            }
            String canonical = key.canonicalId();
            if (out.containsKey(canonical)) {
                return;
            }
            long stored = getStoredAmount(key);
            boolean craftable = isCraftable(key);
            if (stored > 0L || craftable) {
                out.put(canonical, new Ae2ResourceStatus(key, stored, craftable));
            }
        }
    }

    /**
     * Returns {@code true} when Applied Energistics 2 ({@code ae2}) is loaded in the active Forge runtime.
     */
    public static boolean isAvailable() {
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded("ae2");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Registers optional client-side AE2 screen hooks (safe no-op if AE2 is not loaded).
     */
    public static void registerEvents() {
        if (!isAvailable()) {
            return;
        }
        try {
            Ae2GridAccessor.registerScreenHook();
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("AE2 event hook registration skipped", t);
        }
    }

    /**
     * Captures the ME inventory and craftables if the local player currently has an AE2 ME terminal open.
     * Updates the cached snapshot if successful.
     */
    public static Ae2NetworkSnapshot captureFromOpenTerminal() {
        if (!isAvailable()) {
            return Ae2NetworkSnapshot.EMPTY;
        }
        try {
            Ae2GridAccessor.registerScreenHook();
            Ae2NetworkSnapshot snapshot = Ae2GridAccessor.captureFromOpenTerminal();
            if (!snapshot.isEmpty()) {
                cachedSnapshot = snapshot;
            }
            return snapshot;
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("AE2 open terminal capture failed", t);
            return Ae2NetworkSnapshot.EMPTY;
        }
    }

    /**
     * Queries the best available AE2 ME Network snapshot:
     * <ol>
     *     <li>An currently open AE2 ME terminal menu on the client, if any.</li>
     *     <li>A nearby ME grid block entity in singleplayer (integrated server), if any.</li>
     *     <li>The most recently cached snapshot from an opened ME terminal.</li>
     * </ol>
     */
    public static Ae2NetworkSnapshot queryNearbyOrCached() {
        if (!isAvailable()) {
            return Ae2NetworkSnapshot.EMPTY;
        }
        try {
            Ae2GridAccessor.registerScreenHook();
            Ae2NetworkSnapshot open = Ae2GridAccessor.captureFromOpenTerminal();
            if (!open.isEmpty()) {
                cachedSnapshot = open;
                return open;
            }
            Ae2NetworkSnapshot nearby = Ae2GridAccessor.queryNearbyIntegratedServer();
            if (!nearby.isEmpty()) {
                cachedSnapshot = nearby;
                return nearby;
            }
            return cachedSnapshot;
        } catch (Throwable t) {
            PlanOmega.LOGGER.debug("AE2 nearby/cached query failed", t);
            return cachedSnapshot;
        }
    }

    public static Ae2NetworkSnapshot getCachedSnapshot() {
        return cachedSnapshot;
    }

    public static void setCachedSnapshotForTesting(Ae2NetworkSnapshot snapshot) {
        cachedSnapshot = snapshot == null ? Ae2NetworkSnapshot.EMPTY : snapshot;
    }

    public static void clearCache() {
        cachedSnapshot = Ae2NetworkSnapshot.EMPTY;
    }

    public static Map<String, Ae2ResourceStatus> queryPlanResources(PlanGraph graph) {
        return queryNearbyOrCached().queryPlanResources(graph);
    }

    public static Set<String> findCraftableResources(PlanGraph graph) {
        return queryNearbyOrCached().findCraftableResources(graph);
    }

    public static boolean hasAutocrafting(ResourceKey key) {
        return queryNearbyOrCached().isCraftable(key);
    }

    public static long getStoredAmount(ResourceKey key) {
        return queryNearbyOrCached().getStoredAmount(key);
    }
}
