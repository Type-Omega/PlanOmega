package com.typeomega.planomega.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.typeomega.planomega.audit.AuditReport;
import com.typeomega.planomega.audit.FrictionFlag;
import com.typeomega.planomega.client.ui.CanvasGeometry.EdgeEndpoints;
import com.typeomega.planomega.client.ui.CanvasGeometry.PortHit;
import com.typeomega.planomega.client.ui.CanvasGeometry.Vec2;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanEdge;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.StickyNoteNode;
import com.typeomega.planomega.model.VirtualRecipeNode;
import com.typeomega.planomega.model.VoltageTier;
import com.typeomega.planomega.solver.SolveResult;
import com.typeomega.planomega.solver.SolveResult.NodeSolveStats;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Renders the infinite 2D grid, collapsible groups, Bézier resource edges,
 * recipe/virtual/sticky-note cards, ports, and marquee selection box.
 */
public final class CanvasRenderer {

    private static final DecimalFormat AMOUNT_FMT = new DecimalFormat("#.##");

    private CanvasRenderer() {
    }

    public record RenderContext(
            PlanGraph graph,
            CanvasViewport viewport,
            Set<String> selectedNodeIds,
            @Nullable String selectedEdgeId,
            @Nullable String hoveredNodeId,
            @Nullable String hoveredEdgeId,
            @Nullable PortHit hoveredPort,
            @Nullable PortHit wiringSourcePort,
            double mouseWorldX,
            double mouseWorldY,
            boolean showGrid,
            boolean orthogonalRouting,
            @Nullable double[] marqueeWorldBounds,
            @Nullable SolveResult solveResult,
            @Nullable AuditReport auditReport,
            Set<String> searchMatchedNodeIds,
            @Nullable String activeSearchNodeId
    ) {
    }

