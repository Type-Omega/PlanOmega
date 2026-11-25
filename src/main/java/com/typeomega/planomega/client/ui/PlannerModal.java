package com.typeomega.planomega.client.ui;

import com.typeomega.planomega.audit.AuditReport;
import com.typeomega.planomega.client.storage.PlanStorage;
import com.typeomega.planomega.codec.DemoGraphFactory;
import com.typeomega.planomega.codec.PlanCodec;
import com.typeomega.planomega.export.AiAnalysisExporter;
import com.typeomega.planomega.export.MermaidExporter;
import com.typeomega.planomega.export.SandboxDiffExporter;
import com.typeomega.planomega.recipe.RecipeCatalogManager;
import com.typeomega.planomega.solver.SolveResult;
import com.typeomega.planomega.model.GroupNode;
import com.typeomega.planomega.model.PlanGraph;
import com.typeomega.planomega.model.PortDirection;
import com.typeomega.planomega.model.ProcessNode;
import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourceKind;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.StickyNoteNode;
import com.typeomega.planomega.model.VirtualRecipeNode;
import com.typeomega.planomega.model.VoltageTier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Lightweight inline modal overlays hosted inside {@link PlannerScreen} for editing
 * sticky notes, sandbox virtual nodes, groups, and local plan management.
 */
public abstract class PlannerModal {

    protected final int width;
    protected final int height;
    protected final List<EditBox> editBoxes = new ArrayList<>();
    protected final List<ModalButton> buttons = new ArrayList<>();
    protected boolean closed = false;

    protected PlannerModal(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        this.closed = true;
    }

    protected int left(int screenW) {
        return (screenW - width) / 2;
    }

    protected int top(int screenH) {
        return (screenH - height) / 2;
    }

    public abstract void init(Font font, int screenW, int screenH);

    public void tick() {
        for (EditBox box : editBoxes) {
            box.tick();
        }
    }

    public void render(GuiGraphics gfx, Font font, int screenW, int screenH, int mouseX, int mouseY, float partialTick) {
        gfx.fill(0, 0, screenW, screenH, Theme.MODAL_BACKDROP);
        int x = left(screenW);
        int y = top(screenH);
        gfx.fill(x, y, x + width, y + height, Theme.MODAL_BG);
        drawOutline(gfx, x, y, width, height, Theme.BAR_BORDER);

        renderContents(gfx, font, x, y, mouseX, mouseY, partialTick);

        for (EditBox box : editBoxes) {
            box.render(gfx, mouseX, mouseY, partialTick);
        }
        for (ModalButton btn : buttons) {
            btn.render(gfx, font, mouseX, mouseY);
        }
    }

    protected abstract void renderContents(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY, float partialTick);

