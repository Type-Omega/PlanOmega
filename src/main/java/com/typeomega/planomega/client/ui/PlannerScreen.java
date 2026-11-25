package com.typeomega.planomega.client.ui;

import com.typeomega.planomega.audit.AuditReport;
import com.typeomega.planomega.audit.ChainAuditor;
import com.typeomega.planomega.audit.FrictionFlag;
import com.typeomega.planomega.client.storage.PlanStorage;
import com.typeomega.planomega.client.ui.CanvasGeometry.PortHit;
import com.typeomega.planomega.client.ui.CanvasRenderer.MinimapBounds;
import com.typeomega.planomega.client.ui.CanvasRenderer.RenderContext;
import com.typeomega.planomega.codec.BlueprintCodec;
import com.typeomega.planomega.codec.GroupTemplateCodec;
import com.typeomega.planomega.integration.IntegrationBridge;
import com.typeomega.planomega.integration.PendingRecipeQueue;
import com.typeomega.planomega.integration.ae2.Ae2Bridge;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.StickyNoteNode;
import com.typeomega.planomega.model.UndoHistory;
import com.typeomega.planomega.model.VirtualRecipeNode;
import com.typeomega.planomega.model.VoltageTier;
import com.typeomega.planomega.recipe.RecipeCatalog;
import com.typeomega.planomega.recipe.RecipeCatalogManager;
import com.typeomega.planomega.recipe.RecipeEntry;
import com.typeomega.planomega.solver.ProductionSolver;
import com.typeomega.planomega.solver.SolveResult;
import com.typeomega.planomega.solver.SolveResult.NodeSolveStats;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Main client-side screen hosting Plan Omega's infinite 2D production planning canvas.
 */
public final class PlannerScreen extends Screen {

    private static final int TOOLBAR_SINGLE_HEIGHT = 30;
    private static final int TOOLBAR_DOUBLE_HEIGHT = 54;
    private static final int TAB_BAR_HEIGHT = 22;
    private static final int STATUS_HEIGHT = 20;
    private static final DecimalFormat TOOLTIP_FMT = new DecimalFormat("#.##");

    private final CanvasViewport viewport = new CanvasViewport();
    private final SummaryDrawer summaryDrawer = new SummaryDrawer();
    private final List<ToolbarButton> toolbarButtons = new ArrayList<>();
    private final List<PlanTab> openTabs = new ArrayList<>();
    private final List<TabRect> tabRects = new ArrayList<>();
    private int activeTabIndex = 0;
    private int newTabBtnX = 0;
    private int newTabBtnY = 0;
    private int newTabBtnW = 0;
    private int newTabBtnH = 0;

    private UndoHistory history = new UndoHistory(64);
    private Set<String> selectedNodeIds = new LinkedHashSet<>();

    private int toolbarHeight = TOOLBAR_SINGLE_HEIGHT;
    private boolean showToolbarTitle = true;

    private PlanGraph graph;
    private SolveResult solveResult;
    private AuditReport auditReport;
    private @Nullable String selectedEdgeId;
    private boolean showGrid = true;
    private boolean snapToGrid = true;
    private boolean orthogonalRouting = false;
    private boolean summaryOpen = true;

    // Floating Quick Search (Ctrl+F)
    private boolean searchOpen = false;
    private String searchQuery = "";
    private int searchSelectedIndex = 0;
    private List<SearchResultItem> searchResults = List.of();
    private Set<String> searchMatchedNodeIds = Set.of();
    private @Nullable String activeSearchNodeId = null;

    // Interaction state
    private boolean panning = false;
    private boolean rightButtonDown = false;
    private double rightClickStartX = 0.0;
    private double rightClickStartY = 0.0;
    private boolean draggingMinimap = false;
    private boolean draggingNodes = false;
    private boolean drawingMarquee = false;
    private @Nullable PlanGraph preDragGraphSnapshot;
    private double dragAccumDx = 0.0;
    private double dragAccumDy = 0.0;
    private double lastMouseX = 0.0;
    private double lastMouseY = 0.0;
    private double marqueeStartWx = 0.0;
    private double marqueeStartWy = 0.0;
    private @Nullable PortHit wiringSourcePort;

    private long lastClickTimeMs = 0L;
    private @Nullable String lastClickNodeId;

    private @Nullable PlannerModal activeModal;
    private @Nullable ContextMenu activeContextMenu;
    private @Nullable String toastText;
    private long toastExpiresAtMs = 0L;
    private boolean initialFitDone = false;
    private @Nullable PlanGraph copiedSubgraph;
    private int pasteSequence = 0;

    public PlannerScreen() {
        super(Component.translatable("planomega.title"));
        PlanGraph initial = PlanStorage.loadAutosaveOrDefault();
        PlanTab firstTab = new PlanTab(initial);
        openTabs.add(firstTab);
        activeTabIndex = 0;
        this.graph = initial;
        this.history = firstTab.history;
        this.selectedNodeIds = firstTab.selectedNodeIds;
        recomputeAnalysis();
    }

    private void recomputeAnalysis() {
        this.solveResult = ProductionSolver.solve(this.graph);
        this.auditReport = ChainAuditor.audit(this.graph, this.solveResult);
    }

    @Override
    protected void init() {
        updateViewportBounds();
        rebuildToolbarButtons();
        if (activeModal != null) {
            activeModal.init(this.font, this.width, this.height);
        }
        if (!initialFitDone) {
            fitGraphToView();
            initialFitDone = true;
        }
        consumePendingXeiQueue();
    }

    @Override
    public void tick() {
        super.tick();
        if (activeModal != null) {
            activeModal.tick();
            if (activeModal.isClosed()) {
                activeModal = null;
            }
        }
        consumePendingXeiQueue();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        PlanStorage.saveAutosave(graph);
        super.onClose();
    }

    private record ButtonSpec(ResourceLocation icon, String langKey, String shortcut, int accent, boolean active, Runnable action) {
    }

    private void rebuildToolbarButtons() {
        toolbarButtons.clear();

        List<ButtonSpec> leftSpecs = List.of(
                new ButtonSpec(Theme.ICON_RECIPE, "planomega.toolbar.add_recipe", "R", Theme.ACCENT_TEAL, false, this::openRecipeSearch),
                new ButtonSpec(Theme.ICON_NOTE, "planomega.toolbar.add_note", "", Theme.ACCENT_AMBER, false, this::createStickyNote),
                new ButtonSpec(Theme.ICON_SANDBOX, "planomega.toolbar.add_sandbox", "", Theme.ACCENT_AMBER, false, this::createVirtualNode),
                new ButtonSpec(Theme.ICON_GROUP, "planomega.toolbar.group", "Ctrl+G", Theme.ACCENT_TEAL, false, this::groupSelected),
                new ButtonSpec(Theme.ICON_UNDO, "planomega.toolbar.undo", "Ctrl+Z", Theme.BTN_BORDER, false, this::performUndo),
                new ButtonSpec(Theme.ICON_REDO, "planomega.toolbar.redo", "Ctrl+Y", Theme.BTN_BORDER, false, this::performRedo),
                new ButtonSpec(Theme.ICON_GRID, "planomega.toolbar.grid", "G", Theme.ACCENT_TEAL, showGrid, () -> {
                    showGrid = !showGrid;
                    rebuildToolbarButtons();
                }),
                new ButtonSpec(Theme.ICON_SNAP, "planomega.toolbar.snap", "", Theme.ACCENT_TEAL, snapToGrid, () -> {
                    snapToGrid = !snapToGrid;
                    rebuildToolbarButtons();
                }),
                new ButtonSpec(Theme.ICON_FIT, "planomega.toolbar.fit", "F", Theme.BTN_BORDER, false, this::fitGraphToView),
                new ButtonSpec(Theme.ICON_LAYOUT, "planomega.toolbar.layout", "L", Theme.ACCENT_TEAL, false, this::performAutoLayout),
                new ButtonSpec(Theme.ICON_LAYOUT, "planomega.toolbar.auto_connect", "", Theme.ACCENT_TEAL, false, () -> performAutoConnect(false)),
                new ButtonSpec(Theme.ICON_GRID, "planomega.toolbar.routing", "O", Theme.ACCENT_TEAL, orthogonalRouting, this::toggleRoutingMode)
        );

        List<ButtonSpec> rightSpecs = List.of(
                new ButtonSpec(Theme.ICON_GROUP, "planomega.toolbar.templates", "", Theme.ACCENT_TEAL, false, this::openTemplatesModal),
                new ButtonSpec(Theme.ICON_EXPORT, "planomega.toolbar.export", "E", Theme.ACCENT_AMBER, false, this::openExportModal),
                new ButtonSpec(Theme.ICON_SUMMARY, "planomega.toolbar.summary", "Tab", Theme.ACCENT_TEAL, summaryOpen, () -> {
                    summaryOpen = !summaryOpen;
                    updateViewportBounds();
                    rebuildToolbarButtons();
                }),
                new ButtonSpec(Theme.ICON_PLANS, "planomega.toolbar.plans", "", Theme.ACCENT_TEAL, false, this::openPlansModal)
        );

        int gap = 3;
        int h = 20;
        int fullLeftW = measureSpecsWidth(leftSpecs, false, gap);
        int fullRightW = measureSpecsWidth(rightSpecs, false, gap);
        int compactLeftW = measureSpecsWidth(leftSpecs, true, gap);
        int compactRightW = measureSpecsWidth(rightSpecs, true, gap);

        int titleStart = 33;
        int fullTitleBudget = 110;
        boolean fitsFullWithTitle = (titleStart + fullTitleBudget + 10 + fullLeftW + 12 + fullRightW + 8) <= this.width;
        boolean fitsFullNoTitle = (34 + fullLeftW + 10 + fullRightW + 8) <= this.width;
        boolean fitsCompactWithTitle = (titleStart + 84 + 8 + compactLeftW + 10 + compactRightW + 8) <= this.width;
        boolean fitsCompactNoTitle = (34 + compactLeftW + 8 + compactRightW + 8) <= this.width;

        if (fitsFullWithTitle || fitsFullNoTitle) {
            toolbarHeight = TOOLBAR_SINGLE_HEIGHT;
            showToolbarTitle = fitsFullWithTitle;
            int lx = showToolbarTitle ? 150 : 34;
            placeSpecs(leftSpecs, lx, 5, h, gap, false);
            placeSpecs(rightSpecs, this.width - fullRightW - 6, 5, h, gap, false);
        } else if (fitsCompactWithTitle || fitsCompactNoTitle) {
            toolbarHeight = TOOLBAR_SINGLE_HEIGHT;
            showToolbarTitle = fitsCompactWithTitle;
            int lx = showToolbarTitle ? 124 : 34;
            placeSpecs(leftSpecs, lx, 5, h, gap, true);
            placeSpecs(rightSpecs, this.width - compactRightW - 6, 5, h, gap, true);
        } else {
            // Very narrow window / GUI Scale 4: wrap into 2 clean rows so buttons never overlap
            toolbarHeight = TOOLBAR_DOUBLE_HEIGHT;
            showToolbarTitle = false;
            placeSpecs(leftSpecs, 34, 4, h, gap, true);
            placeSpecs(rightSpecs, Math.max(8, this.width - compactRightW - 6), 28, h, gap, true);
        }
        updateViewportBounds();
    }

    private int measureSpecsWidth(List<ButtonSpec> specs, boolean compact, int gap) {
        int total = 0;
        for (int i = 0; i < specs.size(); i++) {
            if (i > 0) {
                total += gap;
            }
            total += buttonWidth(specs.get(i), compact);
        }
        return total;
    }