    public static void render(GuiGraphics gfx, Font font, RenderContext ctx) {
        CanvasViewport vp = ctx.viewport();
        gfx.fill(vp.viewX(), vp.viewY(), vp.viewX() + vp.viewWidth(), vp.viewY() + vp.viewHeight(), Theme.CANVAS_BG);

        gfx.enableScissor(vp.viewX(), vp.viewY(), vp.viewX() + vp.viewWidth(), vp.viewY() + vp.viewHeight());

        if (ctx.showGrid()) {
            drawGrid(gfx, vp);
        }

        CanvasGeometry.FlowHighlight flowHighlight = CanvasGeometry.computeFlowHighlight(
                ctx.graph(), ctx.hoveredNodeId(), ctx.hoveredEdgeId(), ctx.hoveredPort()
        );

        PoseStack pose = gfx.pose();
        pose.pushPose();
        pose.translate((float) vp.centerScreenX(), (float) vp.centerScreenY(), 0f);
        pose.scale((float) vp.zoom(), (float) vp.zoom(), 1f);
        pose.translate((float) -vp.camX(), (float) -vp.camY(), 0f);

        // 1. Groups behind nodes
        for (PlanNode node : ctx.graph().nodes().values()) {
            if (node instanceof GroupNode group && !ctx.graph().isHiddenByCollapsedGroup(group.id())) {
                drawGroupNode(gfx, font, group, ctx);
            }
        }

        // 2. Edges
        for (PlanEdge edge : ctx.graph().edges().values()) {
            Optional<EdgeEndpoints> ptsOpt = CanvasGeometry.resolveEdgeEndpoints(ctx.graph(), edge);
            if (ptsOpt.isEmpty()) {
                continue;
            }
            EdgeEndpoints pts = ptsOpt.get();
            boolean selected = edge.id().equals(ctx.selectedEdgeId());
            boolean hovered = edge.id().equals(ctx.hoveredEdgeId());
            boolean inFlow = flowHighlight.isActive() && flowHighlight.edgeIds().contains(edge.id());
            boolean dimmedByFlow = flowHighlight.isActive() && !inFlow && !selected;

            int color = edge.resource().kind().color();
            if (selected) {
                color = Theme.ACCENT_TEAL;
            } else if (hovered) {
                color = 0xFFFFFFFF;
            } else if (dimmedByFlow) {
                color = Theme.withAlpha(color, 0x45);
            }
            float thickness = (selected || hovered) ? 3.2f : (inFlow ? 2.9f : (dimmedByFlow ? 1.5f : 2.1f));
            List<Vec2> wirePoints = ctx.orthogonalRouting()
                    ? CanvasGeometry.sampleOrthogonal(pts.start(), pts.end())
                    : CanvasGeometry.sampleBezier(pts.start(), pts.end(), 24);
            drawWire(pose, wirePoints, color, thickness);

            if (ctx.solveResult() != null && ctx.solveResult().isSolved() && ctx.viewport().zoom() >= 0.55) {
                Double flow = ctx.solveResult().edgeFlows().get(edge.id());
                if (flow != null && flow > 1e-6) {
                    Vec2 mid = wireMidpoint(wirePoints, pts.start(), pts.end());
                    String flowText = edge.resource().kind() == ResourceKind.FLUID
                            ? AMOUNT_FMT.format(flow) + " mB/s"
                            : AMOUNT_FMT.format(flow) + "/s";
                    int fw = font.width(flowText);
                    int lx = (int) Math.round(mid.x() - fw * 0.5);
                    int ly = (int) Math.round(mid.y() - 12);
                    gfx.fill(lx - 3, ly - 1, lx + fw + 3, ly + 9, dimmedByFlow ? 0x660D1117 : 0xCC0D1117);
                    gfx.drawString(font, flowText, lx, ly, color, false);
                }
            }
        }

        // 3. Live wiring preview
        if (ctx.wiringSourcePort() != null) {
            PortHit src = ctx.wiringSourcePort();
            Vec2 start = src.worldPos();
            Vec2 end = new Vec2(ctx.mouseWorldX(), ctx.mouseWorldY());
            if (src.port().direction().name().equals("INPUT")) {
                Vec2 tmp = start;
                start = end;
                end = tmp;
            }
            List<Vec2> previewPoints = ctx.orthogonalRouting()
                    ? CanvasGeometry.sampleOrthogonal(start, end)
                    : CanvasGeometry.sampleBezier(start, end, 24);
            drawWire(pose, previewPoints, src.port().resource().kind().color(), 2.6f);
        }

        // 4. Process nodes and sticky notes
        for (PlanNode node : ctx.graph().nodes().values()) {
            if (node instanceof GroupNode || ctx.graph().isHiddenByCollapsedGroup(node.id())) {
                continue;
            }
            if (node instanceof ProcessNode proc) {
                drawProcessNode(gfx, font, proc, ctx, flowHighlight);
            } else if (node instanceof StickyNoteNode note) {
                drawStickyNote(gfx, font, note, ctx);
            }
        }

        // 5. Marquee selection box
        if (ctx.marqueeWorldBounds() != null) {
            double[] m = ctx.marqueeWorldBounds();
            int x1 = (int) Math.floor(Math.min(m[0], m[2]));
            int y1 = (int) Math.floor(Math.min(m[1], m[3]));
            int x2 = (int) Math.ceil(Math.max(m[0], m[2]));
            int y2 = (int) Math.ceil(Math.max(m[1], m[3]));
            gfx.fill(x1, y1, x2, y2, Theme.MARQUEE_FILL);
            drawOutline(gfx, x1, y1, x2 - x1, y2 - y1, Theme.MARQUEE_BORDER);
        }

        pose.popPose();
        gfx.disableScissor();

        MinimapBounds mm = computeMinimapBounds(ctx.graph(), vp);
        if (mm != null) {
            drawMinimap(gfx, ctx, mm);
        }
    }