    public boolean mouseClicked(double mouseX, double mouseY, int button, int screenW, int screenH) {
        if (button == 0) {
            for (ModalButton btn : buttons) {
                if (btn.isHovered(mouseX, mouseY)) {
                    btn.onClick.run();
                    return true;
                }
            }
            for (EditBox box : editBoxes) {
                boolean hit = box.mouseClicked(mouseX, mouseY, button);
                box.setFocused(hit);
            }
        }
        int x = left(screenW);
        int y = top(screenH);
        if (mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
            close();
        }
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        for (EditBox box : editBoxes) {
            if (box.isFocused() && box.keyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        }
        return true;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        for (EditBox box : editBoxes) {
            if (box.isFocused() && box.charTyped(codePoint, modifiers)) {
                return true;
            }
        }
        return true;
    }

    public boolean mouseScrolled(double delta) {
        return true;
    }

    protected static void drawOutline(GuiGraphics gfx, int x, int y, int w, int h, int color) {
        gfx.fill(x, y, x + w, y + 1, color);
        gfx.fill(x, y + h - 1, x + w, y + h, color);
        gfx.fill(x, y, x + 1, y + h, color);
        gfx.fill(x + w - 1, y, x + w, y + h, color);
    }

    protected record ModalButton(int x, int y, int w, int h, String label, int accentColor, Runnable onClick) {
        boolean isHovered(double mx, double my) {
            return mx >= x && mx <= x + w && my >= y && my <= y + h;
        }

        void render(GuiGraphics gfx, Font font, int mx, int my) {
            boolean hovered = isHovered(mx, my);
            gfx.fill(x, y, x + w, y + h, hovered ? Theme.BTN_HOVER : Theme.BTN_BG);
            drawOutline(gfx, x, y, w, h, hovered ? accentColor : Theme.BTN_BORDER);
            int tw = font.width(label);
            gfx.drawString(font, label, x + (w - tw) / 2, y + (h - 8) / 2, Theme.TEXT_PRIMARY, false);
        }
    }

    /**
     * Modal for editing a {@link StickyNoteNode}'s title and body text.
     */
    public static final class NoteEditorModal extends PlannerModal {
        private final StickyNoteNode note;
        private final Consumer<StickyNoteNode> onSave;
        private EditBox titleBox;
        private EditBox bodyBox;

        public NoteEditorModal(StickyNoteNode note, Consumer<StickyNoteNode> onSave) {
            super(340, 175);
            this.note = note;
            this.onSave = onSave;
        }

        @Override
        public void init(Font font, int screenW, int screenH) {
            editBoxes.clear();
            buttons.clear();
            int x = left(screenW);
            int y = top(screenH);

            titleBox = new EditBox(font, x + 14, y + 36, width - 28, 18, Component.empty());
            titleBox.setMaxLength(64);
            titleBox.setValue(note.title());
            titleBox.setFocused(true);
            editBoxes.add(titleBox);

            bodyBox = new EditBox(font, x + 14, y + 78, width - 28, 18, Component.empty());
            bodyBox.setMaxLength(280);
            bodyBox.setValue(note.text().replace("\n", " | "));
            editBoxes.add(bodyBox);

            buttons.add(new ModalButton(x + width - 166, y + height - 32, 72, 20,
                    Component.translatable("planomega.ui.cancel").getString(), Theme.BTN_BORDER, this::close));
            buttons.add(new ModalButton(x + width - 86, y + height - 32, 72, 20,
                    Component.translatable("planomega.ui.apply").getString(), Theme.ACCENT_TEAL, () -> {
                String body = bodyBox.getValue().replace(" | ", "\n");
                onSave.accept(note.withContent(titleBox.getValue(), body, note.color()));
                close();
            }));
        }

        @Override
        protected void renderContents(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY, float partialTick) {
            gfx.drawString(font, Component.translatable("planomega.modal.note.title"), x + 14, y + 10, Theme.ACCENT_TEAL, false);
            gfx.drawString(font, Component.translatable("planomega.modal.field.title"), x + 14, y + 25, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, Component.translatable("planomega.modal.note.body_hint"), x + 14, y + 65, Theme.TEXT_SECONDARY, false);
        }
    }

    /**
     * Modal for renaming a {@link GroupNode}.
     */
    public static final class GroupRenameModal extends PlannerModal {
        private final GroupNode group;
        private final Consumer<GroupNode> onSave;
        private EditBox titleBox;

        public GroupRenameModal(GroupNode group, Consumer<GroupNode> onSave) {
            super(300, 120);
            this.group = group;
            this.onSave = onSave;
        }

        @Override
        public void init(Font font, int screenW, int screenH) {
            editBoxes.clear();
            buttons.clear();
            int x = left(screenW);
            int y = top(screenH);

            titleBox = new EditBox(font, x + 14, y + 38, width - 28, 18, Component.empty());
            titleBox.setMaxLength(64);
            titleBox.setValue(group.title());
            titleBox.setFocused(true);
            editBoxes.add(titleBox);

            buttons.add(new ModalButton(x + width - 166, y + height - 30, 72, 20,
                    Component.translatable("planomega.ui.cancel").getString(), Theme.BTN_BORDER, this::close));
            buttons.add(new ModalButton(x + width - 86, y + height - 30, 72, 20,
                    Component.translatable("planomega.ui.apply").getString(), Theme.ACCENT_TEAL, () -> {
                onSave.accept(group.withTitle(titleBox.getValue()));
                close();
            }));
        }

        @Override
        protected void renderContents(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY, float partialTick) {
            gfx.drawString(font, Component.translatable("planomega.modal.group.title"), x + 14, y + 12, Theme.ACCENT_TEAL, false);
        }
    }

    /**
     * Quick Sandbox editor for creating/editing a {@link VirtualRecipeNode} directly in-game.
     */
    public static final class VirtualNodeEditorModal extends PlannerModal {
        private final VirtualRecipeNode node;
        private final Consumer<VirtualRecipeNode> onSave;
        private EditBox titleBox;
        private EditBox eutBox;
        private EditBox ticksBox;
        private EditBox inputsBox;
        private EditBox outputsBox;

        public VirtualNodeEditorModal(VirtualRecipeNode node, Consumer<VirtualRecipeNode> onSave) {
            super(400, 265);
            this.node = node;
            this.onSave = onSave;
        }

        @Override
        public void init(Font font, int screenW, int screenH) {
            editBoxes.clear();
            buttons.clear();
            int x = left(screenW);
            int y = top(screenH);

            titleBox = new EditBox(font, x + 14, y + 36, width - 28, 18, Component.empty());
            titleBox.setMaxLength(80);
            titleBox.setValue(node.title());
            titleBox.setFocused(true);
            editBoxes.add(titleBox);

            eutBox = new EditBox(font, x + 14, y + 76, 175, 18, Component.empty());
            eutBox.setMaxLength(16);
            eutBox.setValue(String.valueOf(node.eut()));
            editBoxes.add(eutBox);

            ticksBox = new EditBox(font, x + 205, y + 76, 181, 18, Component.empty());
            ticksBox.setMaxLength(12);
            ticksBox.setValue(String.valueOf(node.durationTicks()));
            editBoxes.add(ticksBox);

            inputsBox = new EditBox(font, x + 14, y + 118, width - 28, 18, Component.empty());
            inputsBox.setMaxLength(256);
            inputsBox.setValue(encodePortCsv(node.inputs()));
            editBoxes.add(inputsBox);

            outputsBox = new EditBox(font, x + 14, y + 160, width - 28, 18, Component.empty());
            outputsBox.setMaxLength(256);
            outputsBox.setValue(encodePortCsv(node.outputs()));
            editBoxes.add(outputsBox);

            buttons.add(new ModalButton(x + width - 166, y + height - 32, 72, 20,
                    Component.translatable("planomega.ui.cancel").getString(), Theme.BTN_BORDER, this::close));
            buttons.add(new ModalButton(x + width - 86, y + height - 32, 72, 20,
                    Component.translatable("planomega.ui.apply").getString(), Theme.ACCENT_AMBER, this::apply));
        }

        private void apply() {
            long eut = parseLong(eutBox.getValue(), node.eut());
            int ticks = Math.max(1, (int) parseLong(ticksBox.getValue(), node.durationTicks()));
            List<ResourcePort> inputs = parsePortCsv(inputsBox.getValue(), PortDirection.INPUT);
            List<ResourcePort> outputs = parsePortCsv(outputsBox.getValue(), PortDirection.OUTPUT);
            if (inputs.isEmpty()) {
                inputs = node.inputs();
            }
            if (outputs.isEmpty()) {
                outputs = node.outputs();
            }
            int tier = VoltageTier.fromEut(eut).index();
            VirtualRecipeNode updated = node.withEditedSpec(
                    titleBox.getValue().isBlank() ? "Virtual Step" : titleBox.getValue(),
                    node.recipeTypeId(),
                    inputs,
                    outputs,
                    eut,
                    ticks,
                    tier,
                    node.circuit(),
                    node.coilTemp(),
                    node.cleanroom(),
                    node.perfectOverclock(),
                    node.notes()
            );
            onSave.accept(updated);
            close();
        }

        @Override
        protected void renderContents(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY, float partialTick) {
            gfx.drawString(font, Component.translatable("planomega.modal.sandbox.title"), x + 14, y + 10, Theme.ACCENT_AMBER, false);
            gfx.drawString(font, Component.translatable("planomega.modal.field.title"), x + 14, y + 25, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, "EU/t:", x + 14, y + 64, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, Component.translatable("planomega.modal.sandbox.ticks"), x + 205, y + 64, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, Component.translatable("planomega.modal.sandbox.inputs"), x + 14, y + 106, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, Component.translatable("planomega.modal.sandbox.outputs"), x + 14, y + 148, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, Component.translatable("planomega.modal.sandbox.format_hint"), x + 14, y + 188, Theme.TEXT_MUTED, false);
        }

        private static String encodePortCsv(List<ResourcePort> ports) {
            List<String> parts = new ArrayList<>();
            for (ResourcePort p : ports) {
                String prefix = p.resource().kind() == ResourceKind.FLUID ? "f:" : "i:";
                parts.add(prefix + p.resource().id() + "*" + (long) p.amount());
            }
            return String.join(", ", parts);
        }

        private static List<ResourcePort> parsePortCsv(String raw, PortDirection dir) {
            List<ResourcePort> list = new ArrayList<>();
            if (raw == null || raw.isBlank()) {
                return list;
            }
            int idx = 0;
            for (String token : raw.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                boolean fluid = false;
                if (t.startsWith("f:")) {
                    fluid = true;
                    t = t.substring(2).trim();
                } else if (t.startsWith("i:")) {
                    t = t.substring(2).trim();
                }
                double amount = 1.0;
                int star = t.lastIndexOf('*');
                if (star > 0) {
                    amount = parseDouble(t.substring(star + 1).trim(), 1.0);
                    t = t.substring(0, star).trim();
                }
                if (t.isEmpty()) {
                    continue;
                }
                ResourceKey key = fluid ? ResourceKey.fluid(t) : ResourceKey.item(t);
                String pid = (dir == PortDirection.INPUT ? "in_" : "out_") + (idx++);
                list.add(dir == PortDirection.INPUT
                        ? ResourcePort.input(pid, key, Math.max(0.001, amount))
                        : ResourcePort.output(pid, key, Math.max(0.001, amount)));
            }
            return list;
        }

        private static long parseLong(String s, long fallback) {
            try {
                return Long.parseLong(s.trim());
            } catch (Exception e) {
                return fallback;
            }
        }

        private static double parseDouble(String s, double fallback) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception e) {
                return fallback;
            }
        }
    }

    /**
     * Modal for saving, loading, renaming, creating demo/empty graphs, and clipboard share codes.
     */
    public static final class PlansManagerModal extends PlannerModal {
        private final PlanGraph activeGraph;
        private final Consumer<PlanGraph> onLoadGraph;
        private final Consumer<String> onToast;
        private EditBox nameBox;
        private EditBox shareBox;
        private List<PlanStorage.SavedPlanEntry> savedPlans = List.of();
        private final List<ModalButton> dynamicListButtons = new ArrayList<>();

        public PlansManagerModal(PlanGraph activeGraph, Consumer<PlanGraph> onLoadGraph, Consumer<String> onToast) {
            super(420, 300);
            this.activeGraph = activeGraph;
            this.onLoadGraph = onLoadGraph;
            this.onToast = onToast;
        }

        @Override
        public void init(Font font, int screenW, int screenH) {
            editBoxes.clear();
            buttons.clear();
            dynamicListButtons.clear();
            int x = left(screenW);
            int y = top(screenH);

            nameBox = new EditBox(font, x + 14, y + 34, 240, 18, Component.empty());
            nameBox.setMaxLength(64);
            nameBox.setValue(activeGraph.name());
            editBoxes.add(nameBox);

            buttons.add(new ModalButton(x + 262, y + 34, 68, 18,
                    Component.translatable("planomega.ui.save").getString(), Theme.ACCENT_TEAL, () -> {
                PlanGraph renamed = activeGraph.withName(nameBox.getValue());
                try {
                    PlanStorage.savePlan(renamed);
                    onLoadGraph.accept(renamed);
                    onToast.accept(Component.translatable("planomega.toast.saved", renamed.name()).getString());
                    refreshSavedList(x, y);
                } catch (Exception e) {
                    onToast.accept("Save failed: " + e.getMessage());
                }
            }));

            buttons.add(new ModalButton(x + 336, y + 34, 70, 18,
                    Component.translatable("planomega.ui.new_empty").getString(), Theme.BTN_BORDER, () -> {
                onLoadGraph.accept(PlanGraph.empty("New Plan"));
                close();
            }));

            shareBox = new EditBox(font, x + 14, y + 72, 240, 18, Component.empty());
            shareBox.setMaxLength(32767);
            editBoxes.add(shareBox);

            buttons.add(new ModalButton(x + 262, y + 72, 68, 18,
                    Component.translatable("planomega.ui.copy_code").getString(), Theme.ACCENT_TEAL, () -> {
                try {
                    String code = PlanCodec.encodeShareString(activeGraph);
                    Minecraft.getInstance().keyboardHandler.setClipboard(code);
                    shareBox.setValue(code);
                    onToast.accept(Component.translatable("planomega.toast.copied_share").getString());
                } catch (Exception e) {
                    onToast.accept("Export error: " + e.getMessage());
                }
            }));

            buttons.add(new ModalButton(x + 336, y + 72, 70, 18,
                    Component.translatable("planomega.ui.import_code").getString(), Theme.ACCENT_AMBER, () -> {
                String raw = shareBox.getValue();
                if (raw.isBlank()) {
                    raw = Minecraft.getInstance().keyboardHandler.getClipboard();
                }
                try {
                    PlanGraph imported = PlanCodec.decodeShareString(raw);
                    onLoadGraph.accept(imported);
                    onToast.accept(Component.translatable("planomega.toast.imported", imported.name()).getString());
                    close();
                } catch (Exception e) {
                    onToast.accept("Invalid share code");
                }
            }));

            buttons.add(new ModalButton(x + 14, y + height - 28, 130, 18,
                    Component.translatable("planomega.ui.load_sample").getString(), Theme.ACCENT_AMBER, () -> {
                onLoadGraph.accept(DemoGraphFactory.createSampleSulfuricAcidPlan());
                close();
            }));

            buttons.add(new ModalButton(x + width - 86, y + height - 28, 72, 18,
                    Component.translatable("planomega.ui.close").getString(), Theme.BTN_BORDER, this::close));

            refreshSavedList(x, y);
        }

        private void refreshSavedList(int x, int y) {
            savedPlans = PlanStorage.listSavedPlans();
            dynamicListButtons.clear();
            int rowY = y + 112;
            int maxRows = 6;
            for (int i = 0; i < Math.min(maxRows, savedPlans.size()); i++) {
                PlanStorage.SavedPlanEntry entry = savedPlans.get(i);
                dynamicListButtons.add(new ModalButton(x + 286, rowY, 56, 16,
                        Component.translatable("planomega.ui.load").getString(), Theme.ACCENT_TEAL, () -> {
                    try {
                        onLoadGraph.accept(PlanStorage.loadPlan(entry.path()));
                        close();
                    } catch (Exception e) {
                        onToast.accept("Load failed: " + e.getMessage());
                    }
                }));
                dynamicListButtons.add(new ModalButton(x + 348, rowY, 56, 16,
                        Component.translatable("planomega.ui.delete").getString(), 0xFFEF4444, () -> {
                    try {
                        PlanStorage.deletePlan(entry.path());
                        refreshSavedList(x, y);
                    } catch (Exception ignored) {
                    }
                }));
                rowY += 22;
            }
        }

        @Override
        public void render(GuiGraphics gfx, Font font, int screenW, int screenH, int mouseX, int mouseY, float partialTick) {
            super.render(gfx, font, screenW, screenH, mouseX, mouseY, partialTick);
            for (ModalButton btn : dynamicListButtons) {
                btn.render(gfx, font, mouseX, mouseY);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button, int screenW, int screenH) {
            if (button == 0) {
                for (ModalButton btn : dynamicListButtons) {
                    if (btn.isHovered(mouseX, mouseY)) {
                        btn.onClick.run();
                        return true;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, button, screenW, screenH);
        }

        @Override
        protected void renderContents(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY, float partialTick) {
            gfx.drawString(font, Component.translatable("planomega.modal.plans.title"), x + 14, y + 10, Theme.ACCENT_TEAL, false);
            gfx.drawString(font, Component.translatable("planomega.modal.plans.name_label"), x + 14, y + 24, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, Component.translatable("planomega.modal.plans.share_label"), x + 14, y + 61, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, Component.translatable("planomega.modal.plans.saved_list"), x + 14, y + 98, Theme.TEXT_SECONDARY, false);

            if (savedPlans.isEmpty()) {
                gfx.drawString(font, Component.translatable("planomega.modal.plans.empty"), x + 14, y + 118, Theme.TEXT_MUTED, false);
            } else {
                int rowY = y + 115;
                for (int i = 0; i < Math.min(6, savedPlans.size()); i++) {
                    String name = font.plainSubstrByWidth(savedPlans.get(i).name(), 260);
                    gfx.drawString(font, name, x + 18, rowY, Theme.TEXT_PRIMARY, false);
                    rowY += 22;
                }
            }
        }
    }

    /**
     * Modal for configuring production target rate, machine voltage tier, and perfect overclock.
     */
    public static final class NodeConfigModal extends PlannerModal {
        private final ProcessNode node;
        private final Consumer<ProcessNode> onSave;
        private final @org.jetbrains.annotations.Nullable Consumer<VirtualRecipeNode> onCloneSandbox;
        private EditBox targetRateBox;
        private int tier;
        private boolean perfectOc;

        public NodeConfigModal(
                ProcessNode node,
                Consumer<ProcessNode> onSave,
                @org.jetbrains.annotations.Nullable Consumer<VirtualRecipeNode> onCloneSandbox
        ) {
            super(350, 195);
            this.node = node;
            this.onSave = onSave;
            this.onCloneSandbox = onCloneSandbox;
            this.tier = node.tier();
            this.perfectOc = node.perfectOverclock();
        }

        @Override
        public void init(Font font, int screenW, int screenH) {
            rebuild(font, screenW, screenH, node.targetRate() != null ? String.valueOf(node.targetRate()) : "");
        }

        private void rebuild(Font font, int screenW, int screenH, String currentTargetText) {
            editBoxes.clear();
            buttons.clear();
            int x = left(screenW);
            int y = top(screenH);

            targetRateBox = new EditBox(font, x + 14, y + 40, width - 28, 18, Component.empty());
            targetRateBox.setMaxLength(20);
            targetRateBox.setValue(currentTargetText);
            targetRateBox.setFocused(true);
            editBoxes.add(targetRateBox);

            buttons.add(new ModalButton(x + 14, y + 78, 24, 18, "-", Theme.BTN_BORDER, () -> {
                tier = Math.max(VoltageTier.fromEut(node.eut()).index(), tier - 1);
                rebuild(font, screenW, screenH, targetRateBox.getValue());
            }));
            buttons.add(new ModalButton(x + 116, y + 78, 24, 18, "+", Theme.BTN_BORDER, () -> {
                tier = Math.min(VoltageTier.MAX.index(), tier + 1);
                rebuild(font, screenW, screenH, targetRateBox.getValue());
            }));

            buttons.add(new ModalButton(x + 165, y + 78, 150, 18,
                    "Perfect OC: " + (perfectOc ? "ON" : "OFF"),
                    perfectOc ? Theme.ACCENT_TEAL : Theme.BTN_BORDER,
                    () -> {
                        perfectOc = !perfectOc;
                        rebuild(font, screenW, screenH, targetRateBox.getValue());
                    }));

            buttons.add(new ModalButton(x + 14, y + height - 30, 78, 20,
                    Component.translatable("planomega.modal.target.clear").getString(), 0xFFEF4444, () -> {
                targetRateBox.setValue("");
                applyAndClose();
            }));

            if (node instanceof RecipeNode r && onCloneSandbox != null) {
                buttons.add(new ModalButton(x + 98, y + height - 30, 96, 20,
                        Component.translatable("planomega.modal.target.clone_sandbox").getString(), Theme.ACCENT_AMBER, () -> {
                    VirtualRecipeNode cloned = new VirtualRecipeNode(
                            PlanGraph.newId("sim"),
                            r.x() + 20,
                            r.y() + r.height() + 24,
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
                            tier,
                            r.circuit(),
                            r.coilTemp(),
                            r.cleanroom(),
                            perfectOc,
                            "Cloned from " + r.recipeId(),
                            r.targetRate(),
                            r.targetPortId()
                    );
                    close();
                    onCloneSandbox.accept(cloned);
                }));
            }

            buttons.add(new ModalButton(x + width - 146, y + height - 30, 62, 20,
                    Component.translatable("planomega.ui.cancel").getString(), Theme.BTN_BORDER, this::close));
            buttons.add(new ModalButton(x + width - 78, y + height - 30, 64, 20,
                    Component.translatable("planomega.ui.apply").getString(), Theme.ACCENT_TEAL, this::applyAndClose));
        }

        private void applyAndClose() {
            Double parsedTarget = null;
            String raw = targetRateBox.getValue().trim();
            if (!raw.isEmpty()) {
                try {
                    double v = Double.parseDouble(raw);
                    if (v > 0.0 && Double.isFinite(v)) {
                        parsedTarget = v;
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            ProcessNode updated;
            if (node instanceof RecipeNode r) {
                String portId = r.targetPortId() != null ? r.targetPortId()
                        : (r.outputs().isEmpty() ? null : r.outputs().get(0).id());
                updated = new RecipeNode(
                        r.id(), r.x(), r.y(), r.width(), r.height(), r.groupId(),
                        r.recipeId(), r.recipeTypeId(), r.machineId(), r.title(),
                        r.inputs(), r.outputs(), r.eut(), r.durationTicks(),
                        tier, r.circuit(), r.coilTemp(), r.cleanroom(), perfectOc,
                        r.parallelLimit(), parsedTarget, portId, r.lockedRate()
                );
            } else {
                VirtualRecipeNode v = (VirtualRecipeNode) node;
                String portId = v.targetPortId() != null ? v.targetPortId()
                        : (v.outputs().isEmpty() ? null : v.outputs().get(0).id());
                updated = new VirtualRecipeNode(
                        v.id(), v.x(), v.y(), v.width(), v.height(), v.groupId(),
                        v.baseRecipeId(), v.recipeTypeId(), v.machineId(), v.title(),
                        v.inputs(), v.outputs(), v.eut(), v.durationTicks(),
                        tier, v.circuit(), v.coilTemp(), v.cleanroom(), perfectOc,
                        v.notes(), parsedTarget, portId
                );
            }
            onSave.accept(updated);
            close();
        }

        @Override
        protected void renderContents(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY, float partialTick) {
            gfx.drawString(font, Component.translatable("planomega.modal.target.title"), x + 14, y + 10, Theme.ACCENT_TEAL, false);
            gfx.drawString(font, Component.translatable("planomega.modal.target.rate_label"), x + 14, y + 27, Theme.TEXT_SECONDARY, false);
            gfx.drawString(font, Component.translatable("planomega.modal.target.tier_label"), x + 14, y + 66, Theme.TEXT_SECONDARY, false);

            VoltageTier vt = VoltageTier.byIndex(tier);
            String tierName = vt.shortName() + " (" + vt.voltage() + ")";
            int tw = font.width(tierName);
            gfx.drawString(font, tierName, x + 42 + (70 - tw) / 2, y + 83, vt.badgeColor(), false);
        }
    }

    /**
     * Author & AI export modal providing 1-click clipboard copy for AI JSON, Mermaid (GuideME/MkDocs),
     * Sandbox Diff Markdown, KubeJS recipe scripts, and TypeOmega Java snippets.
     */
    public static final class ExportModal extends PlannerModal {
        private final PlanGraph graph;
        private final SolveResult solve;
        private final AuditReport audit;
        private final Consumer<String> onToast;
        private String preview = "";

        public ExportModal(PlanGraph graph, SolveResult solve, AuditReport audit, Consumer<String> onToast) {
            super(430, 260);
            this.graph = graph;
            this.solve = solve;
            this.audit = audit;
            this.onToast = onToast;
        }

        @Override
        public void init(Font font, int screenW, int screenH) {
            editBoxes.clear();
            buttons.clear();
            int x = left(screenW);
            int y = top(screenH);

            buttons.add(new ModalButton(x + 14, y + 34, 128, 22, "AI JSON", Theme.ACCENT_TEAL, () ->
                    copyPayload(AiAnalysisExporter.exportJson(graph, solve, audit), "planomega.toast.exported_ai")));
            buttons.add(new ModalButton(x + 150, y + 34, 130, 22, "Mermaid (GuideME)", Theme.ACCENT_TEAL, () ->
                    copyPayload(MermaidExporter.exportMermaid(graph, solve, audit), "planomega.toast.exported_mermaid")));
            buttons.add(new ModalButton(x + 288, y + 34, 128, 22, "Sandbox Diff (.md)", Theme.ACCENT_AMBER, () ->
                    copyPayload(SandboxDiffExporter.toMarkdownDiff(graph, id -> RecipeCatalogManager.get().findById(id)),
                            "planomega.toast.exported_diff")));

            buttons.add(new ModalButton(x + 14, y + 64, 196, 22, "KubeJS Script", Theme.ACCENT_AMBER, () ->
                    copyPayload(SandboxDiffExporter.toKubeJsScript(graph), "planomega.toast.exported_kubejs")));
            buttons.add(new ModalButton(x + 218, y + 64, 198, 22, "TypeOmega Java", Theme.ACCENT_AMBER, () ->
                    copyPayload(SandboxDiffExporter.toTypeOmegaJava(graph), "planomega.toast.exported_java")));

            buttons.add(new ModalButton(x + width - 86, y + height - 28, 72, 20,
                    Component.translatable("planomega.ui.close").getString(), Theme.BTN_BORDER, this::close));

            this.preview = AiAnalysisExporter.exportJson(graph, solve, audit);
        }

        private void copyPayload(String payload, String toastKey) {
            this.preview = payload;
            Minecraft.getInstance().keyboardHandler.setClipboard(payload);
            onToast.accept(Component.translatable(toastKey).getString());
        }

        @Override
        protected void renderContents(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY, float partialTick) {
            gfx.drawString(font, Component.translatable("planomega.modal.export.title"), x + 14, y + 10, Theme.ACCENT_TEAL, false);
            int boxX = x + 14;
            int boxY = y + 96;
            int boxW = width - 28;
            int boxH = height - 134;
            gfx.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xFF0A0E14);
            drawOutline(gfx, boxX, boxY, boxW, boxH, Theme.BTN_BORDER);

            String[] lines = preview.split("\\R");
            int ly = boxY + 6;
            for (String line : lines) {
                if (ly + 10 > boxY + boxH - 4) {
                    break;
                }
                gfx.drawString(font, font.plainSubstrByWidth(line, boxW - 12), boxX + 6, ly, Theme.TEXT_SECONDARY, false);
                ly += 10;
            }
        }
    }
}
