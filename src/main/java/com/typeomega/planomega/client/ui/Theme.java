package com.typeomega.planomega.client.ui;

import com.typeomega.planomega.PlanOmega;
import net.minecraft.resources.ResourceLocation;

/**
 * Visual theme tokens for Plan Omega's dark industrial TypeOmega canvas UI.
 */
public final class Theme {

    public static final ResourceLocation ICON_64 = ResourceLocation.fromNamespaceAndPath(
            PlanOmega.MOD_ID, "textures/logo/plan_omega_icon_64.png"
    );
    public static final ResourceLocation LOGO_1X = ResourceLocation.fromNamespaceAndPath(
            PlanOmega.MOD_ID, "textures/logo/plan_omega_logo_1x.png"
    );

    // Lucide-style toolbar & badge icons (32x32 RGBA)
    public static final ResourceLocation ICON_RECIPE = icon("recipe");
    public static final ResourceLocation ICON_NOTE = icon("note");
    public static final ResourceLocation ICON_SANDBOX = icon("sandbox");
    public static final ResourceLocation ICON_GROUP = icon("group");
    public static final ResourceLocation ICON_UNDO = icon("undo");
    public static final ResourceLocation ICON_REDO = icon("redo");
    public static final ResourceLocation ICON_GRID = icon("grid");
    public static final ResourceLocation ICON_SNAP = icon("snap");
    public static final ResourceLocation ICON_FIT = icon("fit");
    public static final ResourceLocation ICON_LAYOUT = icon("layout");
    public static final ResourceLocation ICON_EXPORT = icon("export");
    public static final ResourceLocation ICON_SUMMARY = icon("summary");
    public static final ResourceLocation ICON_PLANS = icon("plans");
    public static final ResourceLocation ICON_WARNING = icon("warning");

    private static ResourceLocation icon(String name) {
        return ResourceLocation.fromNamespaceAndPath(PlanOmega.MOD_ID, "textures/icons/" + name + ".png");
    }

    // Canvas & grid
    public static final int CANVAS_BG = 0xFF0D1117;
    public static final int GRID_MINOR = 0xFF161D27;
    public static final int GRID_MAJOR = 0xFF212B3A;
    public static final int GRID_ORIGIN = 0xFF2DD4BF;

    // Chrome (toolbar / status bar / panels)
    public static final int BAR_BG = 0xEE111822;
    public static final int BAR_BORDER = 0xFF263244;
    public static final int BTN_BG = 0xFF1B2432;
    public static final int BTN_HOVER = 0xFF263549;
    public static final int BTN_ACTIVE = 0xFF155E75;
    public static final int BTN_BORDER = 0xFF334155;

    // Text
    public static final int TEXT_PRIMARY = 0xFFF1F5F9;
    public static final int TEXT_SECONDARY = 0xFF94A3B8;
    public static final int TEXT_MUTED = 0xFF64748B;
    public static final int ACCENT_TEAL = 0xFF2DD4BF;
    public static final int ACCENT_AMBER = 0xFFF59E0B;

    // Nodes
    public static final int NODE_BG = 0xEE161E2B;
    public static final int NODE_HEADER_BG = 0xFF1E293B;
    public static final int NODE_VIRTUAL_HEADER_BG = 0xFF312413;
    public static final int NODE_BORDER = 0xFF334155;
    public static final int NODE_SELECTED_BORDER = 0xFF2DD4BF;
    public static final int NODE_VIRTUAL_BORDER = 0xFFF59E0B;

    // Selection marquee & context menu
    public static final int MARQUEE_FILL = 0x332DD4BF;
    public static final int MARQUEE_BORDER = 0xCC2DD4BF;
    public static final int MODAL_BACKDROP = 0xAA05080D;
    public static final int MODAL_BG = 0xF5131B26;

    private Theme() {
    }

    public static int withAlpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
