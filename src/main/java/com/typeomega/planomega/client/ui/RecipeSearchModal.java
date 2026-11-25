package com.typeomega.planomega.client.ui;

import com.typeomega.planomega.model.RecipeNode;
import com.typeomega.planomega.model.ResourceKey;
import com.typeomega.planomega.model.ResourcePort;
import com.typeomega.planomega.model.VoltageTier;
import com.typeomega.planomega.recipe.RecipeCatalog;
import com.typeomega.planomega.recipe.RecipeCatalogManager;
import com.typeomega.planomega.recipe.RecipeEntry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Searchable recipe picker modal supporting {@code @mod}/{@code @machine}, {@code #tag},
 * relevance-ranked text queries, keyboard navigation, quick filter chips, visible scrollbar,
 * and automatic producer/consumer filtering when triggered from a node port.
 */
public final class RecipeSearchModal extends PlannerModal {

    public enum Mode {
        ADD,
        SWITCH
    }

    private static final int ROW_HEIGHT = 28;
    private static final int VISIBLE_ROWS = 7;
    private static final int LIST_TOP_OFFSET = 76;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final List<String> QUICK_FILTERS = List.of("@gtceu", "@typeomega", "#tag");

    private final @Nullable ResourceKey producedFilter;
    private final @Nullable ResourceKey consumedFilter;
    private final String initialQuery;
    private final Mode mode;
    private final @Nullable String currentRecipeId;
    private final @Nullable String currentRecipeTitle;
    private final @Nullable RecipeNode sourceNode;
    private final Consumer<RecipeEntry> onSelect;

    private EditBox searchBox;
    private List<RecipeEntry> results = List.of();
    private int scrollOffset = 0;
    private int selectedIndex = -1;
    private int modalX;
    private int modalY;

    public RecipeSearchModal(
            @Nullable ResourceKey producedFilter,
            @Nullable ResourceKey consumedFilter,
            @Nullable String initialQuery,
            Consumer<RecipeEntry> onSelect
    ) {
        this(producedFilter, consumedFilter, initialQuery, Mode.ADD, null, null, null, onSelect);
    }

    public RecipeSearchModal(
            @Nullable ResourceKey producedFilter,
            @Nullable ResourceKey consumedFilter,
            @Nullable String initialQuery,
            Mode mode,
            @Nullable String currentRecipeId,
            @Nullable String currentRecipeTitle,
            Consumer<RecipeEntry> onSelect
    ) {
        this(producedFilter, consumedFilter, initialQuery, mode, currentRecipeId, currentRecipeTitle, null, onSelect);
    }

    private RecipeSearchModal(
            @Nullable ResourceKey producedFilter,
            @Nullable ResourceKey consumedFilter,
            @Nullable String initialQuery,
            Mode mode,
            @Nullable String currentRecipeId,
            @Nullable String currentRecipeTitle,
            @Nullable RecipeNode sourceNode,
            Consumer<RecipeEntry> onSelect
    ) {
        super(470, 310);
        this.producedFilter = producedFilter;
        this.consumedFilter = consumedFilter;
        this.initialQuery = initialQuery == null ? "" : initialQuery;
        this.mode = mode == null ? Mode.ADD : mode;
        this.currentRecipeId = currentRecipeId;
        this.currentRecipeTitle = currentRecipeTitle;
        this.sourceNode = sourceNode;
        this.onSelect = onSelect;
    }

    public static RecipeSearchModal forSwitch(
            @Nullable ResourceKey producedFilter,
            @Nullable String currentRecipeId,
            @Nullable String currentRecipeTitle,
            Consumer<RecipeEntry> onSelect
    ) {
        return new RecipeSearchModal(
                producedFilter,
                null,
                "",
                Mode.SWITCH,
                currentRecipeId,
                currentRecipeTitle,
                null,
                onSelect
        );
    }

    public static RecipeSearchModal forSwitch(
            RecipeNode node,
            Consumer<RecipeEntry> onSelect
    ) {
        ResourceKey primaryOutput = node.outputs().isEmpty() ? null : node.outputs().get(0).resource();
        return new RecipeSearchModal(
                primaryOutput,
                null,
                "",
                Mode.SWITCH,
                node.recipeId(),
                node.title(),
                node,
                onSelect
        );
    }

    /**
     * Builds a replacement {@link RecipeNode} from {@code newRecipe} while preserving
     * {@code oldNode}'s position, group, target rate, locked rate, tier, coil, and parallel context.
     */
    public static RecipeNode applySwitchToNode(RecipeNode oldNode, RecipeEntry newRecipe) {
        return new RecipeNode(
                oldNode.id(),
                oldNode.x(),
                oldNode.y(),
                0,
                0,
                oldNode.groupId(),
                newRecipe.recipeId(),
                newRecipe.recipeTypeId(),
                newRecipe.machineId(),
                newRecipe.title(),
                newRecipe.inputs(),
                newRecipe.outputs(),
                newRecipe.eut(),
                newRecipe.durationTicks(),
                oldNode.tier() > 0 ? oldNode.tier() : newRecipe.tier(),
                newRecipe.circuit(),
                newRecipe.coilTemp(),
                newRecipe.cleanroom(),
                newRecipe.perfectOverclock(),
                newRecipe.parallelLimit(),
                oldNode.targetRate(),
                oldNode.targetPortId(),
                oldNode.lockedRate(),
                oldNode.coilTier(),
                oldNode.parallelCount()
        );
    }

    public Mode getMode() {
        return mode;
    }

    public @Nullable RecipeNode getSourceNode() {
        return sourceNode;
    }

    @Override
    public void init(Font font, int screenW, int screenH) {
        editBoxes.clear();
        buttons.clear();
        this.modalX = left(screenW);
        this.modalY = top(screenH);

        searchBox = new EditBox(font, modalX + 14, modalY + 32, width - 28, 18, Component.empty());
        searchBox.setMaxLength(128);
        searchBox.setValue(initialQuery);
        searchBox.setFocused(true);
        searchBox.setResponder(q -> refreshResults());
        editBoxes.add(searchBox);

        int chipX = modalX + 14;
        int chipY = modalY + 55;
        for (String token : QUICK_FILTERS) {
            int chipW = font.width(token) + 12;
            buttons.add(new ModalButton(chipX, chipY, chipW, 16, token, Theme.ACCENT_TEAL,
                    () -> toggleFilterToken(token)));
            chipX += chipW + 6;
        }

        buttons.add(new ModalButton(modalX + width - 86, modalY + height - 26, 72, 18,
                Component.translatable("planomega.ui.close").getString(), Theme.BTN_BORDER, this::close));

        refreshResults();
    }

    private void toggleFilterToken(String token) {
        if (searchBox == null) {
            return;
        }
        String current = searchBox.getValue().trim();
        String matchToken = token.equals("#tag") ? "#" : token;
        List<String> parts = new ArrayList<>();
        boolean removed = false;
        if (!current.isEmpty()) {
            for (String part : current.split("\\s+")) {
                if (part.equalsIgnoreCase(token) || (token.equals("#tag") && part.equals("#"))) {
                    removed = true;
                } else if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
        }
        if (!removed) {
            parts.add(matchToken);
        }
        searchBox.setValue(String.join(" ", parts));
        searchBox.setFocused(true);
    }

    private void refreshResults() {
        RecipeCatalog catalog = RecipeCatalogManager.get();
        String q = searchBox != null ? searchBox.getValue() : initialQuery;
        if (mode == Mode.SWITCH) {
            List<RecipeEntry> raw;
            if ((q == null || q.isBlank()) && producedFilter != null) {
                raw = catalog.findAlternatives(producedFilter, currentRecipeId);
                if (raw.size() > 120) {
                    raw = raw.subList(0, 120);
                }
            } else {
                raw = catalog.search(q, producedFilter, consumedFilter, 120);
                if (currentRecipeId != null && !currentRecipeId.isBlank()) {
                    List<RecipeEntry> filtered = new ArrayList<>(raw.size());
                    for (RecipeEntry entry : raw) {
                        if (!entry.recipeId().equals(currentRecipeId)) {
                            filtered.add(entry);
                        }
                    }
                    raw = filtered;
                }
            }
            this.results = raw;
        } else {
            this.results = catalog.search(q, producedFilter, consumedFilter, 120);
        }
        this.scrollOffset = 0;
        this.selectedIndex = results.isEmpty() ? -1 : 0;
    }

    @Override
    protected void renderContents(GuiGraphics gfx, Font font, int x, int y, int mouseX, int mouseY, float partialTick) {
        String header;
        if (mode == Mode.SWITCH) {
            String contextLabel = currentRecipeTitle != null && !currentRecipeTitle.isBlank()
                    ? currentRecipeTitle
                    : (producedFilter != null ? producedFilter.shortName() : "");
            header = Component.translatable("planomega.modal.recipe.switch_title", contextLabel).getString();
        } else if (producedFilter != null) {
            header = Component.translatable("planomega.modal.recipe.producers_of", producedFilter.shortName()).getString();
        } else if (consumedFilter != null) {
            header = Component.translatable("planomega.modal.recipe.consumers_of", consumedFilter.shortName()).getString();
        } else {
            header = Component.translatable("planomega.modal.recipe.title").getString();
        }
        gfx.drawString(font, header, x + 14, y + 10, Theme.ACCENT_TEAL, false);

        String countLabel = results.size() + " / " + RecipeCatalogManager.get().size();
        int cw = font.width(countLabel);
        gfx.drawString(font, countLabel, x + width - cw - 14, y + 10, Theme.TEXT_MUTED, false);

        // Highlight active quick-filter chips
        String qLower = searchBox != null ? searchBox.getValue().toLowerCase(Locale.ROOT) : "";
        List<String> qTokens = qLower.isBlank() ? List.of() : Arrays.asList(qLower.trim().split("\\s+"));
        for (int i = 0; i < QUICK_FILTERS.size() && i < buttons.size(); i++) {
            ModalButton chip = buttons.get(i);
            String token = QUICK_FILTERS.get(i);
            boolean active = token.equals("#tag")
                    ? qTokens.stream().anyMatch(t -> t.startsWith("#"))
                    : qTokens.contains(token);
            if (active) {
                drawOutline(gfx, chip.x() - 1, chip.y() - 1, chip.w() + 2, chip.h() + 2, Theme.ACCENT_TEAL);
            }
        }

        // Footer keyboard navigation hint
        String navHint = Component.translatable("planomega.modal.recipe.nav_hint").getString();
        gfx.drawString(font, navHint, x + 14, y + height - 21, Theme.TEXT_MUTED, false);

        int listTop = y + LIST_TOP_OFFSET;
        if (results.isEmpty()) {
            String emptyKey = mode == Mode.SWITCH
                    ? "planomega.modal.recipe.switch_empty"
                    : "planomega.modal.recipe.empty";
            gfx.drawString(font, Component.translatable(emptyKey).getString(),
                    x + 16, listTop + 12, Theme.TEXT_MUTED, false);
            return;
        }

        int listW = width - 28 - SCROLLBAR_WIDTH - 4;
        int rx = x + 14;
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            int idx = scrollOffset + row;
            if (idx >= results.size()) {
                break;
            }
            RecipeEntry entry = results.get(idx);
            int ry = listTop + row * ROW_HEIGHT;
            boolean hovered = mouseX >= rx && mouseX <= rx + listW && mouseY >= ry && mouseY < ry + ROW_HEIGHT - 2;
            boolean selected = idx == selectedIndex;

            int bg = selected ? Theme.BTN_HOVER : (hovered ? Theme.BTN_HOVER : Theme.BTN_BG);
            int border = selected ? Theme.ACCENT_TEAL : (hovered ? Theme.TEXT_SECONDARY : Theme.BTN_BORDER);
            gfx.fill(rx, ry, rx + listW, ry + ROW_HEIGHT - 2, bg);
            drawOutline(gfx, rx, ry, listW, ROW_HEIGHT - 2, border);

            if (!entry.outputs().isEmpty()) {
                ItemFluidIconRenderer.render(gfx, entry.outputs().get(0).resource(), rx + 5, ry + 5, 16);
            } else if (!entry.inputs().isEmpty()) {
                ItemFluidIconRenderer.render(gfx, entry.inputs().get(0).resource(), rx + 5, ry + 5, 16);
            }

            String title = font.plainSubstrByWidth(entry.title(), listW - 170);
            gfx.drawString(font, title, rx + 26, ry + 4, Theme.TEXT_PRIMARY, false);

            String sub = formatSubtitle(entry);
            String subClipped = font.plainSubstrByWidth(sub, listW - 170);
            gfx.drawString(font, subClipped, rx + 26, ry + 15, Theme.TEXT_SECONDARY, false);

            VoltageTier tier = VoltageTier.fromEut(entry.eut());
            String rightBadge = entry.eut() != 0
                    ? tier.shortName() + " " + entry.eut() + " EU/t"
                    : entry.machineLabel();
            int bw = font.width(rightBadge);
            gfx.drawString(font, rightBadge, rx + listW - bw - 8, ry + 9,
                    entry.eut() != 0 ? tier.badgeColor() : Theme.TEXT_MUTED, false);
        }

        renderScrollbar(gfx, x + width - 14 - SCROLLBAR_WIDTH, listTop, mouseX, mouseY);
    }

    private void renderScrollbar(GuiGraphics gfx, int trackX, int trackY, int mouseX, int mouseY) {
        int trackH = VISIBLE_ROWS * ROW_HEIGHT - 2;
        gfx.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, Theme.BTN_BG);
        drawOutline(gfx, trackX, trackY, SCROLLBAR_WIDTH, trackH, Theme.BTN_BORDER);

        if (results.size() <= VISIBLE_ROWS) {
            return;
        }
        int maxScroll = results.size() - VISIBLE_ROWS;
        int thumbH = Math.max(16, (int) ((double) VISIBLE_ROWS / results.size() * trackH));
        int thumbY = trackY + (int) ((double) scrollOffset / maxScroll * (trackH - thumbH));
        boolean hovered = mouseX >= trackX && mouseX <= trackX + SCROLLBAR_WIDTH
                && mouseY >= trackY && mouseY <= trackY + trackH;
        gfx.fill(trackX + 1, thumbY + 1, trackX + SCROLLBAR_WIDTH - 1, thumbY + thumbH - 1,
                hovered ? Theme.ACCENT_TEAL : Theme.TEXT_MUTED);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button, int screenW, int screenH) {
        if (button == 0) {
            int listTop = modalY + LIST_TOP_OFFSET;
            int listH = VISIBLE_ROWS * ROW_HEIGHT - 2;
            int rx = modalX + 14;
            int listW = width - 28 - SCROLLBAR_WIDTH - 4;

            if (mouseX >= rx && mouseX <= rx + listW && mouseY >= listTop && mouseY < listTop + VISIBLE_ROWS * ROW_HEIGHT) {
                int row = (int) ((mouseY - listTop) / ROW_HEIGHT);
                int idx = scrollOffset + row;
                if (idx >= 0 && idx < results.size()) {
                    selectRecipe(results.get(idx));
                    return true;
                }
            }

            int trackX = modalX + width - 14 - SCROLLBAR_WIDTH;
            if (results.size() > VISIBLE_ROWS
                    && mouseX >= trackX - 2 && mouseX <= trackX + SCROLLBAR_WIDTH + 2
                    && mouseY >= listTop && mouseY <= listTop + listH) {
                double ratio = Math.max(0.0, Math.min(1.0, (mouseY - listTop) / listH));
                int maxScroll = results.size() - VISIBLE_ROWS;
                scrollOffset = Math.max(0, Math.min(maxScroll, (int) Math.round(ratio * maxScroll)));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button, screenW, screenH);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DOWN) {
            if (!results.isEmpty()) {
                selectedIndex = Math.min(results.size() - 1, Math.max(0, selectedIndex + 1));
                ensureSelectedVisible();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP) {
            if (!results.isEmpty()) {
                selectedIndex = Math.max(0, selectedIndex - 1);
                ensureSelectedVisible();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            if (!results.isEmpty()) {
                selectedIndex = Math.min(results.size() - 1, Math.max(0, selectedIndex + VISIBLE_ROWS));
                ensureSelectedVisible();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP) {
            if (!results.isEmpty()) {
                selectedIndex = Math.max(0, selectedIndex - VISIBLE_ROWS);
                ensureSelectedVisible();
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (selectedIndex >= 0 && selectedIndex < results.size()) {
                selectRecipe(results.get(selectedIndex));
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double delta) {
        int maxScroll = Math.max(0, results.size() - VISIBLE_ROWS);
        if (delta < 0) {
            scrollOffset = Math.min(maxScroll, scrollOffset + 1);
        } else if (delta > 0) {
            scrollOffset = Math.max(0, scrollOffset - 1);
        }
        return true;
    }

    private void ensureSelectedVisible() {
        if (selectedIndex < 0) {
            return;
        }
        if (selectedIndex < scrollOffset) {
            scrollOffset = selectedIndex;
        } else if (selectedIndex >= scrollOffset + VISIBLE_ROWS) {
            scrollOffset = selectedIndex - VISIBLE_ROWS + 1;
        }
    }

    private void selectRecipe(RecipeEntry entry) {
        onSelect.accept(entry);
        close();
    }

    private static String formatSubtitle(RecipeEntry entry) {
        StringBuilder sb = new StringBuilder(entry.machineLabel());
        sb.append(" • ").append(String.format("%.1fs", entry.durationTicks() / 20.0));
        if (entry.circuit() != null) {
            sb.append(" • C").append(entry.circuit());
        }
        if (entry.coilTemp() > 0) {
            sb.append(" • ").append(entry.coilTemp()).append("K");
        }
        if (entry.cleanroom()) {
            sb.append(" • Cleanroom");
        }
        if (!entry.inputs().isEmpty()) {
            ResourcePort firstIn = entry.inputs().get(0);
            sb.append(" | In: ").append((long) firstIn.amount()).append("x ").append(firstIn.displayLabel());
            if (entry.inputs().size() > 1) {
                sb.append(" +").append(entry.inputs().size() - 1);
            }
        }
        return sb.toString();
    }
}
