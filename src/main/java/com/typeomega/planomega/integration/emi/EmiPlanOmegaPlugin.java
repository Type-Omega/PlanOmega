package com.typeomega.planomega.integration.emi;

import com.typeomega.planomega.client.PlanOmegaKeybinds;
import com.typeomega.planomega.recipe.RecipeEntry;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.Widget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.Optional;

/**
 * EMI entrypoint for Plan Omega. Registers the "Send to Plan Omega" recipe decorator
 * and enables hovered-stack/recipe lookup via {@link EmiLookupHelper}.
 */
@EmiEntrypoint
public final class EmiPlanOmegaPlugin implements EmiPlugin {

    @Override
    public void register(EmiRegistry registry) {
        registry.addRecipeDecorator((recipe, widgets) -> {
            if (recipe == null || recipe.getId() == null) {
                return;
            }
            int btnSize = 12;
            int bx = Math.max(0, widgets.getWidth() - btnSize - 2);
            int by = 2;
            widgets.add(new SendToPlannerWidget(recipe, new Bounds(bx, by, btnSize, btnSize)));
        });
    }

    private static final class SendToPlannerWidget extends Widget {

        private final EmiRecipe recipe;
        private final Bounds bounds;

        private SendToPlannerWidget(EmiRecipe recipe, Bounds bounds) {
            this.recipe = recipe;
            this.bounds = bounds;
        }

        @Override
        public Bounds getBounds() {
            return bounds;
        }

        @Override
        public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
            boolean hovered = bounds.contains(mouseX, mouseY);
            int bg = hovered ? 0xE01A3B40 : 0xC0121820;
            int border = hovered ? 0xFF2DD4BF : 0xFF475569;
            int x = bounds.x();
            int y = bounds.y();
            int w = bounds.width();
            int h = bounds.height();

            gfx.fill(x, y, x + w, y + h, bg);
            gfx.fill(x, y, x + w, y + 1, border);
            gfx.fill(x, y + h - 1, x + w, y + h, border);
            gfx.fill(x, y, x + 1, y + h, border);
            gfx.fill(x + w - 1, y, x + w, y + h, border);

            Font font = Minecraft.getInstance().font;
            String glyph = "Ω";
            int gw = font.width(glyph);
            gfx.drawString(font, glyph, x + (w - gw) / 2, y + 2, hovered ? 0xFF5EEAD4 : 0xFFCBD5E1, false);
        }

        @Override
        public List<ClientTooltipComponent> getTooltip(int mouseX, int mouseY) {
            if (!bounds.contains(mouseX, mouseY)) {
                return List.of();
            }
            return List.of(ClientTooltipComponent.create(
                    Component.translatable("planomega.xei.send_to_planner").getVisualOrderText()));
        }

        @Override
        public boolean mouseClicked(int mouseX, int mouseY, int button) {
            if (button == 0 && bounds.contains(mouseX, mouseY)) {
                return triggerEnqueue();
            }
            return false;
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (PlanOmegaKeybinds.ADD_FROM_XEI.matches(keyCode, scanCode)) {
                return triggerEnqueue();
            }
            return false;
        }

        private boolean triggerEnqueue() {
            Optional<RecipeEntry> resolved = EmiLookupHelper.resolveOrConvert(recipe);
            if (resolved.isEmpty()) {
                return false;
            }
            EmiLookupHelper.enqueueRecipe(recipe);
            Minecraft mc = Minecraft.getInstance();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.translatable("planomega.xei.enqueued_recipe", resolved.get().title()),
                        true
                );
            }
            return true;
        }
    }
}
