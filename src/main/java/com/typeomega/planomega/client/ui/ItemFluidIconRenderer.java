package com.typeomega.planomega.client.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourceKind;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITag;
import net.minecraftforge.registries.tags.ITagManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders cached {@link ItemStack} icons, fluid color swatches, and cycling {@link ResourceKind#TAG}
 * representative icons for {@link ResourceKey} entries.
 */
public final class ItemFluidIconRenderer {

    private static final Map<String, ItemStack> ITEM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Integer> FLUID_COLOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<ItemStack>> TAG_ITEM_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<Integer>> TAG_FLUID_COLOR_CACHE = new ConcurrentHashMap<>();

    private ItemFluidIconRenderer() {
    }

    public static void clearCache() {
        ITEM_CACHE.clear();
        FLUID_COLOR_CACHE.clear();
        TAG_ITEM_CACHE.clear();
        TAG_FLUID_COLOR_CACHE.clear();
    }

    public static void render(GuiGraphics gfx, ResourceKey key, int x, int y, int size) {
        if (key.kind() == ResourceKind.FLUID) {
            renderFluidSwatch(gfx, resolveFluidColor(key.id()), x, y, size);
            return;
        }
        if (key.kind() == ResourceKind.ENERGY) {
            gfx.fill(x + 1, y + 1, x + size - 1, y + size - 1, ResourceKind.ENERGY.color());
            return;
        }
        if (key.kind() == ResourceKind.TAG) {
            renderTag(gfx, key.id(), x, y, size);
            return;
        }

        ItemStack stack = resolveItemStack(key.id());
        if (stack.isEmpty()) {
            gfx.fill(x + 1, y + 1, x + size - 1, y + size - 1, key.kind().color());
            return;
        }

        renderItemStack(gfx, stack, x, y, size);
    }

    private static void renderTag(GuiGraphics gfx, String tagId, int x, int y, int size) {
        List<ItemStack> itemReps = resolveTagItems(tagId);
        if (!itemReps.isEmpty()) {
            int idx = (int) ((System.currentTimeMillis() / 1000L) % itemReps.size());
            renderItemStack(gfx, itemReps.get(idx), x, y, size);
            return;
        }

        List<Integer> fluidColors = resolveTagFluidColors(tagId);
        if (!fluidColors.isEmpty()) {
            int idx = (int) ((System.currentTimeMillis() / 1000L) % fluidColors.size());
            renderFluidSwatch(gfx, fluidColors.get(idx), x, y, size);
            return;
        }

        gfx.fill(x + 1, y + 1, x + size - 1, y + size - 1, ResourceKind.TAG.color());
    }

    private static void renderItemStack(GuiGraphics gfx, ItemStack stack, int x, int y, int size) {
        PoseStack pose = gfx.pose();
        pose.pushPose();
        pose.translate(x, y, 0f);
        float scale = size / 16.0f;
        pose.scale(scale, scale, 1.0f);
        gfx.renderFakeItem(stack, 0, 0);
        pose.popPose();
    }

    private static void renderFluidSwatch(GuiGraphics gfx, int color, int x, int y, int size) {
        gfx.fill(x, y, x + size, y + size, 0xFF0B121B);
        gfx.fill(x + 1, y + 1, x + size - 1, y + size - 1, color);
        gfx.fill(x + 2, y + 2, x + size / 2, y + size / 2, 0x44FFFFFF);
    }

    private static List<ItemStack> resolveTagItems(String tagId) {
        List<ItemStack> cached = TAG_ITEM_CACHE.get(tagId);
        if (cached != null) {
            return cached;
        }
        ResourceLocation rl = ResourceLocation.tryParse(tagId);
        ITagManager<Item> tags = ForgeRegistries.ITEMS.tags();
        if (rl == null || tags == null) {
            return List.of();
        }
        ITag<Item> tag = tags.getTag(ItemTags.create(rl));
        if (tag.isEmpty()) {
            return List.of();
        }
        List<ItemStack> list = new ArrayList<>();
        for (Item item : tag) {
            if (item != null && item != Items.AIR) {
                list.add(new ItemStack(item));
                if (list.size() >= 16) {
                    break;
                }
            }
        }
        if (!list.isEmpty()) {
            List<ItemStack> immutable = List.copyOf(list);
            TAG_ITEM_CACHE.put(tagId, immutable);
            return immutable;
        }
        return List.of();
    }

    private static List<Integer> resolveTagFluidColors(String tagId) {
        List<Integer> cached = TAG_FLUID_COLOR_CACHE.get(tagId);
        if (cached != null) {
            return cached;
        }
        ResourceLocation rl = ResourceLocation.tryParse(tagId);
        ITagManager<Fluid> tags = ForgeRegistries.FLUIDS.tags();
        if (rl == null || tags == null) {
            return List.of();
        }
        ITag<Fluid> tag = tags.getTag(FluidTags.create(rl));
        if (tag.isEmpty()) {
            return List.of();
        }
        List<Integer> list = new ArrayList<>();
        for (Fluid fluid : tag) {
            if (fluid != null && fluid != Fluids.EMPTY) {
                list.add(fluidColorOf(fluid));
                if (list.size() >= 16) {
                    break;
                }
            }
        }
        if (!list.isEmpty()) {
            List<Integer> immutable = List.copyOf(list);
            TAG_FLUID_COLOR_CACHE.put(tagId, immutable);
            return immutable;
        }
        return List.of();
    }

    private static ItemStack resolveItemStack(String id) {
        return ITEM_CACHE.computeIfAbsent(id, raw -> {
            ResourceLocation rl = ResourceLocation.tryParse(raw);
            if (rl == null) {
                return ItemStack.EMPTY;
            }
            Item item = ForgeRegistries.ITEMS.getValue(rl);
            if (item == null || item == Items.AIR) {
                return ItemStack.EMPTY;
            }
            return new ItemStack(item);
        });
    }

    private static int resolveFluidColor(String id) {
        return FLUID_COLOR_CACHE.computeIfAbsent(id, raw -> {
            ResourceLocation rl = ResourceLocation.tryParse(raw);
            if (rl == null) {
                return ResourceKind.FLUID.color();
            }
            Fluid fluid = ForgeRegistries.FLUIDS.getValue(rl);
            if (fluid == null || fluid == Fluids.EMPTY) {
                return ResourceKind.FLUID.color();
            }
            return fluidColorOf(fluid);
        });
    }

    private static int fluidColorOf(Fluid fluid) {
        int tint = IClientFluidTypeExtensions.of(fluid).getTintColor();
        if ((tint & 0x00FFFFFF) == 0 || (tint & 0xFF000000) == 0) {
            return ResourceKind.FLUID.color();
        }
        return 0xFF000000 | (tint & 0x00FFFFFF);
    }
}