    private int buttonWidth(ButtonSpec spec, boolean compact) {
        if (compact) {
            return 22;
        }
        String label = Component.translatable(spec.langKey()).getString();
        return Math.max(36, 12 + 4 + this.font.width(label) + 10);
    }

    private void placeSpecs(List<ButtonSpec> specs, int startX, int y, int h, int gap, boolean compact) {
        int x = startX;
        for (ButtonSpec spec : specs) {
            String label = Component.translatable(spec.langKey()).getString();
            String tooltip = spec.shortcut().isEmpty() ? label : label + " (" + spec.shortcut() + ")";
            int w = buttonWidth(spec, compact);
            toolbarButtons.add(new ToolbarButton(x, y, w, h, spec.icon(), compact ? "" : label, tooltip, spec.accent(), spec.active(), spec.action()));
            x += w + gap;
        }
    }

    private void updateViewportBounds() {
        int topOffset = toolbarHeight + TAB_BAR_HEIGHT;
        int canvasW = summaryOpen ? Math.max(200, this.width - SummaryDrawer.WIDTH) : this.width;
        viewport.setBounds(0, topOffset, canvasW, Math.max(1, this.height - topOffset - STATUS_HEIGHT));
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        updateViewportBounds();

        double mwx = viewport.screenToWorldX(mouseX);
        double mwy = viewport.screenToWorldY(mouseY);

        MinimapBounds minimap = CanvasRenderer.computeMinimapBounds(graph, viewport);
        boolean overMinimap = minimap != null && minimap.contains(mouseX, mouseY);
        boolean overSearch = searchOpen && isMouseOverSearchOverlay(mouseX, mouseY);
        boolean canHoverCanvas = activeModal == null && activeContextMenu == null && !overMinimap && !overSearch && viewport.isPointInside(mouseX, mouseY);

        ProcessNode hoveredAuditNode = canHoverCanvas
                ? CanvasGeometry.findAuditBadgeNodeAt(graph, auditReport, this.font, mwx, mwy) : null;
        PortHit hoveredPort = (canHoverCanvas && hoveredAuditNode == null)
                ? CanvasGeometry.findPortAt(graph, mwx, mwy) : null;
        PlanNode hoveredNode = (canHoverCanvas && hoveredAuditNode == null && hoveredPort == null)
                ? CanvasGeometry.findNodeAt(graph, mwx, mwy) : null;
        PlanEdge hoveredEdge = (canHoverCanvas && hoveredAuditNode == null && hoveredPort == null && hoveredNode == null)
                ? CanvasGeometry.findEdgeAt(graph, mwx, mwy, orthogonalRouting) : null;

        double[] marquee = drawingMarquee ? new double[]{marqueeStartWx, marqueeStartWy, mwx, mwy} : null;

        CanvasRenderer.render(gfx, this.font, new RenderContext(
                graph,
                viewport,
                selectedNodeIds,
                selectedEdgeId,
                hoveredNode != null ? hoveredNode.id() : null,
                hoveredEdge != null ? hoveredEdge.id() : null,
                hoveredPort,
                wiringSourcePort,
                mwx,
                mwy,
                showGrid,
                orthogonalRouting,
                marquee,
                solveResult,
                auditReport,
                searchOpen ? searchMatchedNodeIds : Set.of(),
                searchOpen ? activeSearchNodeId : null
        ));

        if (summaryOpen) {
            int topOffset = toolbarHeight + TAB_BAR_HEIGHT;
            summaryDrawer.render(gfx, this.font, graph, solveResult, auditReport,
                    viewport.viewWidth(), topOffset,
                    this.height - topOffset - STATUS_HEIGHT,
                    mouseX, mouseY);
        }

        renderToolbar(gfx, mouseX, mouseY);
        renderTabBar(gfx, mouseX, mouseY);
        renderStatusBar(gfx, mwx, mwy);
        if (searchOpen) {
            renderSearchOverlay(gfx, mouseX, mouseY);
        }
        renderToast(gfx);

        if (activeContextMenu != null) {
            activeContextMenu.render(gfx, this.font, mouseX, mouseY);
        }

        if (activeModal != null) {
            activeModal.render(gfx, this.font, this.width, this.height, mouseX, mouseY, partialTick);
            if (activeModal.isClosed()) {
                activeModal = null;
            }
        } else if (activeContextMenu == null) {
            renderHoverTooltips(gfx, mouseX, mouseY, hoveredAuditNode, hoveredPort);
        }
    }

    private void renderToolbar(GuiGraphics gfx, int mouseX, int mouseY) {
        gfx.fill(0, 0, this.width, toolbarHeight, Theme.BAR_BG);
        gfx.fill(0, toolbarHeight - 1, this.width, toolbarHeight, Theme.BAR_BORDER);

        gfx.blit(Theme.ICON_64, 8, 5, 0, 0, 20, 20, 20, 20);
        if (showToolbarTitle) {
            String name = this.font.plainSubstrByWidth(graph.name(), 108);
            gfx.drawString(this.font, name, 33, 11, Theme.TEXT_PRIMARY, false);
        }

        for (ToolbarButton btn : toolbarButtons) {
            btn.render(gfx, this.font, mouseX, mouseY);
        }
    }

    private void renderStatusBar(GuiGraphics gfx, double mwx, double mwy) {
        int top = this.height - STATUS_HEIGHT;
        gfx.fill(0, top, this.width, this.height, Theme.BAR_BG);
        gfx.fill(0, top, this.width, top + 1, Theme.BAR_BORDER);

        String leftStats = String.format("X: %.0f  Y: %.0f  |  Zoom: %.0f%%  |  Nodes: %d  Edges: %d  Sel: %d",
                mwx, mwy, viewport.zoom() * 100.0,
                graph.nodes().size(), graph.edges().size(), selectedNodeIds.size());
        gfx.drawString(this.font, leftStats, 10, top + 6, Theme.TEXT_SECONDARY, false);

        String hint = Component.translatable("planomega.status.hints").getString();
        int hintW = this.font.width(hint);
        if (hintW + this.font.width(leftStats) + 30 < this.width) {
            gfx.drawString(this.font, hint, this.width - hintW - 10, top + 6, Theme.TEXT_MUTED, false);
        }
    }

    private void renderToast(GuiGraphics gfx) {
        if (toastText == null || System.currentTimeMillis() > toastExpiresAtMs) {
            return;
        }
        int tw = this.font.width(toastText) + 20;
        int x = (this.width - tw) / 2;
        int y = toolbarHeight + TAB_BAR_HEIGHT + (searchOpen ? 36 : 10);
        gfx.fill(x, y, x + tw, y + 20, 0xEE102A2E);
        gfx.fill(x, y, x + tw, y + 1, Theme.ACCENT_TEAL);
        gfx.fill(x, y + 19, x + tw, y + 20, Theme.ACCENT_TEAL);
        gfx.drawString(this.font, toastText, x + 10, y + 6, Theme.TEXT_PRIMARY, false);
    }

    private void renderHoverTooltips(
            GuiGraphics gfx,
            int mouseX,
            int mouseY,
            @Nullable ProcessNode hoveredAuditNode,
            @Nullable PortHit hoveredPort
    ) {
        if (mouseY < toolbarHeight) {
            for (ToolbarButton btn : toolbarButtons) {
                if (btn.isHovered(mouseX, mouseY) && !btn.tooltip().isEmpty()) {
                    gfx.renderTooltip(this.font, Component.literal(btn.tooltip()), mouseX, mouseY);
                    return;
                }
            }
            return;
        }

        if (hoveredAuditNode != null && auditReport != null) {
            List<FrictionFlag> flags = auditReport.forNode(hoveredAuditNode.id());
            if (!flags.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (int i = 0; i < flags.size(); i++) {
                    FrictionFlag flag = flags.get(i);
                    if (i > 0) {
                        lines.add(Component.literal(" "));
                    }
                    lines.add(Component.literal("[" + flag.severity().name() + "] " + flag.title())
                            .withStyle(Style.EMPTY.withColor(flag.severity().color()).withBold(true)));
                    lines.add(Component.literal(flag.detail()).withStyle(ChatFormatting.GRAY));
                    if (flag.aiHint() != null && !flag.aiHint().isBlank()) {
                        lines.add(Component.literal("AI Hint: " + flag.aiHint())
                                .withStyle(Style.EMPTY.withColor(Theme.ACCENT_TEAL)));
                    }
                }
                gfx.renderComponentTooltip(this.font, lines, mouseX, mouseY);
                return;
            }
        }

        if (hoveredPort != null) {
            ResourcePort port = hoveredPort.port();
            ResourceKey key = port.resource();
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal(port.displayLabel() + " ")
                    .withStyle(Style.EMPTY.withColor(Theme.TEXT_PRIMARY).withBold(true))
                    .append(Component.literal("[" + key.kind().name() + "]")
                            .withStyle(Style.EMPTY.withColor(key.kind().color()))));
            lines.add(Component.literal("ID: " + key.id()).withStyle(ChatFormatting.DARK_GRAY));

            String unit = key.kind() == ResourceKind.FLUID ? " mB/craft"
                    : (key.kind() == ResourceKind.ENERGY ? " EU/craft" : "x/craft");
            lines.add(Component.literal("Amount: " + TOOLTIP_FMT.format(port.amount()) + unit)
                    .withStyle(ChatFormatting.GRAY));

            PlanNode host = graph.nodes().get(hoveredPort.nodeId());
            int nodeTier = (host instanceof ProcessNode proc) ? proc.tier() : VoltageTier.ULV.index();
            double effectiveChance = Math.min(1.0,
                    port.chance() + Math.max(0, nodeTier - VoltageTier.ULV.index()) * port.tierChanceBoost());

            if (solveResult != null && solveResult.isSolved()) {
                NodeSolveStats stats = solveResult.nodeStats().get(hoveredPort.nodeId());
                if (stats != null && stats.craftsPerSecond() > 1e-6) {
                    double ratePerSec = stats.craftsPerSecond() * port.amount()
                            * (port.direction() == PortDirection.OUTPUT ? effectiveChance : 1.0);
                    String rateUnit = key.kind() == ResourceKind.FLUID ? " mB/s"
                            : (key.kind() == ResourceKind.ENERGY ? " EU/t" : "/s");
                    lines.add(Component.literal("Solved Rate: " + TOOLTIP_FMT.format(ratePerSec) + rateUnit)
                            .withStyle(Style.EMPTY.withColor(Theme.ACCENT_TEAL)));
                }
            }

            if (port.chance() < 0.9999 || port.tierChanceBoost() > 0.0) {
                String chanceStr = String.format("Chance: %s%%", TOOLTIP_FMT.format(port.chance() * 100.0));
                if (port.tierChanceBoost() > 0.0) {
                    chanceStr += String.format(" (+%s%%/tier, eff %s%%)",
                            TOOLTIP_FMT.format(port.tierChanceBoost() * 100.0),
                            TOOLTIP_FMT.format(effectiveChance * 100.0));
                }
                lines.add(Component.literal(chanceStr).withStyle(Style.EMPTY.withColor(Theme.ACCENT_AMBER)));
            }

            if (port.catalyst()) {
                lines.add(Component.literal("Catalyst (not consumed)")
                        .withStyle(Style.EMPTY.withColor(0xFFC4B5FD)));
            }

            if (Ae2Bridge.isAvailable()) {
                long stored = Ae2Bridge.getStoredAmount(key);
                boolean craftable = Ae2Bridge.hasAutocrafting(key);
                if (stored > 0 || craftable) {
                    String ae2Text = "AE2: " + stored + (craftable ? " [Autocraft]" : "");
                    lines.add(Component.literal(ae2Text)
                            .withStyle(Style.EMPTY.withColor(craftable ? Theme.ACCENT_TEAL : Theme.TEXT_SECONDARY)));
                }
            }

            gfx.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeModal != null) {
            boolean handled = activeModal.mouseClicked(mouseX, mouseY, button, this.width, this.height);
            if (activeModal.isClosed()) {
                activeModal = null;
            }
            return handled;
        }