    public record MinimapBounds(
            int x,
            int y,
            int w,
            int h,
            double worldMinX,
            double worldMinY,
            double worldMaxX,
            double worldMaxY
    ) {
        public boolean contains(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        public double screenToWorldX(double sx) {
            double t = Math.max(0.0, Math.min(1.0, (sx - x) / w));
            return worldMinX + t * (worldMaxX - worldMinX);
        }

        public double screenToWorldY(double sy) {
            double t = Math.max(0.0, Math.min(1.0, (sy - y) / h));
            return worldMinY + t * (worldMaxY - worldMinY);
        }
    }

    public static @Nullable MinimapBounds computeMinimapBounds(PlanGraph graph, CanvasViewport vp) {
        if (vp.viewWidth() < 260 || vp.viewHeight() < 180) {
            return null;
        }
        int mw = Math.min(148, Math.max(96, vp.viewWidth() / 5));
        int mh = Math.min(98, Math.max(68, vp.viewHeight() / 5));
        int mx = vp.viewX() + vp.viewWidth() - mw - 10;
        int my = vp.viewY() + vp.viewHeight() - mh - 10;

        double viewLeft = vp.screenToWorldX(vp.viewX());
        double viewRight = vp.screenToWorldX(vp.viewX() + vp.viewWidth());
        double viewTop = vp.screenToWorldY(vp.viewY());
        double viewBottom = vp.screenToWorldY(vp.viewY() + vp.viewHeight());

        double minX = viewLeft;
        double minY = viewTop;
        double maxX = viewRight;
        double maxY = viewBottom;

        for (PlanNode node : graph.nodes().values()) {
            if (graph.isHiddenByCollapsedGroup(node.id())) {
                continue;
            }
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
            maxX = Math.max(maxX, node.x() + CanvasGeometry.visualWidth(node));
            maxY = Math.max(maxY, node.y() + CanvasGeometry.visualHeight(node));
        }

        double padX = Math.max(120.0, (maxX - minX) * 0.12);
        double padY = Math.max(80.0, (maxY - minY) * 0.12);
        minX -= padX;
        maxX += padX;
        minY -= padY;
        maxY += padY;

        // Preserve aspect ratio inside minimap
        double worldW = Math.max(1.0, maxX - minX);
        double worldH = Math.max(1.0, maxY - minY);
        double mapAspect = (double) mw / mh;
        if (worldW / worldH > mapAspect) {
            double targetH = worldW / mapAspect;
            double cy = (minY + maxY) * 0.5;
            minY = cy - targetH * 0.5;
            maxY = cy + targetH * 0.5;
        } else {
            double targetW = worldH * mapAspect;
            double cx = (minX + maxX) * 0.5;
            minX = cx - targetW * 0.5;
            maxX = cx + targetW * 0.5;
        }

        return new MinimapBounds(mx, my, mw, mh, minX, minY, maxX, maxY);
    }

    private static void drawMinimap(GuiGraphics gfx, RenderContext ctx, MinimapBounds mm) {
        gfx.fill(mm.x(), mm.y(), mm.x() + mm.w(), mm.y() + mm.h(), 0xDD0B1017);
        drawOutline(gfx, mm.x(), mm.y(), mm.w(), mm.h(), Theme.BAR_BORDER);

        gfx.enableScissor(mm.x() + 1, mm.y() + 1, mm.x() + mm.w() - 1, mm.y() + mm.h() - 1);
        double spanX = Math.max(1.0, mm.worldMaxX() - mm.worldMinX());
        double spanY = Math.max(1.0, mm.worldMaxY() - mm.worldMinY());

        // Groups first
        for (PlanNode node : ctx.graph().nodes().values()) {
            if (node instanceof GroupNode group && !ctx.graph().isHiddenByCollapsedGroup(group.id())) {
                int x1 = mm.x() + (int) Math.round((group.x() - mm.worldMinX()) / spanX * mm.w());
                int y1 = mm.y() + (int) Math.round((group.y() - mm.worldMinY()) / spanY * mm.h());
                int w = Math.max(4, (int) Math.round(CanvasGeometry.visualWidth(group) / spanX * mm.w()));
                int h = Math.max(4, (int) Math.round(CanvasGeometry.visualHeight(group) / spanY * mm.h()));
                gfx.fill(x1, y1, x1 + w, y1 + h, Theme.withAlpha(group.color(), 0x44));
                drawOutline(gfx, x1, y1, w, h, Theme.withAlpha(group.color(), 0xAA));
            }
        }

        // Foreground nodes
        for (PlanNode node : ctx.graph().nodes().values()) {
            if (node instanceof GroupNode || ctx.graph().isHiddenByCollapsedGroup(node.id())) {
                continue;
            }
            int x1 = mm.x() + (int) Math.round((node.x() - mm.worldMinX()) / spanX * mm.w());
            int y1 = mm.y() + (int) Math.round((node.y() - mm.worldMinY()) / spanY * mm.h());
            int w = Math.max(3, (int) Math.round(CanvasGeometry.visualWidth(node) / spanX * mm.w()));
            int h = Math.max(3, (int) Math.round(CanvasGeometry.visualHeight(node) / spanY * mm.h()));
            int color = node.id().equals(ctx.activeSearchNodeId())
                    ? 0xFFFBBF24
                    : (ctx.searchMatchedNodeIds().contains(node.id())
                    ? Theme.ACCENT_AMBER
                    : (ctx.selectedNodeIds().contains(node.id())
                    ? Theme.ACCENT_TEAL
                    : (node instanceof ProcessNode p && p.isVirtual() ? Theme.ACCENT_AMBER
                    : (node instanceof StickyNoteNode ? 0xFFFDE68A : 0xFF64748B))));
            gfx.fill(x1, y1, x1 + w, y1 + h, color);
        }

        // Current viewport rectangle
        CanvasViewport vp = ctx.viewport();
        double vLeft = vp.screenToWorldX(vp.viewX());
        double vRight = vp.screenToWorldX(vp.viewX() + vp.viewWidth());
        double vTop = vp.screenToWorldY(vp.viewY());
        double vBottom = vp.screenToWorldY(vp.viewY() + vp.viewHeight());
        int vx1 = mm.x() + (int) Math.round((vLeft - mm.worldMinX()) / spanX * mm.w());
        int vy1 = mm.y() + (int) Math.round((vTop - mm.worldMinY()) / spanY * mm.h());
        int vx2 = mm.x() + (int) Math.round((vRight - mm.worldMinX()) / spanX * mm.w());
        int vy2 = mm.y() + (int) Math.round((vBottom - mm.worldMinY()) / spanY * mm.h());
        int vw = Math.max(4, vx2 - vx1);
        int vh = Math.max(4, vy2 - vy1);
        gfx.fill(vx1, vy1, vx1 + vw, vy1 + vh, 0x222DD4BF);
        drawOutline(gfx, vx1, vy1, vw, vh, Theme.ACCENT_TEAL);

        gfx.disableScissor();
    }

    private static void drawGrid(GuiGraphics gfx, CanvasViewport vp) {
        double step = CanvasViewport.GRID_STEP;
        // Double grid spacing when zoomed far out so lines never crowd into solid fill
        while (step * vp.zoom() < 10.0) {
            step *= 4.0;
        }
        double majorStep = step * 5.0;

        double left = vp.screenToWorldX(vp.viewX());
        double right = vp.screenToWorldX(vp.viewX() + vp.viewWidth());
        double top = vp.screenToWorldY(vp.viewY());
        double bottom = vp.screenToWorldY(vp.viewY() + vp.viewHeight());

        double startX = Math.floor(left / step) * step;
        for (double wx = startX; wx <= right; wx += step) {
            int sx = (int) Math.round(vp.worldToScreenX(wx));
            boolean isOrigin = Math.abs(wx) < 0.001;
            boolean isMajor = Math.abs(Math.IEEEremainder(wx, majorStep)) < 0.001;
            int color = isOrigin ? Theme.withAlpha(Theme.GRID_ORIGIN, 0x55)
                    : (isMajor ? Theme.GRID_MAJOR : Theme.GRID_MINOR);
            gfx.fill(sx, vp.viewY(), sx + 1, vp.viewY() + vp.viewHeight(), color);
        }

        double startY = Math.floor(top / step) * step;
        for (double wy = startY; wy <= bottom; wy += step) {
            int sy = (int) Math.round(vp.worldToScreenY(wy));
            boolean isOrigin = Math.abs(wy) < 0.001;
            boolean isMajor = Math.abs(Math.IEEEremainder(wy, majorStep)) < 0.001;
            int color = isOrigin ? Theme.withAlpha(Theme.GRID_ORIGIN, 0x55)
                    : (isMajor ? Theme.GRID_MAJOR : Theme.GRID_MINOR);
            gfx.fill(vp.viewX(), sy, vp.viewX() + vp.viewWidth(), sy + 1, color);
        }
    }

    private static void drawGroupNode(GuiGraphics gfx, Font font, GroupNode group, RenderContext ctx) {
        int x = (int) Math.round(group.x());
        int y = (int) Math.round(group.y());
        int w = (int) Math.round(CanvasGeometry.visualWidth(group));
        int h = (int) Math.round(CanvasGeometry.visualHeight(group));

        boolean selected = ctx.selectedNodeIds().contains(group.id());
        int fill = Theme.withAlpha(group.color(), group.collapsed() ? 0xDD : 0x33);
        int headerFill = Theme.withAlpha(group.color(), 0xCC);
        int border = selected ? Theme.NODE_SELECTED_BORDER : Theme.withAlpha(group.color(), 0xCC);

        gfx.fill(x, y, x + w, y + h, fill);
        gfx.fill(x, y, x + w, y + (int) GroupNode.HEADER_HEIGHT, headerFill);
        drawOutline(gfx, x, y, w, h, border);

        if (ctx.viewport().zoom() >= 0.30) {
            String toggle = group.collapsed() ? "[+]" : "[-]";
            String label = toggle + " " + group.title();
            if (group.collapsed()) {
                label += " (" + group.childNodeIds().size() + ")";
            }
            String trimmed = font.plainSubstrByWidth(label, Math.max(20, w - 10));
            gfx.drawString(font, trimmed, x + 6, y + 8, Theme.TEXT_PRIMARY, false);
        }
    }

    private static void drawProcessNode(
            GuiGraphics gfx,
            Font font,
            ProcessNode node,
            RenderContext ctx,
            CanvasGeometry.FlowHighlight flowHighlight
    ) {
        int x = (int) Math.round(node.x());
        int y = (int) Math.round(node.y());
        int w = (int) Math.round(node.width());
        int h = (int) Math.round(node.height());

        boolean selected = ctx.selectedNodeIds().contains(node.id());
        boolean hovered = node.id().equals(ctx.hoveredNodeId());
        boolean inFlow = flowHighlight.isActive() && flowHighlight.nodeIds().contains(node.id());
        boolean activeSearch = node.id().equals(ctx.activeSearchNodeId());
        boolean searchMatch = ctx.searchMatchedNodeIds().contains(node.id());
        boolean virtual = node.isVirtual();
        ProcessNode.NodeRole role = node.role();
        boolean isSource = role == ProcessNode.NodeRole.SOURCE;
        boolean isSink = role == ProcessNode.NodeRole.SINK;

        int headerBg = isSource ? 0xFF102A24
                : (isSink ? 0xFF281838
                : (virtual ? Theme.NODE_VIRTUAL_HEADER_BG : Theme.NODE_HEADER_BG));
        int defaultBorder = isSource ? Theme.ACCENT_TEAL
                : (isSink ? 0xFFA855F7
                : (virtual ? Theme.NODE_VIRTUAL_BORDER
                : (hovered ? 0xFF64748B : Theme.NODE_BORDER)));
        int border = selected ? Theme.NODE_SELECTED_BORDER
                : (activeSearch ? 0xFFFBBF24
                : (inFlow ? 0xFF93C5FD : defaultBorder));

        // Shadow & body
        gfx.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x55000000);
        gfx.fill(x, y, x + w, y + h, Theme.NODE_BG);
        gfx.fill(x, y, x + w, y + (int) ProcessNode.HEADER_HEIGHT, headerBg);
        gfx.fill(x, y + (int) ProcessNode.HEADER_HEIGHT, x + w, y + (int) (ProcessNode.HEADER_HEIGHT + ProcessNode.META_HEIGHT), 0x660B1017);

        if (virtual) {
            drawDashedOutline(gfx, x, y, w, h, border);
        } else {
            drawOutline(gfx, x, y, w, h, border);
        }
        if (selected) {
            drawOutline(gfx, x - 1, y - 1, w + 2, h + 2, Theme.withAlpha(Theme.NODE_SELECTED_BORDER, 0x88));
        } else if (activeSearch) {
            drawOutline(gfx, x - 2, y - 2, w + 4, h + 4, 0xCCFBBF24);
        } else if (searchMatch) {
            drawOutline(gfx, x - 1, y - 1, w + 2, h + 2, 0x88F59E0B);
        } else if (inFlow) {
            drawOutline(gfx, x - 1, y - 1, w + 2, h + 2, 0x6693C5FD);
        }

        boolean drawText = ctx.viewport().zoom() >= 0.42;
        VoltageTier tier = VoltageTier.byIndex(node.tier());
        String tierName = tier.shortName();
        int badgeW = font.width(tierName) + 8;

        if (drawText) {
            // Tier pill on top-right
            int bx = x + w - badgeW - 5;
            int by = y + 6;
            gfx.fill(bx, by, bx + badgeW, by + 14, Theme.withAlpha(tier.badgeColor(), 0x38));
            drawOutline(gfx, bx, by, badgeW, 14, tier.badgeColor());
            gfx.drawString(font, tierName, bx + 4, by + 3, tier.badgeColor(), false);

            // Title (with role/sandbox tag if virtual or SOURCE/SINK)
            String prefix = isSource ? "[SRC] " : (isSink ? "[SINK] " : (virtual ? "[SIM] " : ""));
            int titleColor = isSource ? 0xFF5EEAD4 : (isSink ? 0xFFD8B4FE : (virtual ? 0xFFFDE68A : Theme.TEXT_PRIMARY));
            int maxTitleW = Math.max(20, w - badgeW - 18);
            String title = font.plainSubstrByWidth(prefix + node.title(), maxTitleW);
            gfx.drawString(font, title, x + 7, y + 9, titleColor, false);

            // Metadata subtitle line
            String meta = formatMetaLine(node);
            String trimmedMeta = font.plainSubstrByWidth(meta, Math.max(20, w - 12));
            gfx.drawString(font, trimmedMeta, x + 7, y + (int) ProcessNode.HEADER_HEIGHT + 4, Theme.TEXT_SECONDARY, false);
        }

        // Divider between inputs & outputs
        int portTopY = y + (int) (ProcessNode.HEADER_HEIGHT + ProcessNode.META_HEIGHT);
        gfx.fill(x + w / 2, portTopY + 2, x + w / 2 + 1, y + h - 4, 0x22FFFFFF);

        // Ports
        int halfW = (w / 2) - 10;
        for (ResourcePort port : node.inputs()) {
            drawPort(gfx, font, node, port, true, halfW, drawText, ctx);
        }
        for (ResourcePort port : node.outputs()) {
            drawPort(gfx, font, node, port, false, halfW, drawText, ctx);
        }

        // Solved stats pill at bottom of card
        if (drawText && ctx.solveResult() != null && ctx.solveResult().isSolved()) {
            NodeSolveStats stats = ctx.solveResult().nodeStats().get(node.id());
            if (stats != null && stats.craftsPerSecond() > 1e-6) {
                String badge = String.format("%sx (%d) • %s EU/t",
                        AMOUNT_FMT.format(stats.machinesExact()),
                        stats.machinesCeil(),
                        AMOUNT_FMT.format(stats.totalEut()));
                int bw = font.width(badge) + 8;
                int bx = x + (w - bw) / 2;
                int by = y + h + 2;
                gfx.fill(bx, by, bx + bw, by + 12, 0xEE0F172A);
                drawOutline(gfx, bx, by, bw, 12, Theme.ACCENT_TEAL);
                gfx.drawString(font, badge, bx + 4, by + 2, Theme.ACCENT_TEAL, false);
            }
        }

        // Audit / Friction flag indicator badge on top-left corner
        if (ctx.auditReport() != null) {
            List<FrictionFlag> nodeFlags = ctx.auditReport().forNode(node.id());
            if (!nodeFlags.isEmpty()) {
                int flagColor = nodeFlags.stream()
                        .map(f -> f.severity().color())
                        .findFirst()
                        .orElse(Theme.ACCENT_AMBER);
                String tag = "!" + nodeFlags.size();
                int tw = font.width(tag) + 15;
                int fx = x - 2;
                int fy = y - 10;
                gfx.fill(fx, fy, fx + tw, fy + 10, 0xEE0D1117);
                drawOutline(gfx, fx, fy, tw, 10, flagColor);
                gfx.blit(Theme.ICON_WARNING, fx + 2, fy + 1, 0, 0, 8, 8, 8, 8);
                gfx.drawString(font, tag, fx + 12, fy + 1, flagColor, false);
            }
        }
    }

