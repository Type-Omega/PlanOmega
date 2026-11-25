package com.typeomega.planomega.integration.ae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.AEKeyFilter;
import appeng.menu.me.common.GridInventoryEntry;
import appeng.menu.me.common.IClientRepo;
import appeng.menu.me.common.MEStorageMenu;
import com.typeomega.planomega.integration.ae2.Ae2Bridge.Ae2NetworkSnapshot;
import com.typeomega.planomega.model.ResourceKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Isolated AE2 accessor. Must only be classloaded when {@code ModList.get().isLoaded("ae2")} is true.
 */
final class Ae2GridAccessor {

    private static final int NEARBY_SCAN_RADIUS = 12;
    private static final AtomicBoolean HOOK_REGISTERED = new AtomicBoolean(false);

    private Ae2GridAccessor() {
    }

    static void registerScreenHook() {
        if (HOOK_REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Closing event) -> {
                Ae2NetworkSnapshot snap = captureFromOpenTerminal();
                if (!snap.isEmpty()) {
                    Ae2Bridge.setCachedSnapshotForTesting(snap);
                }
            });
        }
    }

    static Ae2NetworkSnapshot captureFromOpenTerminal() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !(player.containerMenu instanceof MEStorageMenu menu)) {
            return Ae2NetworkSnapshot.EMPTY;
        }
        IClientRepo repo = menu.getClientRepo();
        if (repo == null) {
            return Ae2NetworkSnapshot.EMPTY;
        }

        Map<String, Long> stored = new LinkedHashMap<>();
        Set<String> craftable = new LinkedHashSet<>();
        for (GridInventoryEntry entry : repo.getAllEntries()) {
            String canonical = toCanonicalId(entry.getWhat());
            if (canonical == null) {
                continue;
            }
            long amount = entry.getStoredAmount();
            if (amount > 0L) {
                stored.merge(canonical, amount, Long::sum);
            }
            if (entry.isCraftable()) {
                craftable.add(canonical);
            }
        }
        if (stored.isEmpty() && craftable.isEmpty()) {
            return Ae2NetworkSnapshot.EMPTY;
        }
        return new Ae2NetworkSnapshot(stored, craftable, "me_terminal", System.currentTimeMillis());
    }

    static Ae2NetworkSnapshot queryNearbyIntegratedServer() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        IntegratedServer server = mc.getSingleplayerServer();
        if (player == null || server == null) {
            return Ae2NetworkSnapshot.EMPTY;
        }
        ServerLevel serverLevel = server.getLevel(player.level().dimension());
        if (serverLevel == null) {
            return Ae2NetworkSnapshot.EMPTY;
        }

        BlockPos origin = player.blockPosition();
        IGrid foundGrid = findNearestGrid(serverLevel, origin, NEARBY_SCAN_RADIUS);
        if (foundGrid == null) {
            return Ae2NetworkSnapshot.EMPTY;
        }

        Map<String, Long> stored = new LinkedHashMap<>();
        Set<String> craftable = new LinkedHashSet<>();

        IStorageService storage = foundGrid.getStorageService();
        if (storage != null) {
            KeyCounter counter = storage.getCachedInventory();
            for (AEKey key : counter.keySet()) {
                String canonical = toCanonicalId(key);
                if (canonical == null) {
                    continue;
                }
                long amt = counter.get(key);
                if (amt > 0L) {
                    stored.merge(canonical, amt, Long::sum);
                }
            }
        }

        ICraftingService crafting = foundGrid.getCraftingService();
        if (crafting != null) {
            for (AEKey key : crafting.getCraftables(AEKeyFilter.none())) {
                String canonical = toCanonicalId(key);
                if (canonical != null) {
                    craftable.add(canonical);
                }
            }
        }

        if (stored.isEmpty() && craftable.isEmpty()) {
            return Ae2NetworkSnapshot.EMPTY;
        }
        return new Ae2NetworkSnapshot(stored, craftable, "nearby_me_grid", System.currentTimeMillis());
    }

    private static @Nullable IGrid findNearestGrid(ServerLevel level, BlockPos origin, int radius) {
        IGrid bestGrid = null;
        double bestDistSq = Double.MAX_VALUE;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius / 2; dy <= radius / 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    BlockEntity be = level.getBlockEntity(cursor);
                    if (!(be instanceof IInWorldGridNodeHost host)) {
                        continue;
                    }
                    IGrid grid = extractGrid(host);
                    if (grid != null) {
                        double distSq = origin.distSqr(cursor);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            bestGrid = grid;
                        }
                    }
                }
            }
        }
        return bestGrid;
    }

    private static @Nullable IGrid extractGrid(IInWorldGridNodeHost host) {
        IGridNode direct = host.getGridNode(null);
        if (direct != null && direct.getGrid() != null) {
            return direct.getGrid();
        }
        for (Direction dir : Direction.values()) {
            IGridNode node = host.getGridNode(dir);
            if (node != null && node.getGrid() != null) {
                return node.getGrid();
            }
        }
        return null;
    }

    private static @Nullable String toCanonicalId(@Nullable AEKey key) {
        if (key instanceof AEItemKey itemKey) {
            return ResourceKey.item(itemKey.getId().toString()).canonicalId();
        }
        if (key instanceof AEFluidKey fluidKey) {
            return ResourceKey.fluid(fluidKey.getId().toString()).canonicalId();
        }
        return null;
    }
}