        if (activeContextMenu != null) {
            if (button == 0 && activeContextMenu.mouseClicked(mouseX, mouseY)) {
                activeContextMenu = null;
                return true;
            }
            activeContextMenu = null;
            if (button == 0) {
                return true;
            }
        }

        lastMouseX = mouseX;
        lastMouseY = mouseY;

        // Toolbar click
        if (button == 0 && mouseY < toolbarHeight) {
            for (ToolbarButton btn : toolbarButtons) {
                if (btn.isHovered(mouseX, mouseY)) {
                    btn.action.run();
                    return true;
                }
            }
            return true;
        }

        // Tab bar click
        if (button == 0 && mouseY >= toolbarHeight && mouseY < toolbarHeight + TAB_BAR_HEIGHT) {
            if (handleTabBarClick(mouseX, mouseY)) {
                return true;
            }
            return true;
        }

        // Floating search overlay click
        if (searchOpen) {
            if (handleSearchOverlayClick(mouseX, mouseY, button)) {
                return true;
            }
        }

        int topOffset = toolbarHeight + TAB_BAR_HEIGHT;
        if (summaryOpen && mouseX >= viewport.viewWidth() && mouseY >= topOffset && mouseY < this.height - STATUS_HEIGHT) {
            summaryDrawer.mouseClicked(mouseX, mouseY, button, this::focusNode);
            return true;
        }

