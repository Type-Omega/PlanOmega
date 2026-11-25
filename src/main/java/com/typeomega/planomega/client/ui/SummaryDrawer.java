package com.typeomega.planomega.client.ui;

import com.typeomega.planomega.audit.AuditReport;
import com.typeomega.planomega.audit.FrictionFlag;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PlanNode;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.solver.SolveResult;
import com.typeomega.planomega.solver.SolveResult.NodeSolveStats;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Collapsible right-hand summary drawer displaying LP-solved raw material imports,
 * final/surplus exports, total EU/t, machine counts, and clickable bottleneck nodes.
 */
public final class SummaryDrawer {

    public static final int WIDTH = 236;
    private static final DecimalFormat RATE_FMT = new DecimalFormat("#.##");

    private final List<ClickableRow> clickableNodes = new ArrayList<>();

    public void render(
            GuiGraphics gfx,
            Font font,
            PlanGraph graph,
            SolveResult result,
            AuditReport audit,
            int x,
            int y,
            int height,
            int mouseX,
            int mouseY
    ) {
        clickableNodes.clear();

        gfx.fill(x, y, x + WIDTH, y + height, Theme.BAR_BG);
        gfx.fill(x, y, x + 1, y + height, Theme.BAR_BORDER);

        int cy = y + 8;
        int padX = x + 10;
        int innerW = WIDTH - 20;

        // Title + Solver Status badge
        gfx.drawString(font, Component.translatable("planomega.summary.title"), padX, cy, Theme.TEXT_PRIMARY, false);
        String statusText = statusLabel(result.status());
        int statusColor = statusColor(result.status());
        int sw = font.width(statusText);
        gfx.drawString(font, statusText, x + WIDTH - sw - 10, cy, statusColor, false);
        cy += 16;

        gfx.fill(padX, cy, x + WIDTH - 10, cy + 1, Theme.BAR_BORDER);
        cy += 6;

        if (!result.isSolved()) {
            String msg = result.status() == SolveResult.Status.NO_TARGETS
                    ? Component.translatable("planomega.summary.no_targets").getString()
                    : (result.message() != null ? result.message() : "Infeasible");
            for (var line : font.split(Component.literal(msg), innerW)) {
                gfx.drawString(font, line, padX, cy, Theme.TEXT_MUTED, false);
                cy += 10;
            }
            return;
        }

        // Energy & Machines overview
        gfx.drawString(font, String.format("Consumed: %s EU/t", RATE_FMT.format(result.totalConsumptionEut())),
                padX, cy, Theme.ACCENT_AMBER, false);
        cy += 11;
        if (result.totalGenerationEut() > 0.01) {
            gfx.drawString(font, String.format("Generated: %s EU/t", RATE_FMT.format(result.totalGenerationEut())),
                    padX, cy, ResourceKind.ENERGY.color(), false);
            cy += 11;
        }
        gfx.drawString(font, String.format("Machines: %d  |  Net: %s EU/t",
                        result.totalMachinesCeil(), RATE_FMT.format(result.netEut())),
                padX, cy, Theme.TEXT_SECONDARY, false);
        cy += 14;

        // Raw Material Imports
        cy = renderResourceSection(gfx, font, Component.translatable("planomega.summary.imports").getString(),
                result.netImports(), padX, cy, innerW, y + height, 0xFFFB923C);
        cy += 6;

        // Net Exports / Byproducts
        cy = renderResourceSection(gfx, font, Component.translatable("planomega.summary.exports").getString(),
                result.netExports(), padX, cy, innerW, y + height, Theme.ACCENT_TEAL);
        cy += 6;

        // Bottlenecks (top machines/EU)
        if (cy + 24 < y + height) {
            gfx.drawString(font, Component.translatable("planomega.summary.bottlenecks"), padX, cy, Theme.TEXT_PRIMARY, false);
            cy += 12;

            List<NodeSolveStats> sorted = result.nodeStats().values().stream()
                    .filter(s -> s.craftsPerSecond() > 1e-6)
                    .sorted(Comparator.comparingDouble((NodeSolveStats s) -> Math.abs(s.totalEut())).reversed()
                            .thenComparing(Comparator.comparingDouble(NodeSolveStats::machinesExact).reversed()))
                    .limit(5)
                    .toList();

            for (NodeSolveStats stat : sorted) {
                if (cy + 16 > y + height - 6) {
                    break;
                }
                PlanNode node = graph.nodes().get(stat.nodeId());
                if (!(node instanceof ProcessNode proc)) {
                    continue;
                }
                boolean hovered = mouseX >= padX && mouseX <= padX + innerW && mouseY >= cy && mouseY < cy + 14;
                if (hovered) {
                    gfx.fill(padX - 2, cy - 1, padX + innerW + 2, cy + 13, Theme.BTN_HOVER);
                }
                String label = font.plainSubstrByWidth(proc.title(), innerW - 72);
                String metric = String.format("%sx %.0fEU", RATE_FMT.format(stat.machinesExact()), stat.totalEut());
                int mw = font.width(metric);
                gfx.drawString(font, label, padX, cy + 2, Theme.TEXT_SECONDARY, false);
                gfx.drawString(font, metric, padX + innerW - mw, cy + 2, Theme.ACCENT_AMBER, false);
                clickableNodes.add(new ClickableRow(padX - 2, cy - 1, innerW + 4, 14, proc.id()));
                cy += 15;
            }
        }

        // Audit / Friction Flags
        if (audit != null && !audit.flags().isEmpty() && cy + 26 < y + height) {
            cy += 4;
            gfx.drawString(font, Component.translatable("planomega.summary.audit", audit.flags().size()),
                    padX, cy, Theme.ACCENT_AMBER, false);
            cy += 12;

            for (FrictionFlag flag : audit.flags()) {
                if (cy + 14 > y + height - 4) {
                    break;
                }
                boolean hovered = flag.nodeId() != null
                        && mouseX >= padX && mouseX <= padX + innerW
                        && mouseY >= cy && mouseY < cy + 13;
                if (hovered) {
                    gfx.fill(padX - 2, cy - 1, padX + innerW + 2, cy + 12, Theme.BTN_HOVER);
                }
                String line = font.plainSubstrByWidth("• " + flag.title(), innerW);
                gfx.drawString(font, line, padX, cy + 1, flag.severity().color(), false);
                if (flag.nodeId() != null) {
                    clickableNodes.add(new ClickableRow(padX - 2, cy - 1, innerW + 4, 13, flag.nodeId()));
                }
                cy += 13;
            }
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button, Consumer<String> onFocusNode) {
        if (button != 0) {
            return false;
        }
        for (ClickableRow row : clickableNodes) {
            if (mouseX >= row.x && mouseX <= row.x + row.w && mouseY >= row.y && mouseY <= row.y + row.h) {
                onFocusNode.accept(row.nodeId);
                return true;
            }
        }
        return false;
    }

    private static int renderResourceSection(
            GuiGraphics gfx,
            Font font,
            String title,
            Map<ResourceKey, Double> map,
            int padX,
            int cy,
            int innerW,
            int maxBottom,
            int accentColor
    ) {
        if (cy + 20 >= maxBottom) {
            return cy;
        }
        gfx.drawString(font, title, padX, cy, Theme.TEXT_PRIMARY, false);
        cy += 12;

        if (map.isEmpty()) {
            gfx.drawString(font, "-", padX + 4, cy, Theme.TEXT_MUTED, false);
            return cy + 12;
        }

        int shown = 0;
        for (Map.Entry<ResourceKey, Double> entry : map.entrySet()) {
            if (cy + 14 > maxBottom - 20 || shown >= 7) {
                break;
            }
            ResourceKey key = entry.getKey();
            double rate = entry.getValue();
            ItemFluidIconRenderer.render(gfx, key, padX, cy, 10);

            String rateStr = formatRate(key, rate);
            int rw = font.width(rateStr);
            String name = font.plainSubstrByWidth(key.shortName(), Math.max(24, innerW - rw - 16));
            gfx.drawString(font, name, padX + 14, cy + 1, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, rateStr, padX + innerW - rw, cy + 1, accentColor, false);
            cy += 13;
            shown++;
        }
        return cy;
    }

    private static String formatRate(ResourceKey key, double perSec) {
        if (key.kind() == ResourceKind.FLUID) {
            return RATE_FMT.format(perSec) + " mB/s";
        }
        return RATE_FMT.format(perSec) + "/s";
    }

    private static String statusLabel(SolveResult.Status status) {
        return switch (status) {
            case OPTIMAL -> "OPTIMAL";
            case FEASIBLE -> "FEASIBLE";
            case INFEASIBLE -> "INFEASIBLE";
            case NO_TARGETS -> "IDLE";
            case ERROR -> "ERROR";
        };
    }

    private static int statusColor(SolveResult.Status status) {
        return switch (status) {
            case OPTIMAL, FEASIBLE -> 0xFF4ADE80;
            case INFEASIBLE, ERROR -> 0xFFEF4444;
            case NO_TARGETS -> Theme.TEXT_MUTED;
        };
    }

    private record ClickableRow(int x, int y, int w, int h, String nodeId) {
    }
}