    private static void drawPort(
            GuiGraphics gfx,
            Font font,
            ProcessNode node,
            ResourcePort port,
            boolean isInput,
            int maxLabelWidth,
            boolean drawText,
            RenderContext ctx
    ) {
        Vec2 pos = CanvasGeometry.portWorldPos(node, port);
        int px = (int) Math.round(pos.x());
        int py = (int) Math.round(pos.y());

        boolean hovered = ctx.hoveredPort() != null
                && ctx.hoveredPort().nodeId().equals(node.id())
                && ctx.hoveredPort().port().id().equals(port.id());
        boolean wiringSource = ctx.wiringSourcePort() != null
                && ctx.wiringSourcePort().nodeId().equals(node.id())
                && ctx.wiringSourcePort().port().id().equals(port.id());

        int baseColor = port.resource().kind().color();
        int radius = (hovered || wiringSource) ? 5 : 4;
        gfx.fill(px - radius, py - radius, px + radius, py + radius, (hovered || wiringSource) ? 0xFFFFFFFF : baseColor);
        gfx.fill(px - radius + 1, py - radius + 1, px + radius - 1, py + radius - 1,
                port.catalyst() ? 0xFF0F172A : baseColor);

        if (!drawText) {
            return;
        }
        boolean drawIcon = ctx.viewport().zoom() >= 0.55;
        int iconSize = 10;
        String qty = formatPortAmount(port);
        String text = qty + " " + port.displayLabel();
        int textBudget = Math.max(12, maxLabelWidth - (drawIcon ? iconSize + 4 : 0));
        String clipped = font.plainSubstrByWidth(text, textBudget);
        int textColor = port.catalyst() ? 0xFFC4B5FD : Theme.TEXT_PRIMARY;
        if (isInput) {
            int textX = px + 8;
            if (drawIcon) {
                ItemFluidIconRenderer.render(gfx, port.resource(), px + 7, py - 5, iconSize);
                textX += iconSize + 3;
            }
            gfx.drawString(font, clipped, textX, py - 4, textColor, false);
        } else {
            int tw = font.width(clipped);
            int textX = px - 8 - tw;
            if (drawIcon) {
                ItemFluidIconRenderer.render(gfx, port.resource(), px - 7 - iconSize, py - 5, iconSize);
                textX -= (iconSize + 3);
            }
            gfx.drawString(font, clipped, textX, py - 4, textColor, false);
        }
    }