        if (!viewport.isPointInside(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // Minimap click -> move camera
        if (button == 0) {
            MinimapBounds mm = CanvasRenderer.computeMinimapBounds(graph, viewport);
            if (mm != null && mm.contains(mouseX, mouseY)) {
                draggingMinimap = true;
                viewport.setCamera(mm.screenToWorldX(mouseX), mm.screenToWorldY(mouseY));
                return true;
            }
        }

        // Right click -> track for drag-pan vs click-context-menu
        if (button == 1) {
            rightButtonDown = true;
            rightClickStartX = mouseX;
            rightClickStartY = mouseY;
            return true;
        }

        // Middle click or Space + Left click -> Pan
        boolean spaceHeld = GLFW.glfwGetKey(this.minecraft.getWindow().getWindow(), GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
        if (button == 2 || (button == 0 && spaceHeld)) {
            panning = true;
            return true;
        }

        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        double wx = viewport.screenToWorldX(mouseX);
        double wy = viewport.screenToWorldY(mouseY);

        // 1. Port wiring start
        PortHit portHit = CanvasGeometry.findPortAt(graph, wx, wy);
        if (portHit != null) {
            wiringSourcePort = portHit;
            selectedEdgeId = null;
            return true;
        }

        // 2. Node hit
        PlanNode nodeHit = CanvasGeometry.findNodeAt(graph, wx, wy);
        if (nodeHit != null) {
            // Check Group collapse toggle in header left area
            if (nodeHit instanceof GroupNode group
                    && wy <= group.y() + GroupNode.HEADER_HEIGHT
                    && wx <= group.x() + 26.0) {
                commitMutation(graph.toggleGroupCollapsed(group.id()));
                return true;
            }

            long now = System.currentTimeMillis();
            boolean isDoubleClick = nodeHit.id().equals(lastClickNodeId) && (now - lastClickTimeMs) < 350L;
            lastClickTimeMs = now;
            lastClickNodeId = nodeHit.id();

            if (isDoubleClick) {
                openEditorForNode(nodeHit);
                return true;
            }

            selectedEdgeId = null;
            if (hasShiftDown()) {
                if (!selectedNodeIds.remove(nodeHit.id())) {
                    selectedNodeIds.add(nodeHit.id());
                }
            } else if (!selectedNodeIds.contains(nodeHit.id())) {
                selectedNodeIds.clear();
                selectedNodeIds.add(nodeHit.id());
            }

            draggingNodes = true;
            preDragGraphSnapshot = graph;
            dragAccumDx = 0.0;
            dragAccumDy = 0.0;
            return true;
        }

        // 3. Edge hit
        PlanEdge edgeHit = CanvasGeometry.findEdgeAt(graph, wx, wy, orthogonalRouting);
        if (edgeHit != null) {
            selectedNodeIds.clear();
            selectedEdgeId = edgeHit.id();
            return true;
        }

        // 4. Empty canvas -> start marquee selection
        if (!hasShiftDown()) {
            selectedNodeIds.clear();
        }
        selectedEdgeId = null;
        drawingMarquee = true;
        marqueeStartWx = wx;
        marqueeStartWy = wy;
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (activeModal != null) {
            return true;
        }

        double dx = mouseX - lastMouseX;
        double dy = mouseY - lastMouseY;
        lastMouseX = mouseX;
        lastMouseY = mouseY;

        if (draggingMinimap) {
            MinimapBounds mm = CanvasRenderer.computeMinimapBounds(graph, viewport);
            if (mm != null) {
                viewport.setCamera(mm.screenToWorldX(mouseX), mm.screenToWorldY(mouseY));
            }
            return true;
        }

        if (rightButtonDown && !panning && Math.hypot(mouseX - rightClickStartX, mouseY - rightClickStartY) > 4.0) {
            panning = true;
            activeContextMenu = null;
        }

        if (panning) {
            viewport.panByScreenDelta(dx, dy);
            return true;
        }

        if (draggingNodes && preDragGraphSnapshot != null && !selectedNodeIds.isEmpty()) {
            dragAccumDx += dx / viewport.zoom();
            dragAccumDy += dy / viewport.zoom();
            double stepDx = CanvasViewport.snap(dragAccumDx, snapToGrid);
            double stepDy = CanvasViewport.snap(dragAccumDy, snapToGrid);
            graph = preDragGraphSnapshot.moveNodes(selectedNodeIds, stepDx, stepDy);
            return true;
        }

        if (wiringSourcePort != null || drawingMarquee) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (activeModal != null) {
            return true;
        }

        if (button == 0) {
            draggingMinimap = false;
        }

        if (button == 1) {
            boolean wasPanning = panning;
            rightButtonDown = false;
            panning = false;
            if (!wasPanning && viewport.isPointInside(mouseX, mouseY)) {
                openContextMenuAt((int) Math.round(mouseX), (int) Math.round(mouseY));
            }
            return true;
        }

        panning = false;

        if (draggingNodes) {
            draggingNodes = false;
            if (preDragGraphSnapshot != null && !preDragGraphSnapshot.equals(graph)) {
                history.recordBeforeEdit(preDragGraphSnapshot);
                PlanStorage.saveAutosave(graph);
            }
            preDragGraphSnapshot = null;
            return true;
        }

        if (wiringSourcePort != null) {
            double wx = viewport.screenToWorldX(mouseX);
            double wy = viewport.screenToWorldY(mouseY);
            PortHit target = CanvasGeometry.findPortAt(graph, wx, wy);
            PortHit source = wiringSourcePort;
            wiringSourcePort = null;
            if (target != null && target.nodeId().equals(source.nodeId()) && target.port().id().equals(source.port().id())) {
                openRecipeSearchForPort(source);
            } else if (target != null && !target.nodeId().equals(source.nodeId())) {
                tryConnectPorts(source, target);
            }
            return true;
        }

        if (drawingMarquee) {
            drawingMarquee = false;
            double wx = viewport.screenToWorldX(mouseX);
            double wy = viewport.screenToWorldY(mouseY);
            double minX = Math.min(marqueeStartWx, wx);
            double minY = Math.min(marqueeStartWy, wy);
            double maxX = Math.max(marqueeStartWx, wx);
            double maxY = Math.max(marqueeStartWy, wy);

            if (Math.hypot(maxX - minX, maxY - minY) > 4.0) {
                for (PlanNode node : graph.nodes().values()) {
                    if ((node instanceof GroupNode g && !g.collapsed()) || graph.isHiddenByCollapsedGroup(node.id())) {
                        continue;
                    }
                    if (CanvasGeometry.intersectsRect(node, minX, minY, maxX, maxY)) {
                        selectedNodeIds.add(node.id());
                    }
                }
            }
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (activeModal != null) {
            return activeModal.mouseScrolled(delta);
        }
        if (viewport.isPointInside(mouseX, mouseY)) {
            double factor = delta > 0 ? 1.12 : (1.0 / 1.12);
            viewport.zoomAtScreen(mouseX, mouseY, factor);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (activeModal != null) {
            boolean handled = activeModal.keyPressed(keyCode, scanCode, modifiers);
            if (activeModal.isClosed()) {
                activeModal = null;
            }
            return handled;
        }

        if (activeContextMenu != null && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            activeContextMenu = null;
            return true;
        }

        boolean ctrl = hasControlDown();
        if (searchOpen) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                closeSearch();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (!searchResults.isEmpty()) {
                    jumpToSearchResult(searchSelectedIndex, true);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP && !searchResults.isEmpty()) {
                searchSelectedIndex = (searchSelectedIndex - 1 + searchResults.size()) % searchResults.size();
                activeSearchNodeId = searchResults.get(searchSelectedIndex).nodeId();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_DOWN && !searchResults.isEmpty()) {
                searchSelectedIndex = (searchSelectedIndex + 1) % searchResults.size();
                activeSearchNodeId = searchResults.get(searchSelectedIndex).nodeId();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (ctrl) {
                    searchQuery = "";
                } else if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                }
                refreshSearchResults();
                return true;
            }
            if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
                return true;
            }
            return true;
        }

        if (ctrl && keyCode == GLFW.GLFW_KEY_F) {
            openSearch();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_N) {
            openNewTab();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_W) {
            if (openTabs.size() > 1) {
                closeTab(activeTabIndex);
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_TAB && openTabs.size() > 1) {
            int next = hasShiftDown()
                    ? (activeTabIndex - 1 + openTabs.size()) % openTabs.size()
                    : (activeTabIndex + 1) % openTabs.size();
            switchToTab(next);
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Z) {
            if (hasShiftDown()) {
                performRedo();
            } else {
                performUndo();
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_Y) {
            performRedo();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_A) {
            selectedNodeIds.clear();
            for (PlanNode node : graph.nodes().values()) {
                if (!graph.isHiddenByCollapsedGroup(node.id())) {
                    selectedNodeIds.add(node.id());
                }
            }
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_G) {
            groupSelected();
            return true;
        }
        if (ctrl && hasShiftDown() && keyCode == GLFW.GLFW_KEY_C) {
            copyBlueprintToClipboard();
            return true;
        }
        if (ctrl && hasShiftDown() && keyCode == GLFW.GLFW_KEY_V) {
            pasteBlueprintFromClipboard();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_C) {
            copySelection();
            return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_V) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            if ((copiedSubgraph == null || copiedSubgraph.nodes().isEmpty()) && BlueprintCodec.isBlueprint(clip)) {
                pasteBlueprintFromClipboard();
            } else {
                pasteSelection();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            deleteSelection();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F && !ctrl) {
            fitGraphToView();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_L && !ctrl) {
            performAutoLayout();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_O && !ctrl) {
            toggleRoutingMode();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R && !ctrl) {
            openRecipeSearch();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_T && !ctrl) {
            openTargetConfigForSelection();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_E && !ctrl) {
            openExportModal();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && !ctrl) {
            summaryOpen = !summaryOpen;
            updateViewportBounds();
            rebuildToolbarButtons();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_G && !ctrl) {
            showGrid = !showGrid;
            rebuildToolbarButtons();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (activeModal != null) {
            return activeModal.charTyped(codePoint, modifiers);
        }
        if (searchOpen) {
            if (codePoint >= 32 && !hasControlDown()) {
                searchQuery += codePoint;
                refreshSearchResults();
            }
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    private void tryConnectPorts(PortHit a, PortHit b) {
        PortHit out = a.port().direction() == PortDirection.OUTPUT ? a : b;
        PortHit in = a.port().direction() == PortDirection.INPUT ? a : b;
        if (!graph.canConnect(out.nodeId(), out.port().id(), in.nodeId(), in.port().id())) {
            showToast(Component.translatable("planomega.toast.incompatible_ports").getString());
            return;
        }
        PlanEdge edge = new PlanEdge(
                PlanGraph.newId("edge"),
                out.nodeId(),
                out.port().id(),
                in.nodeId(),
                in.port().id(),
                out.port().resource(),
                true
        );
        commitMutation(graph.withEdge(edge));
    }

    private void openRecipeSearch() {
        openModal(new RecipeSearchModal(null, null, "", this::insertRecipeAtCenter));
    }

    private void openRecipeSearchForPort(PortHit portHit) {
        PlanNode host = graph.nodes().get(portHit.nodeId());
        if (host == null) {
            return;
        }
        boolean needsProducer = portHit.port().direction() == PortDirection.INPUT;
        ResourceKey filterKey = portHit.port().resource();
        openModal(new RecipeSearchModal(
                needsProducer ? filterKey : null,
                needsProducer ? null : filterKey,
                "",
                entry -> insertAndWireRecipe(entry, host, portHit, needsProducer)
        ));
    }

    private void insertRecipeAtCenter(RecipeEntry entry) {
        double wx = CanvasViewport.snap(viewport.camX() - 105.0, snapToGrid);
        double wy = CanvasViewport.snap(viewport.camY() - 55.0, snapToGrid);
        RecipeNode node = entry.toRecipeNode(wx, wy);
        commitMutation(graph.withNode(node));
        selectedNodeIds.clear();
        selectedNodeIds.add(node.id());
        showToast(Component.translatable("planomega.toast.added_recipe", entry.title()).getString());
    }

    private void insertAndWireRecipe(RecipeEntry entry, PlanNode anchor, PortHit anchorPort, boolean anchorIsInput) {
        double offsetX = anchorIsInput ? -270.0 : anchor.width() + 60.0;
        double wx = CanvasViewport.snap(anchor.x() + offsetX, snapToGrid);
        double wy = CanvasViewport.snap(anchorPort.worldPos().y() - 48.0, snapToGrid);
        RecipeNode newNode = entry.toRecipeNode(wx, wy);
        PlanGraph next = graph.withNode(newNode);

        List<ResourcePort> candidates = anchorIsInput ? newNode.outputs() : newNode.inputs();
        ResourceKey targetKey = anchorPort.port().resource();
        ResourcePort matched = null;
        for (ResourcePort candidate : candidates) {
            if (candidate.resource().equals(targetKey)) {
                matched = candidate;
                break;
            }
        }
        if (matched == null) {
            for (ResourcePort candidate : candidates) {
                if (candidate.resource().isCompatibleWith(targetKey)) {
                    matched = candidate;
                    break;
                }
            }
        }
        if (matched != null) {
            String fromNode = anchorIsInput ? newNode.id() : anchor.id();
            String fromPort = anchorIsInput ? matched.id() : anchorPort.port().id();
            String toNode = anchorIsInput ? anchor.id() : newNode.id();
            String toPort = anchorIsInput ? anchorPort.port().id() : matched.id();
            if (next.canConnect(fromNode, fromPort, toNode, toPort)) {
                next = next.withEdge(new PlanEdge(
                        PlanGraph.newId("edge"),
                        fromNode, fromPort, toNode, toPort,
                        matched.resource(),
                        true
                ));
            }
        }
        commitMutation(next);
        selectedNodeIds.clear();
        selectedNodeIds.add(newNode.id());
    }

    /**
     * Handles an item or recipe hovered in EMI or JEI when the user triggers {@code ADD_FROM_XEI}.
     */
    public void handleXeiLookup(IntegrationBridge.XeiLookup lookup) {
        RecipeCatalog catalog = RecipeCatalogManager.get();
        if (lookup.recipeId() != null) {
            RecipeEntry matched = catalog.findById(lookup.recipeId()).orElse(lookup.fallbackRecipe());
            if (matched != null) {
                insertRecipeAtCenter(matched);
                return;
            }
        }
        if (lookup.resourceKey() != null) {
            openModal(new RecipeSearchModal(lookup.resourceKey(), null, "", this::insertRecipeAtCenter));
        }
    }

    private void createStickyNote() {
        double wx = CanvasViewport.snap(viewport.camX() - StickyNoteNode.DEFAULT_WIDTH * 0.5, snapToGrid);
        double wy = CanvasViewport.snap(viewport.camY() - StickyNoteNode.DEFAULT_HEIGHT * 0.5, snapToGrid);
        StickyNoteNode note = new StickyNoteNode(
                PlanGraph.newId("note"),
                wx, wy,
                StickyNoteNode.DEFAULT_WIDTH,
                StickyNoteNode.DEFAULT_HEIGHT,
                null,
                "Note",
                "Double-click to edit",
                StickyNoteNode.DEFAULT_COLOR
        );
        commitMutation(graph.withNode(note));
        selectedNodeIds.clear();
        selectedNodeIds.add(note.id());
        openEditorForNode(note);
    }

    private void createVirtualNode() {
        double wx = CanvasViewport.snap(viewport.camX() - 105.0, snapToGrid);
        double wy = CanvasViewport.snap(viewport.camY() - 55.0, snapToGrid);
        VirtualRecipeNode draft = VirtualRecipeNode.createDraft(
                PlanGraph.newId("sim"),
                wx, wy,
                "What-If Step",
                "planomega:virtual",
                List.of(ResourcePort.input("in_0", ResourceKey.item("minecraft:iron_ingot"), 1)),
                List.of(ResourcePort.output("out_0", ResourceKey.item("gtceu:steel_ingot"), 1)),
                30,
                100
        );
        commitMutation(graph.withNode(draft));
        selectedNodeIds.clear();
        selectedNodeIds.add(draft.id());
        openEditorForNode(draft);
    }

    private void groupSelected() {
        if (selectedNodeIds.isEmpty()) {
            showToast(Component.translatable("planomega.toast.select_to_group").getString());
            return;
        }
        PlanGraph next = graph.groupNodes("Group", selectedNodeIds, GroupNode.DEFAULT_COLOR);
        if (!next.equals(graph)) {
            commitMutation(next);
            showToast(Component.translatable("planomega.toast.grouped").getString());
        }
    }

    private void deleteSelection() {
        if (!selectedNodeIds.isEmpty()) {
            commitMutation(graph.withoutNodes(selectedNodeIds));
            selectedNodeIds.clear();
        } else if (selectedEdgeId != null) {
            commitMutation(graph.withoutEdge(selectedEdgeId));
            selectedEdgeId = null;
        }
    }

    private void openEditorForNode(PlanNode node) {
        if (node instanceof StickyNoteNode note) {
            openModal(new PlannerModal.NoteEditorModal(note, updated -> commitMutation(graph.withNode(updated))));
        } else if (node instanceof VirtualRecipeNode virtual) {
            openModal(new PlannerModal.VirtualNodeEditorModal(virtual, updated -> commitMutation(graph.withNode(updated))));
        } else if (node instanceof RecipeNode recipe) {
            openModal(new PlannerModal.NodeConfigModal(
                    recipe,
                    updated -> commitMutation(graph.withNode(updated)),
                    this::cloneToSandboxAndEdit
            ));
        } else if (node instanceof GroupNode group) {
            openModal(new PlannerModal.GroupRenameModal(group, updated -> commitMutation(graph.withNode(updated))));
        }
    }

    private void openTargetConfigForSelection() {
        for (String id : selectedNodeIds) {
            PlanNode node = graph.nodes().get(id);
            if (node instanceof com.typeomega.planomega.model.ProcessNode proc) {
                openModal(new PlannerModal.NodeConfigModal(
                        proc,
                        updated -> commitMutation(graph.withNode(updated)),
                        this::cloneToSandboxAndEdit
                ));
                return;
            }
        }
    }

    private void cloneToSandboxAndEdit(VirtualRecipeNode cloned) {
        commitMutation(graph.withNode(cloned));
        selectedNodeIds.clear();
        selectedNodeIds.add(cloned.id());
        openModal(new PlannerModal.VirtualNodeEditorModal(cloned, updated -> commitMutation(graph.withNode(updated))));
    }

    private void openExportModal() {
        openModal(new PlannerModal.ExportModal(graph, solveResult, auditReport, this::showToast));
    }

    private void focusNode(String nodeId) {
        PlanNode node = graph.nodes().get(nodeId);
        if (node == null) {
            return;
        }
        selectedNodeIds.clear();
        selectedNodeIds.add(nodeId);
        viewport.fitToBounds(node.x() - 60, node.y() - 60, node.x() + node.width() + 60, node.y() + node.height() + 60);
    }

    private void openPlansModal() {
        openModal(new PlannerModal.PlansManagerModal(
                graph,
                loaded -> openGraphInTab(loaded),
                this::showToast
        ));
    }

    private void openModal(PlannerModal modal) {
        this.activeModal = modal;
        this.activeModal.init(this.font, this.width, this.height);
    }

    private void performUndo() {
        if (!history.canUndo()) {
            return;
        }
        graph = history.undo(graph);
        recomputeAnalysis();
        selectedNodeIds.retainAll(graph.nodes().keySet());
        if (selectedEdgeId != null && !graph.edges().containsKey(selectedEdgeId)) {
            selectedEdgeId = null;
        }
        PlanStorage.saveAutosave(graph);
    }

    private void performRedo() {
        if (!history.canRedo()) {
            return;
        }
        graph = history.redo(graph);
        recomputeAnalysis();
        selectedNodeIds.retainAll(graph.nodes().keySet());
        PlanStorage.saveAutosave(graph);
    }

    private void commitMutation(PlanGraph next) {
        if (!next.equals(graph)) {
            history.recordBeforeEdit(graph);
            graph = next;
            recomputeAnalysis();
            PlanStorage.saveAutosave(graph);
        }
    }

    private void fitGraphToView() {
        if (graph.nodes().isEmpty()) {
            viewport.resetView();
            return;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (PlanNode node : graph.nodes().values()) {
            if (graph.isHiddenByCollapsedGroup(node.id())) {
                continue;
            }
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
            maxX = Math.max(maxX, node.x() + CanvasGeometry.visualWidth(node));
            maxY = Math.max(maxY, node.y() + CanvasGeometry.visualHeight(node));
        }
        viewport.fitToBounds(minX, minY, maxX, maxY);
    }

    private void performAutoLayout() {
        if (graph.nodes().isEmpty()) {
            return;
        }
        PlanGraph laidOut = CanvasGeometry.autoLayoutLeftToRight(graph, snapToGrid);
        commitMutation(laidOut);
        fitGraphToView();
        showToast(Component.translatable("planomega.toast.auto_layout").getString());
    }

    private void performAutoConnect(boolean closeLoops) {
        if (graph.nodes().isEmpty()) {
            return;
        }
        PlanGraph.AutoConnectResult result = graph.autoConnect(closeLoops);
        if (result.connectionsAdded() > 0) {
            commitMutation(result.graph());
            showToast(Component.translatable("planomega.toast.auto_connected", result.connectionsAdded()).getString());
        } else {
            showToast(Component.translatable("planomega.toast.auto_connect_none").getString());
        }
    }

    private void copySelection() {
        if (selectedNodeIds.isEmpty()) {
            showToast(Component.translatable("planomega.toast.nothing_to_copy").getString());
            return;
        }
        copiedSubgraph = graph.extractSubgraph(selectedNodeIds);
        pasteSequence = 0;
        showToast(Component.translatable("planomega.toast.copied_nodes", copiedSubgraph.nodes().size()).getString());
    }

    private void pasteSelection() {
        if (copiedSubgraph == null || copiedSubgraph.nodes().isEmpty()) {
            return;
        }
        pasteSequence++;
        double offset = pasteSequence * 32.0;
        PlanGraph cloned = GroupTemplateCodec.cloneWithFreshIds(copiedSubgraph, offset, offset);
        commitMutation(graph.mergeSubgraph(cloned));
        selectedNodeIds.clear();
        selectedNodeIds.addAll(cloned.nodes().keySet());
        selectedEdgeId = null;
        showToast(Component.translatable("planomega.toast.pasted_nodes", cloned.nodes().size()).getString());
    }

    private void openTemplatesModal() {
        double wx = CanvasViewport.snap(viewport.camX() - 100.0, snapToGrid);
        double wy = CanvasViewport.snap(viewport.camY() - 60.0, snapToGrid);
        openTemplatesModalAt(wx, wy);
    }

    private void openTemplatesModalAt(double wx, double wy) {
        openModal(new PlannerModal.GroupTemplatesModal(
                wx, wy,
                instantiated -> {
                    commitMutation(graph.mergeSubgraph(instantiated));
                    selectedNodeIds.clear();
                    selectedNodeIds.addAll(instantiated.nodes().keySet());
                    selectedEdgeId = null;
                },
                this::showToast
        ));
    }

    private boolean isPortConnected(PortHit portHit) {
        boolean isInput = portHit.port().direction() == PortDirection.INPUT;
        for (PlanEdge edge : graph.edges().values()) {
            if (isInput
                    && edge.toNodeId().equals(portHit.nodeId())
                    && edge.toPortId().equals(portHit.port().id())) {
                return true;
            }
            if (!isInput
                    && edge.fromNodeId().equals(portHit.nodeId())
                    && edge.fromPortId().equals(portHit.port().id())) {
                return true;
            }
        }
        return false;
    }

    private void disconnectPort(PortHit portHit) {
        boolean isInput = portHit.port().direction() == PortDirection.INPUT;
        List<String> toRemove = new ArrayList<>();
        for (PlanEdge edge : graph.edges().values()) {
            if (isInput
                    && edge.toNodeId().equals(portHit.nodeId())
                    && edge.toPortId().equals(portHit.port().id())) {
                toRemove.add(edge.id());
            } else if (!isInput
                    && edge.fromNodeId().equals(portHit.nodeId())
                    && edge.fromPortId().equals(portHit.port().id())) {
                toRemove.add(edge.id());
            }
        }
        if (!toRemove.isEmpty()) {
            commitMutation(graph.withoutEdges(toRemove));
        }
    }

    private void openContextMenuAt(int screenX, int screenY) {
        double wx = viewport.screenToWorldX(screenX);
        double wy = viewport.screenToWorldY(screenY);

        List<ContextMenuItem> items = new ArrayList<>();
        PortHit portHit = CanvasGeometry.findPortAt(graph, wx, wy);
        if (portHit != null) {
            boolean connected = isPortConnected(portHit);
            if (portHit.port().direction() == PortDirection.INPUT) {
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.generate_producer").getString(),
                        Theme.ACCENT_TEAL,
                        () -> openRecipeSearchForPort(portHit)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.add_source").getString(),
                        Theme.ACCENT_TEAL,
                        () -> createSourceForPort(portHit)
                ));
            } else {
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.generate_consumer").getString(),
                        Theme.ACCENT_TEAL,
                        () -> openRecipeSearchForPort(portHit)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.add_sink").getString(),
                        0xFFA855F7,
                        () -> createSinkForPort(portHit)
                ));
            }
            if (connected) {
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.disconnect_port").getString(),
                        0xFFEF4444,
                        () -> disconnectPort(portHit)
                ));
            }
            this.activeContextMenu = new ContextMenu(screenX, screenY, items, this.font, this.width, this.height);
            return;
        }

        PlanNode nodeHit = CanvasGeometry.findNodeAt(graph, wx, wy);
        if (nodeHit != null) {
            if (!selectedNodeIds.contains(nodeHit.id())) {
                selectedNodeIds.clear();
                selectedNodeIds.add(nodeHit.id());
            }
            selectedEdgeId = null;

            if (nodeHit instanceof ProcessNode proc) {
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.set_target").getString(),
                        Theme.ACCENT_TEAL,
                        () -> openModal(new PlannerModal.NodeConfigModal(
                                proc,
                                updated -> commitMutation(graph.withNode(updated)),
                                this::cloneToSandboxAndEdit
                        ))
                ));
                if (proc instanceof RecipeNode recipe) {
                    items.add(new ContextMenuItem(
                            Component.translatable("planomega.ctx.switch_recipe").getString(),
                            Theme.ACCENT_TEAL,
                            () -> openRecipeSwitcher(recipe)
                    ));
                    items.add(new ContextMenuItem(
                            Component.translatable("planomega.ctx.clone_sandbox").getString(),
                            Theme.ACCENT_AMBER,
                            () -> cloneNodeToSandbox(recipe)
                    ));
                } else if (proc instanceof VirtualRecipeNode) {
                    items.add(new ContextMenuItem(
                            Component.translatable("planomega.ctx.edit_sandbox").getString(),
                            Theme.ACCENT_AMBER,
                            () -> openEditorForNode(proc)
                    ));
                }
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.group").getString(),
                        Theme.TEXT_PRIMARY,
                        this::groupSelected
                ));
                if (proc.groupId() != null) {
                    items.add(new ContextMenuItem(
                            Component.translatable("planomega.ctx.ungroup").getString(),
                            Theme.TEXT_SECONDARY,
                            () -> ungroupNode(proc.id())
                    ));
                }
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.delete").getString(),
                        0xFFEF4444,
                        this::deleteSelection
                ));
            } else if (nodeHit instanceof GroupNode group) {
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.toggle_collapse").getString(),
                        Theme.ACCENT_TEAL,
                        () -> commitMutation(graph.toggleGroupCollapsed(group.id()))
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.rename_group").getString(),
                        Theme.TEXT_PRIMARY,
                        () -> openEditorForNode(group)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.save_template").getString(),
                        Theme.ACCENT_AMBER,
                        () -> openModal(new PlannerModal.SaveTemplateModal(
                                group.title(),
                                graph.extractSubgraph(List.of(group.id())),
                                this::showToast
                        ))
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.ungroup").getString(),
                        Theme.ACCENT_AMBER,
                        () -> {
                            commitMutation(graph.withoutNode(group.id()));
                            selectedNodeIds.remove(group.id());
                        }
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.delete").getString(),
                        0xFFEF4444,
                        () -> {
                            List<String> toDelete = new ArrayList<>(group.childNodeIds());
                            toDelete.add(group.id());
                            commitMutation(graph.withoutNodes(toDelete));
                            selectedNodeIds.removeAll(toDelete);
                        }
                ));
            } else if (nodeHit instanceof StickyNoteNode note) {
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.edit_note").getString(),
                        Theme.ACCENT_AMBER,
                        () -> openEditorForNode(note)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.group").getString(),
                        Theme.TEXT_PRIMARY,
                        this::groupSelected
                ));
                if (note.groupId() != null) {
                    items.add(new ContextMenuItem(
                            Component.translatable("planomega.ctx.ungroup").getString(),
                            Theme.TEXT_SECONDARY,
                            () -> ungroupNode(note.id())
                    ));
                }
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.delete").getString(),
                        0xFFEF4444,
                        this::deleteSelection
                ));
            }
        } else {
            PlanEdge edgeHit = CanvasGeometry.findEdgeAt(graph, wx, wy, orthogonalRouting);
            if (edgeHit != null) {
                selectedNodeIds.clear();
                selectedEdgeId = edgeHit.id();
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.toggle_routing").getString(),
                        Theme.ACCENT_TEAL,
                        this::toggleRoutingMode
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.delete_edge").getString(),
                        0xFFEF4444,
                        () -> {
                            commitMutation(graph.withoutEdge(edgeHit.id()));
                            selectedEdgeId = null;
                        }
                ));
            } else {
                final double placeX = CanvasViewport.snap(wx, snapToGrid);
                final double placeY = CanvasViewport.snap(wy, snapToGrid);
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.add_recipe").getString(),
                        Theme.ACCENT_TEAL,
                        () -> openModal(new RecipeSearchModal(null, null, "", entry -> {
                            RecipeNode node = entry.toRecipeNode(placeX, placeY);
                            commitMutation(graph.withNode(node));
                            selectedNodeIds.clear();
                            selectedNodeIds.add(node.id());
                            showToast(Component.translatable("planomega.toast.added_recipe", entry.title()).getString());
                        }))
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.add_note").getString(),
                        Theme.ACCENT_AMBER,
                        () -> createStickyNoteAt(placeX, placeY)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.add_sandbox").getString(),
                        Theme.ACCENT_AMBER,
                        () -> createVirtualNodeAt(placeX, placeY)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.add_source").getString(),
                        Theme.ACCENT_TEAL,
                        () -> createSourceNodeAt(placeX, placeY)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.add_sink").getString(),
                        0xFFA855F7,
                        () -> createSinkNodeAt(placeX, placeY)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.insert_template").getString(),
                        Theme.ACCENT_TEAL,
                        () -> openTemplatesModalAt(placeX, placeY)
                ));
                if (IntegrationBridge.hasActiveEmiTree()) {
                    items.add(new ContextMenuItem(
                            Component.translatable("planomega.ctx.import_emi_tree").getString(),
                            Theme.ACCENT_TEAL,
                            this::importActiveEmiTree
                    ));
                }
                if (copiedSubgraph != null && !copiedSubgraph.nodes().isEmpty()) {
                    items.add(new ContextMenuItem(
                            Component.translatable("planomega.ctx.paste").getString(),
                            Theme.TEXT_PRIMARY,
                            this::pasteSelection
                    ));
                }
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.copy_blueprint").getString(),
                        Theme.TEXT_PRIMARY,
                        this::copyBlueprintToClipboard
                ));
                String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                if (BlueprintCodec.isBlueprint(clip)) {
                    items.add(new ContextMenuItem(
                            Component.translatable("planomega.ctx.paste_blueprint").getString(),
                            Theme.ACCENT_TEAL,
                            this::pasteBlueprintFromClipboard
                    ));
                }
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.toggle_routing").getString(),
                        Theme.TEXT_SECONDARY,
                        this::toggleRoutingMode
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.auto_connect").getString(),
                        Theme.ACCENT_TEAL,
                        () -> performAutoConnect(false)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.auto_connect_loops").getString(),
                        Theme.ACCENT_AMBER,
                        () -> performAutoConnect(true)
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.auto_layout").getString(),
                        Theme.TEXT_PRIMARY,
                        this::performAutoLayout
                ));
                items.add(new ContextMenuItem(
                        Component.translatable("planomega.ctx.fit_view").getString(),
                        Theme.TEXT_SECONDARY,
                        this::fitGraphToView
                ));
            }
        }

        if (!items.isEmpty()) {
            this.activeContextMenu = new ContextMenu(screenX, screenY, items, this.font, this.width, this.height);
        }
    }

    private void cloneNodeToSandbox(RecipeNode r) {
        VirtualRecipeNode cloned = new VirtualRecipeNode(
                PlanGraph.newId("sim"),
                CanvasViewport.snap(r.x() + 20, snapToGrid),
                CanvasViewport.snap(r.y() + r.height() + 24, snapToGrid),
                r.width(),
                r.height(),
                r.groupId(),
                r.recipeId(),
                r.recipeTypeId(),
                r.machineId(),
                r.title() + " (What-If)",
                r.inputs(),
                r.outputs(),
                r.eut(),
                r.durationTicks(),
                r.tier(),
                r.circuit(),
                r.coilTemp(),
                r.cleanroom(),
                r.perfectOverclock(),
                "Cloned from " + r.recipeId(),
                r.targetRate(),
                r.targetPortId()
        );
        cloneToSandboxAndEdit(cloned);
    }

    private void ungroupNode(String nodeId) {
        PlanNode node = graph.nodes().get(nodeId);
        if (node == null || node.groupId() == null) {
            return;
        }
        String gid = node.groupId();
        Map<String, PlanNode> nextNodes = new LinkedHashMap<>(graph.nodes());
        nextNodes.put(nodeId, node.withGroupId(null));
        PlanNode maybeGroup = nextNodes.get(gid);
        if (maybeGroup instanceof GroupNode group) {
            List<String> remaining = group.childNodeIds().stream()
                    .filter(cid -> !cid.equals(nodeId))
                    .toList();
            if (remaining.isEmpty()) {
                nextNodes.remove(gid);
            } else {
                nextNodes.put(gid, group.withChildNodeIds(remaining));
            }
        }
        commitMutation(new PlanGraph(
                graph.schemaVersion(),
                graph.id(),
                graph.name(),
                graph.author(),
                graph.description(),
                nextNodes,
                graph.edges()
        ));
    }

    private void createStickyNoteAt(double wx, double wy) {
        StickyNoteNode note = new StickyNoteNode(
                PlanGraph.newId("note"),
                wx, wy,
                StickyNoteNode.DEFAULT_WIDTH,
                StickyNoteNode.DEFAULT_HEIGHT,
                null,
                "Note",
                "Double-click to edit",
                StickyNoteNode.DEFAULT_COLOR
        );
        commitMutation(graph.withNode(note));
        selectedNodeIds.clear();
        selectedNodeIds.add(note.id());
        openEditorForNode(note);
    }

    private void createVirtualNodeAt(double wx, double wy) {
        VirtualRecipeNode draft = VirtualRecipeNode.createDraft(
                PlanGraph.newId("sim"),
                wx, wy,
                "What-If Step",
                "planomega:virtual",
                List.of(ResourcePort.input("in_0", ResourceKey.item("minecraft:iron_ingot"), 1)),
                List.of(ResourcePort.output("out_0", ResourceKey.item("gtceu:steel_ingot"), 1)),
                30,
                100
        );
        commitMutation(graph.withNode(draft));
        selectedNodeIds.clear();
        selectedNodeIds.add(draft.id());
        openEditorForNode(draft);
    }

    private void showToast(String message) {
        this.toastText = message;
        this.toastExpiresAtMs = System.currentTimeMillis() + 3000L;
    }

    private record ContextMenuItem(String label, int color, Runnable action) {
    }

    private static final class ContextMenu {
        private static final int ROW_H = 18;
        private static final int PAD_Y = 4;
        private final int x;
        private final int y;
        private final int w;
        private final int h;
        private final List<ContextMenuItem> items;

        ContextMenu(int desiredX, int desiredY, List<ContextMenuItem> items, net.minecraft.client.gui.Font font, int screenW, int screenH) {
            this.items = items;
            int maxW = 110;
            for (ContextMenuItem item : items) {
                maxW = Math.max(maxW, font.width(item.label()) + 20);
            }
            this.w = maxW;
            this.h = items.size() * ROW_H + PAD_Y * 2;
            this.x = Math.max(4, Math.min(desiredX, screenW - this.w - 4));
            this.y = Math.max(4, Math.min(desiredY, screenH - this.h - 4));
        }

        boolean mouseClicked(double mx, double my) {
            if (mx < x || mx > x + w || my < y + PAD_Y || my >= y + h - PAD_Y) {
                return false;
            }
            int idx = (int) ((my - (y + PAD_Y)) / ROW_H);
            if (idx >= 0 && idx < items.size()) {
                items.get(idx).action().run();
                return true;
            }
            return false;
        }

        void render(GuiGraphics gfx, net.minecraft.client.gui.Font font, int mx, int my) {
            gfx.fill(x + 2, y + 2, x + w + 2, y + h + 2, 0x66000000);
            gfx.fill(x, y, x + w, y + h, Theme.MODAL_BG);
            gfx.fill(x, y, x + w, y + 1, Theme.BAR_BORDER);
            gfx.fill(x, y + h - 1, x + w, y + h, Theme.BAR_BORDER);
            gfx.fill(x, y, x + 1, y + h, Theme.BAR_BORDER);
            gfx.fill(x + w - 1, y, x + w, y + h, Theme.BAR_BORDER);

            for (int i = 0; i < items.size(); i++) {
                int ry = y + PAD_Y + i * ROW_H;
                boolean hovered = mx >= x + 2 && mx <= x + w - 2 && my >= ry && my < ry + ROW_H;
                if (hovered) {
                    gfx.fill(x + 2, ry, x + w - 2, ry + ROW_H, Theme.BTN_HOVER);
                }
                ContextMenuItem item = items.get(i);
                gfx.drawString(font, item.label(), x + 9, ry + 5, item.color(), false);
            }
        }
    }

    private record ToolbarButton(
            int x,
            int y,
            int w,
            int h,
            ResourceLocation icon,
            String label,
            String tooltip,
            int accent,
            boolean active,
            Runnable action
    ) {
        boolean isHovered(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        void render(GuiGraphics gfx, net.minecraft.client.gui.Font font, int mx, int my) {
            boolean hovered = isHovered(mx, my);
            int bg = active ? Theme.BTN_ACTIVE : (hovered ? Theme.BTN_HOVER : Theme.BTN_BG);
            int border = (active || hovered) ? accent : Theme.BTN_BORDER;
            gfx.fill(x, y, x + w, y + h, bg);
            gfx.fill(x, y, x + w, y + 1, border);
            gfx.fill(x, y + h - 1, x + w, y + h, border);
            gfx.fill(x, y, x + 1, y + h, border);
            gfx.fill(x + w - 1, y, x + w, y + h, border);

            int iconSize = 12;
            int iconY = y + (h - iconSize) / 2;
            if (label.isEmpty()) {
                int iconX = x + (w - iconSize) / 2;
                gfx.blit(icon, iconX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
            } else {
                int iconX = x + 5;
                gfx.blit(icon, iconX, iconY, 0, 0, iconSize, iconSize, iconSize, iconSize);
                gfx.drawString(font, label, iconX + iconSize + 4, y + (h - 8) / 2, Theme.TEXT_PRIMARY, false);
            }
        }
    }

    private void toggleRoutingMode() {
        orthogonalRouting = !orthogonalRouting;
        rebuildToolbarButtons();
        showToast(Component.translatable(orthogonalRouting
                ? "planomega.toast.routing_orthogonal"
                : "planomega.toast.routing_bezier").getString());
    }

    // ==================== Plan Tabs ====================

    private void syncActiveTabState() {
        if (activeTabIndex < 0 || activeTabIndex >= openTabs.size()) {
            return;
        }
        PlanTab tab = openTabs.get(activeTabIndex);
        tab.graph = this.graph;
        tab.selectedEdgeId = this.selectedEdgeId;
        tab.solveResult = this.solveResult;
        tab.auditReport = this.auditReport;
        tab.camX = viewport.camX();
        tab.camY = viewport.camY();
        tab.zoom = viewport.zoom();
        tab.initialFitDone = this.initialFitDone;
    }

    private void switchToTab(int idx) {
        if (idx < 0 || idx >= openTabs.size() || idx == activeTabIndex) {
            return;
        }
        syncActiveTabState();
        activeTabIndex = idx;
        PlanTab tab = openTabs.get(idx);
        this.graph = tab.graph;
        this.history = tab.history;
        this.selectedNodeIds = tab.selectedNodeIds;
        this.selectedEdgeId = tab.selectedEdgeId;
        this.solveResult = tab.solveResult;
        this.auditReport = tab.auditReport;
        this.initialFitDone = tab.initialFitDone;
        if (this.solveResult == null || this.auditReport == null) {
            recomputeAnalysis();
        }
        viewport.setCamera(tab.camX, tab.camY);
        viewport.setZoom(tab.zoom);
        if (!this.initialFitDone) {
            fitGraphToView();
            this.initialFitDone = true;
        }
        if (searchOpen) {
            refreshSearchResults();
        }
    }

    private void openNewTab() {
        PlanGraph empty = PlanGraph.empty("Plan " + (openTabs.size() + 1));
        openGraphInTab(empty);
    }

    private void openGraphInTab(PlanGraph loaded) {
        for (int i = 0; i < openTabs.size(); i++) {
            if (openTabs.get(i).graph.id().equals(loaded.id())) {
                switchToTab(i);
                history.recordBeforeEdit(graph);
                graph = loaded;
                recomputeAnalysis();
                selectedNodeIds.clear();
                selectedEdgeId = null;
                PlanStorage.saveAutosave(graph);
                fitGraphToView();
                return;
            }
        }
        if (graph.nodes().isEmpty() && graph.edges().isEmpty()) {
            history.recordBeforeEdit(graph);
            graph = loaded;
            recomputeAnalysis();
            selectedNodeIds.clear();
            selectedEdgeId = null;
            PlanStorage.saveAutosave(graph);
            fitGraphToView();
            return;
        }
        syncActiveTabState();
        PlanTab tab = new PlanTab(loaded);
        openTabs.add(tab);
        activeTabIndex = openTabs.size() - 1;
        this.graph = loaded;
        this.history = tab.history;
        this.selectedNodeIds = tab.selectedNodeIds;
        this.selectedEdgeId = null;
        recomputeAnalysis();
        fitGraphToView();
        this.initialFitDone = true;
        PlanStorage.saveAutosave(graph);
    }

    private void closeTab(int idx) {
        if (idx < 0 || idx >= openTabs.size()) {
            return;
        }
        if (openTabs.size() == 1) {
            PlanGraph empty = PlanGraph.empty("Untitled Plan");
            openTabs.set(0, new PlanTab(empty));
            activeTabIndex = 0;
            PlanTab tab = openTabs.get(0);
            this.graph = tab.graph;
            this.history = tab.history;
            this.selectedNodeIds = tab.selectedNodeIds;
            this.selectedEdgeId = null;
            recomputeAnalysis();
            viewport.resetView();
            return;
        }
        openTabs.remove(idx);
        if (activeTabIndex >= openTabs.size()) {
            activeTabIndex = openTabs.size() - 1;
        } else if (idx < activeTabIndex) {
            activeTabIndex--;
        }
        PlanTab tab = openTabs.get(activeTabIndex);
        this.graph = tab.graph;
        this.history = tab.history;
        this.selectedNodeIds = tab.selectedNodeIds;
        this.selectedEdgeId = tab.selectedEdgeId;
        this.solveResult = tab.solveResult;
        this.auditReport = tab.auditReport;
        this.initialFitDone = tab.initialFitDone;
        if (this.solveResult == null || this.auditReport == null) {
            recomputeAnalysis();
        }
        viewport.setCamera(tab.camX, tab.camY);
        viewport.setZoom(tab.zoom);
        if (searchOpen) {
            refreshSearchResults();
        }
    }

    private void renderTabBar(GuiGraphics gfx, int mouseX, int mouseY) {
        int y = toolbarHeight;
        int h = TAB_BAR_HEIGHT;
        gfx.fill(0, y, this.width, y + h, 0xEE0B1017);
        gfx.fill(0, y + h - 1, this.width, y + h, Theme.BAR_BORDER);

        tabRects.clear();
        int curX = 6;
        int maxTabArea = this.width - 36;
        int maxTabW = openTabs.isEmpty() ? 140 : Math.max(72, Math.min(150, (maxTabArea - 12) / Math.max(1, openTabs.size())));

        for (int i = 0; i < openTabs.size(); i++) {
            PlanTab tab = openTabs.get(i);
            String title = (i == activeTabIndex ? this.graph.name() : tab.graph.name());
            int desiredW = Math.min(maxTabW, Math.max(72, this.font.width(title) + 30));
            if (curX + desiredW > maxTabArea) {
                break;
            }
            boolean active = (i == activeTabIndex);
            boolean hovered = mouseX >= curX && mouseX <= curX + desiredW && mouseY >= y + 2 && mouseY < y + h;
            int bg = active ? Theme.CANVAS_BG : (hovered ? Theme.BTN_HOVER : Theme.BTN_BG);
            gfx.fill(curX, y + 2, curX + desiredW, y + h - (active ? 0 : 1), bg);
            if (active) {
                gfx.fill(curX, y + 2, curX + desiredW, y + 4, Theme.ACCENT_TEAL);
            } else {
                gfx.fill(curX, y + 2, curX + desiredW, y + 3, Theme.BAR_BORDER);
            }
            gfx.fill(curX, y + 2, curX + 1, y + h, Theme.BAR_BORDER);
            gfx.fill(curX + desiredW - 1, y + 2, curX + desiredW, y + h, Theme.BAR_BORDER);

            String clipped = this.font.plainSubstrByWidth(title, desiredW - 24);
            gfx.drawString(this.font, clipped, curX + 6, y + 7, active ? Theme.TEXT_PRIMARY : Theme.TEXT_SECONDARY, false);

            int closeW = 12;
            int closeX = curX + desiredW - closeW - 3;
            boolean closeHover = mouseX >= closeX && mouseX <= closeX + closeW && mouseY >= y + 4 && mouseY <= y + h - 3;
            gfx.drawString(this.font, "x", closeX + 3, y + 7, closeHover ? 0xFFEF4444 : Theme.TEXT_MUTED, false);

            tabRects.add(new TabRect(curX, y + 2, desiredW, h - 2, closeX, closeW, i));
            curX += desiredW + 3;
        }

        newTabBtnX = curX + 2;
        newTabBtnY = y + 3;
        newTabBtnW = 18;
        newTabBtnH = h - 6;
        boolean plusHover = mouseX >= newTabBtnX && mouseX <= newTabBtnX + newTabBtnW
                && mouseY >= newTabBtnY && mouseY <= newTabBtnY + newTabBtnH;
        gfx.fill(newTabBtnX, newTabBtnY, newTabBtnX + newTabBtnW, newTabBtnY + newTabBtnH, plusHover ? Theme.BTN_HOVER : Theme.BTN_BG);
        gfx.drawString(this.font, "+", newTabBtnX + 6, newTabBtnY + 4, plusHover ? Theme.ACCENT_TEAL : Theme.TEXT_SECONDARY, false);
    }

    private boolean handleTabBarClick(double mouseX, double mouseY) {
        if (mouseX >= newTabBtnX && mouseX <= newTabBtnX + newTabBtnW
                && mouseY >= newTabBtnY && mouseY <= newTabBtnY + newTabBtnH) {
            openNewTab();
            return true;
        }
        for (TabRect rect : tabRects) {
            if (mouseX >= rect.x() && mouseX <= rect.x() + rect.w()
                    && mouseY >= rect.y() && mouseY <= rect.y() + rect.h()) {
                if (mouseX >= rect.closeX() && mouseX <= rect.closeX() + rect.closeW()) {
                    closeTab(rect.index());
                } else {
                    switchToTab(rect.index());
                }
                return true;
            }
        }
        return false;
    }

    // ==================== Floating Quick Search (Ctrl+F) ====================

    private void openSearch() {
        searchOpen = true;
        searchQuery = "";
        searchSelectedIndex = 0;
        refreshSearchResults();
    }

    private void closeSearch() {
        searchOpen = false;
        searchResults = List.of();
        searchMatchedNodeIds = Set.of();
        activeSearchNodeId = null;
    }

    private void refreshSearchResults() {
        String q = searchQuery.trim().toLowerCase(java.util.Locale.ROOT);
        String[] tokens = q.isEmpty() ? new String[0] : q.split("\\s+");

        List<SearchResultItem> matches = new ArrayList<>();
        Set<String> matchedIds = new LinkedHashSet<>();

        for (PlanNode node : graph.nodes().values()) {
            String title;
            String subtitle;
            int badgeColor;
            StringBuilder haystack = new StringBuilder();
            haystack.append(node.id()).append(' ');

            if (node instanceof ProcessNode proc) {
                title = proc.title();
                VoltageTier vt = VoltageTier.byIndex(proc.tier());
                subtitle = vt.shortName() + " • " + proc.machineId();
                badgeColor = vt.badgeColor();
                haystack.append(proc.title()).append(' ')
                        .append(proc.machineId()).append(' ')
                        .append(proc.recipeTypeId()).append(' ')
                        .append(vt.shortName()).append(' ')
                        .append(proc.role().name()).append(' ');
                if (proc instanceof RecipeNode r) {
                    haystack.append(r.recipeId()).append(' ');
                } else if (proc instanceof VirtualRecipeNode v) {
                    haystack.append(v.baseRecipeId()).append(' ').append(v.notes()).append(' ');
                }
                for (ResourcePort p : proc.inputs()) {
                    haystack.append(p.displayLabel()).append(' ').append(p.resource().id()).append(' ');
                }
                for (ResourcePort p : proc.outputs()) {
                    haystack.append(p.displayLabel()).append(' ').append(p.resource().id()).append(' ');
                }
            } else if (node instanceof GroupNode group) {
                title = group.title();
                subtitle = "Group (" + group.childNodeIds().size() + ")";
                badgeColor = group.color();
                haystack.append(group.title()).append(" group ");
            } else if (node instanceof StickyNoteNode note) {
                title = note.title().isBlank() ? "Note" : note.title();
                subtitle = note.text();
                badgeColor = note.color();
                haystack.append(note.title()).append(' ').append(note.text()).append(" note ");
            } else {
                continue;
            }

            String hay = haystack.toString().toLowerCase(java.util.Locale.ROOT);
            boolean ok = true;
            for (String tok : tokens) {
                if (!hay.contains(tok)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                matches.add(new SearchResultItem(node.id(), title, subtitle, badgeColor));
                matchedIds.add(node.id());
            }
        }

        this.searchResults = matches;
        this.searchMatchedNodeIds = matchedIds;
        if (matches.isEmpty()) {
            this.searchSelectedIndex = 0;
            this.activeSearchNodeId = null;
        } else {
            this.searchSelectedIndex = Math.max(0, Math.min(this.searchSelectedIndex, matches.size() - 1));
            this.activeSearchNodeId = matches.get(this.searchSelectedIndex).nodeId();
        }
    }

    private void jumpToSearchResult(int idx, boolean closeAfter) {
        if (idx < 0 || idx >= searchResults.size()) {
            return;
        }
        String nodeId = searchResults.get(idx).nodeId();
        expandAncestors(nodeId);
        focusNode(nodeId);
        if (closeAfter) {
            closeSearch();
        }
    }

    private void expandAncestors(String nodeId) {
        PlanNode cur = graph.nodes().get(nodeId);
        PlanGraph working = graph;
        Set<String> seen = new java.util.HashSet<>();
        while (cur != null && cur.groupId() != null && seen.add(cur.groupId())) {
            PlanNode parent = working.nodes().get(cur.groupId());
            if (parent instanceof GroupNode g && g.collapsed()) {
                working = working.toggleGroupCollapsed(g.id());
            }
            cur = parent;
        }
        if (!working.equals(graph)) {
            commitMutation(working);
        }
    }

    private int searchBoxW() {
        return Math.min(340, Math.max(180, viewport.viewWidth() - 24));
    }

    private int searchBoxX() {
        return viewport.viewX() + (viewport.viewWidth() - searchBoxW()) / 2;
    }

    private int searchBoxY() {
        return toolbarHeight + TAB_BAR_HEIGHT + 8;
    }

    private boolean isMouseOverSearchOverlay(double mx, double my) {
        int sx = searchBoxX();
        int sy = searchBoxY();
        int sw = searchBoxW();
        int rows = Math.min(7, searchResults.size());
        int sh = 24 + (rows > 0 ? rows * 20 + 4 : 0);
        return mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sh;
    }

    private void renderSearchOverlay(GuiGraphics gfx, int mouseX, int mouseY) {
        int sx = searchBoxX();
        int sy = searchBoxY();
        int sw = searchBoxW();

        gfx.fill(sx + 2, sy + 2, sx + sw + 2, sy + 26, 0x66000000);
        gfx.fill(sx, sy, sx + sw, sy + 24, Theme.MODAL_BG);
        gfx.fill(sx, sy, sx + sw, sy + 1, Theme.ACCENT_TEAL);
        gfx.fill(sx, sy + 23, sx + sw, sy + 24, Theme.ACCENT_TEAL);
        gfx.fill(sx, sy, sx + 1, sy + 24, Theme.ACCENT_TEAL);
        gfx.fill(sx + sw - 1, sy, sx + sw, sy + 24, Theme.ACCENT_TEAL);

        String countLabel = searchResults.size() + "";
        int countW = this.font.width(countLabel) + 8;
        int textBudget = Math.max(40, sw - countW - 28);
        String placeholder = Component.translatable("planomega.search.placeholder").getString();
        boolean blink = (System.currentTimeMillis() / 500L) % 2L == 0L;
        String display = searchQuery.isEmpty()
                ? placeholder
                : this.font.plainSubstrByWidth(searchQuery, textBudget) + (blink ? "|" : "");
        gfx.drawString(this.font, display, sx + 8, sy + 8,
                searchQuery.isEmpty() ? Theme.TEXT_MUTED : Theme.TEXT_PRIMARY, false);
        gfx.drawString(this.font, countLabel, sx + sw - countW - 14, sy + 8, Theme.ACCENT_TEAL, false);
        gfx.drawString(this.font, "x", sx + sw - 12, sy + 8, Theme.TEXT_SECONDARY, false);

        int rows = Math.min(7, searchResults.size());
        if (rows > 0) {
            int dy = sy + 24;
            int dh = rows * 20 + 2;
            gfx.fill(sx, dy, sx + sw, dy + dh, 0xF0111822);
            gfx.fill(sx, dy + dh - 1, sx + sw, dy + dh, Theme.BAR_BORDER);
            gfx.fill(sx, dy, sx + 1, dy + dh, Theme.BAR_BORDER);
            gfx.fill(sx + sw - 1, dy, sx + sw, dy + dh, Theme.BAR_BORDER);

            for (int i = 0; i < rows; i++) {
                SearchResultItem item = searchResults.get(i);
                int ry = dy + 1 + i * 20;
                boolean hovered = mouseX >= sx + 2 && mouseX <= sx + sw - 2 && mouseY >= ry && mouseY < ry + 20;
                boolean selected = (i == searchSelectedIndex);
                if (selected || hovered) {
                    gfx.fill(sx + 2, ry, sx + sw - 2, ry + 20, selected ? Theme.BTN_ACTIVE : Theme.BTN_HOVER);
                }
                gfx.fill(sx + 6, ry + 5, sx + 9, ry + 15, item.badgeColor());
                String title = this.font.plainSubstrByWidth(item.title(), sw / 2);
                gfx.drawString(this.font, title, sx + 14, ry + 6, Theme.TEXT_PRIMARY, false);
                String sub = this.font.plainSubstrByWidth(item.subtitle(), Math.max(20, sw / 2 - 20));
                int subW = this.font.width(sub);
                gfx.drawString(this.font, sub, sx + sw - subW - 8, ry + 6, Theme.TEXT_MUTED, false);
            }
        }
    }

    private boolean handleSearchOverlayClick(double mouseX, double mouseY, int button) {
        int sx = searchBoxX();
        int sy = searchBoxY();
        int sw = searchBoxW();
        if (mouseY >= sy && mouseY <= sy + 24 && mouseX >= sx + sw - 16 && mouseX <= sx + sw) {
            closeSearch();
            return true;
        }
        int rows = Math.min(7, searchResults.size());
        int dy = sy + 24;
        if (rows > 0 && mouseX >= sx && mouseX <= sx + sw && mouseY >= dy && mouseY <= dy + rows * 20) {
            int idx = (int) ((mouseY - dy) / 20);
            if (idx >= 0 && idx < rows) {
                jumpToSearchResult(idx, true);
                return true;
            }
        }
        if (isMouseOverSearchOverlay(mouseX, mouseY)) {
            return true;
        }
        closeSearch();
        return false;
    }

    // ==================== JEI / EMI Queue, Recipe Switcher, Blueprints, SOURCE/SINK ====================

    private void consumePendingXeiQueue() {
        List<RecipeEntry> queuedRecipes = PendingRecipeQueue.drainAll();
        if (!queuedRecipes.isEmpty()) {
            PlanGraph next = graph;
            RecipeNode lastNode = null;
            selectedNodeIds.clear();
            for (int i = 0; i < queuedRecipes.size(); i++) {
                RecipeEntry entry = queuedRecipes.get(i);
                double wx = CanvasViewport.snap(viewport.camX() - 105.0 + i * 32.0, snapToGrid);
                double wy = CanvasViewport.snap(viewport.camY() - 55.0 + i * 24.0, snapToGrid);
                RecipeNode node = entry.toRecipeNode(wx, wy);
                next = next.withNode(node);
                selectedNodeIds.add(node.id());
                lastNode = node;
            }
            commitMutation(next);
            if (queuedRecipes.size() == 1 && lastNode != null) {
                showToast(Component.translatable("planomega.toast.added_recipe", queuedRecipes.get(0).title()).getString());
            } else {
                showToast(Component.translatable("planomega.toast.received_queued", queuedRecipes.size()).getString());
            }
        }

        List<PlanGraph> queuedSubgraphs = PendingRecipeQueue.drainSubgraphs();
        if (!queuedSubgraphs.isEmpty()) {
            PlanGraph next = graph;
            Set<String> insertedIds = new LinkedHashSet<>();
            for (PlanGraph sub : queuedSubgraphs) {
                PlanGraph cloned = GroupTemplateCodec.cloneWithFreshIds(sub, viewport.camX() - 200.0, viewport.camY() - 100.0);
                next = next.mergeSubgraph(cloned);
                insertedIds.addAll(cloned.nodes().keySet());
            }
            commitMutation(next);
            selectedNodeIds.clear();
            selectedNodeIds.addAll(insertedIds);
            showToast(Component.translatable("planomega.toast.imported_tree", insertedIds.size()).getString());
        }
    }

    private void importActiveEmiTree() {
        IntegrationBridge.importActiveEmiTree().ifPresentOrElse(tree -> {
            PlanGraph cloned = GroupTemplateCodec.cloneWithFreshIds(tree, viewport.camX() - 200.0, viewport.camY() - 100.0);
            commitMutation(graph.mergeSubgraph(cloned));
            selectedNodeIds.clear();
            selectedNodeIds.addAll(cloned.nodes().keySet());
            fitGraphToView();
            showToast(Component.translatable("planomega.toast.imported_tree", cloned.nodes().size()).getString());
        }, () -> showToast(Component.translatable("planomega.toast.no_emi_tree").getString()));
    }

    private void openRecipeSwitcher(RecipeNode recipe) {
        openModal(RecipeSearchModal.forSwitch(recipe, newEntry -> {
            RecipeNode switched = RecipeSearchModal.applySwitchToNode(recipe, newEntry);
            PlanGraph next = graph.withNode(switched);
            List<String> invalidEdges = new ArrayList<>();
            for (PlanEdge edge : next.edges().values()) {
                if (edge.fromNodeId().equals(switched.id()) && switched.findPort(edge.fromPortId()).isEmpty()) {
                    invalidEdges.add(edge.id());
                } else if (edge.toNodeId().equals(switched.id()) && switched.findPort(edge.toPortId()).isEmpty()) {
                    invalidEdges.add(edge.id());
                }
            }
            if (!invalidEdges.isEmpty()) {
                next = next.withoutEdges(invalidEdges);
            }
            commitMutation(next);
            showToast(Component.translatable("planomega.toast.switched_recipe", newEntry.title()).getString());
        }));
    }

    private void copyBlueprintToClipboard() {
        try {
            String encoded = BlueprintCodec.encode(graph);
            Minecraft.getInstance().keyboardHandler.setClipboard(encoded);
            showToast(Component.translatable("planomega.toast.blueprint_copied").getString());
        } catch (Exception ex) {
            showToast("Blueprint encode error: " + ex.getMessage());
        }
    }

    private void pasteBlueprintFromClipboard() {
        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (!BlueprintCodec.isBlueprint(clip)) {
            showToast(Component.translatable("planomega.toast.blueprint_invalid").getString());
            return;
        }
        try {
            PlanGraph decoded = BlueprintCodec.decode(clip);
            openGraphInTab(decoded);
            showToast(Component.translatable("planomega.toast.blueprint_pasted", decoded.name()).getString());
        } catch (Exception ex) {
            showToast(Component.translatable("planomega.toast.blueprint_invalid").getString());
        }
    }

    private void createSourceNodeAt(double wx, double wy) {
        VirtualRecipeNode src = VirtualRecipeNode.createSource(
                PlanGraph.newId("src"),
                wx, wy,
                "Raw Source",
                ResourceKey.item("minecraft:iron_ingot"),
                1.0
        );
        commitMutation(graph.withNode(src));
        selectedNodeIds.clear();
        selectedNodeIds.add(src.id());
        openEditorForNode(src);
    }

    private void createSinkNodeAt(double wx, double wy) {
        VirtualRecipeNode sink = VirtualRecipeNode.createSink(
                PlanGraph.newId("sink"),
                wx, wy,
                "Target Sink",
                ResourceKey.item("gtceu:steel_ingot"),
                1.0,
                1.0
        );
        commitMutation(graph.withNode(sink));
        selectedNodeIds.clear();
        selectedNodeIds.add(sink.id());
        openEditorForNode(sink);
    }

    private void createSourceForPort(PortHit portHit) {
        PlanNode host = graph.nodes().get(portHit.nodeId());
        if (host == null) {
            return;
        }
        double wx = CanvasViewport.snap(host.x() - 250.0, snapToGrid);
        double wy = CanvasViewport.snap(portHit.worldPos().y() - 40.0, snapToGrid);
        ResourcePort port = portHit.port();
        VirtualRecipeNode src = VirtualRecipeNode.createSource(
                PlanGraph.newId("src"),
                wx, wy,
                "Source: " + port.displayLabel(),
                port.resource(),
                Math.max(1.0, port.amount())
        );
        PlanGraph next = graph.withNode(src);
        if (!src.outputs().isEmpty()) {
            ResourcePort outPort = src.outputs().get(0);
            if (next.canConnect(src.id(), outPort.id(), host.id(), port.id())) {
                next = next.withEdge(new PlanEdge(
                        PlanGraph.newId("edge"),
                        src.id(), outPort.id(), host.id(), port.id(),
                        outPort.resource(), true
                ));
            }
        }
        commitMutation(next);
        selectedNodeIds.clear();
        selectedNodeIds.add(src.id());
    }

    private void createSinkForPort(PortHit portHit) {
        PlanNode host = graph.nodes().get(portHit.nodeId());
        if (host == null) {
            return;
        }
        double wx = CanvasViewport.snap(host.x() + host.width() + 50.0, snapToGrid);
        double wy = CanvasViewport.snap(portHit.worldPos().y() - 40.0, snapToGrid);
        ResourcePort port = portHit.port();
        VirtualRecipeNode sink = VirtualRecipeNode.createSink(
                PlanGraph.newId("sink"),
                wx, wy,
                "Sink: " + port.displayLabel(),
                port.resource(),
                Math.max(1.0, port.amount()),
                1.0
        );
        PlanGraph next = graph.withNode(sink);
        if (!sink.inputs().isEmpty()) {
            ResourcePort inPort = sink.inputs().get(0);
            if (next.canConnect(host.id(), port.id(), sink.id(), inPort.id())) {
                next = next.withEdge(new PlanEdge(
                        PlanGraph.newId("edge"),
                        host.id(), port.id(), sink.id(), inPort.id(),
                        port.resource(), true
                ));
            }
        }
        commitMutation(next);
        selectedNodeIds.clear();
        selectedNodeIds.add(sink.id());
    }

    private static final class PlanTab {
        PlanGraph graph;
        final UndoHistory history = new UndoHistory(64);
        final Set<String> selectedNodeIds = new LinkedHashSet<>();
        @Nullable String selectedEdgeId;
        @Nullable SolveResult solveResult;
        @Nullable AuditReport auditReport;
        double camX = 0.0;
        double camY = 0.0;
        double zoom = 1.0;
        boolean initialFitDone = false;

        PlanTab(PlanGraph graph) {
            this.graph = graph;
        }
    }

    private record TabRect(int x, int y, int w, int h, int closeX, int closeW, int index) {
    }

    private record SearchResultItem(String nodeId, String title, String subtitle, int badgeColor) {
    }
}