    private static void drawStickyNote(GuiGraphics gfx, Font font, StickyNoteNode note, RenderContext ctx) {
        int x = (int) Math.round(note.x());
        int y = (int) Math.round(note.y());
        int w = (int) Math.round(note.width());
        int h = (int) Math.round(note.height());

        boolean selected = ctx.selectedNodeIds().contains(note.id());
        int bg = Theme.withAlpha(note.color(), 0xE8);
        int headerBg = darken(note.color(), 0.78f);
        int border = selected ? Theme.NODE_SELECTED_BORDER : darken(note.color(), 0.55f);

        gfx.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x44000000);
        gfx.fill(x, y, x + w, y + h, bg);
        gfx.fill(x, y, x + w, y + 20, headerBg);
        drawOutline(gfx, x, y, w, h, border);

        if (ctx.viewport().zoom() < 0.38) {
            return;
        }
        String title = font.plainSubstrByWidth(note.title().isBlank() ? "Note" : note.title(), Math.max(16, w - 12));
        gfx.drawString(font, title, x + 6, y + 6, 0xFF1E293B, false);

        List<FormattedCharSequence> lines = font.split(Component.literal(note.text()), Math.max(20, w - 12));
        int lineY = y + 25;
        for (FormattedCharSequence line : lines) {
            if (lineY + 9 > y + h - 4) {
                break;
            }
            gfx.drawString(font, line, x + 6, lineY, 0xFF0F172A, false);
            lineY += 10;
        }
    }

    private static Vec2 wireMidpoint(List<Vec2> points, Vec2 fallbackStart, Vec2 fallbackEnd) {
        if (points.size() < 2) {
            return new Vec2((fallbackStart.x() + fallbackEnd.x()) * 0.5, (fallbackStart.y() + fallbackEnd.y()) * 0.5);
        }
        if (points.size() <= 6) {
            int seg = (points.size() - 1) / 2;
            Vec2 a = points.get(seg);
            Vec2 b = points.get(seg + 1);
            return new Vec2((a.x() + b.x()) * 0.5, (a.y() + b.y()) * 0.5);
        }
        return points.get(points.size() / 2);
    }

    private static void drawWire(PoseStack pose, List<Vec2> points, int argb, float halfWidth) {
        if (points.size() < 2) {
            return;
        }

        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;

        Matrix4f matrix = pose.last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < points.size(); i++) {
            Vec2 curr = points.get(i);
            Vec2 prev = i == 0 ? curr : points.get(i - 1);
            Vec2 next = i == points.size() - 1 ? curr : points.get(i + 1);
            double tx = next.x() - prev.x();
            double ty = next.y() - prev.y();
            double len = Math.hypot(tx, ty);
            if (len < 1e-6) {
                len = 1.0;
            }
            double nx = -ty / len * (halfWidth * 0.5);
            double ny = tx / len * (halfWidth * 0.5);

            builder.vertex(matrix, (float) (curr.x() + nx), (float) (curr.y() + ny), 0f)
                    .color(r, g, b, a).endVertex();
            builder.vertex(matrix, (float) (curr.x() - nx), (float) (curr.y() - ny), 0f)
                    .color(r, g, b, a).endVertex();
        }
        BufferUploader.drawWithShader(builder.end());

        // Directional chevron at midpoint of middle segment
        int midSeg = (points.size() - 1) / 2;
        Vec2 segA = points.get(midSeg);
        Vec2 segB = points.get(midSeg + 1);
        Vec2 mid = new Vec2((segA.x() + segB.x()) * 0.5, (segA.y() + segB.y()) * 0.5);
        double vx = segB.x() - segA.x();
        double vy = segB.y() - segA.y();
        double len = Math.hypot(vx, vy);
        if (len > 1e-6) {
            double ux = vx / len;
            double uy = vy / len;
            double px = -uy;
            double py = ux;
            float tipX = (float) (mid.x() + ux * 5.0);
            float tipY = (float) (mid.y() + uy * 5.0);
            float leftX = (float) (mid.x() - ux * 3.5 + px * 4.0);
            float leftY = (float) (mid.y() - uy * 3.5 + py * 4.0);
            float rightX = (float) (mid.x() - ux * 3.5 - px * 4.0);
            float rightY = (float) (mid.y() - uy * 3.5 - py * 4.0);

            builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            builder.vertex(matrix, tipX, tipY, 0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, leftX, leftY, 0f).color(r, g, b, a).endVertex();
            builder.vertex(matrix, rightX, rightY, 0f).color(r, g, b, a).endVertex();
            BufferUploader.drawWithShader(builder.end());
        }
        RenderSystem.disableBlend();
    }

    private static void drawOutline(GuiGraphics gfx, int x, int y, int w, int h, int color) {
        gfx.fill(x, y, x + w, y + 1, color);
        gfx.fill(x, y + h - 1, x + w, y + h, color);
        gfx.fill(x, y, x + 1, y + h, color);
        gfx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void drawDashedOutline(GuiGraphics gfx, int x, int y, int w, int h, int color) {
        int dash = 6;
        int gap = 4;
        for (int dx = 0; dx < w; dx += dash + gap) {
            int segEnd = Math.min(w, dx + dash);
            gfx.fill(x + dx, y, x + segEnd, y + 1, color);
            gfx.fill(x + dx, y + h - 1, x + segEnd, y + h, color);
        }
        for (int dy = 0; dy < h; dy += dash + gap) {
            int segEnd = Math.min(h, dy + dash);
            gfx.fill(x, y + dy, x + 1, y + segEnd, color);
            gfx.fill(x + w - 1, y + dy, x + w, y + segEnd, color);
        }
    }

    private static String formatMetaLine(ProcessNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.eut() != 0) {
            sb.append(node.eut()).append(" EU/t");
        } else {
            sb.append("0 EU/t");
        }
        sb.append(" • ").append(AMOUNT_FMT.format(node.durationTicks() / 20.0)).append("s");
        if (node.circuit() != null) {
            sb.append(" • C").append(node.circuit());
        }
        if (node.coilTemp() > 0) {
            sb.append(" • ").append(node.coilTemp()).append("K");
        }
        if (node.cleanroom()) {
            sb.append(" • CR");
        }
        if (node instanceof RecipeNode r && r.targetRate() != null) {
            sb.append(" • Target ").append(AMOUNT_FMT.format(r.targetRate())).append("/s");
        } else if (node instanceof VirtualRecipeNode v && v.targetRate() != null) {
            sb.append(" • Target ").append(AMOUNT_FMT.format(v.targetRate())).append("/s");
        }
        return sb.toString();
    }

    private static String formatPortAmount(ResourcePort port) {
        String base = AMOUNT_FMT.format(port.amount());
        if (port.resource().kind() == ResourceKind.FLUID) {
            base += "mB";
        } else if (port.resource().kind() == ResourceKind.ENERGY) {
            base += "EU";
        } else {
            base += "x";
        }
        if (port.chance() < 0.9999) {
            base += " (" + (int) Math.round(port.chance() * 100) + "%)";
        }
        return base;
    }

    private static int darken(int argb, float factor) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.max(0, Math.min(255, Math.round(((argb >>> 16) & 0xFF) * factor)));
        int g = Math.max(0, Math.min(255, Math.round(((argb >>> 8) & 0xFF) * factor)));
        int b = Math.max(0, Math.min(255, Math.round((argb & 0xFF) * factor)));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
