package net.xolt.freecam.forge.ghost;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/** Swaps the survival hotbar for the ghost hotbar while ghost editing. */
@EventBusSubscriber(bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class GhostHud {
    private static final ResourceLocation HOTBAR = ResourceLocation.withDefaultNamespace("hud/hotbar");
    private static final ResourceLocation HOTBAR_SELECTION = ResourceLocation.withDefaultNamespace("hud/hotbar_selection");

    private GhostHud() {}

    @SubscribeEvent
    public static void onRenderLayer(RenderGuiLayerEvent.Pre event) {
        if (!GhostBlocks.isEditing()) {
            return;
        }
        if (event.getName().equals(VanillaGuiLayers.SELECTED_ITEM_NAME)) {
            event.setCanceled(true);
            return;
        }
        if (!event.getName().equals(VanillaGuiLayers.HOTBAR)) {
            return;
        }
        event.setCanceled(true);

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics g = event.getGuiGraphics();
        int x = g.guiWidth() / 2 - 91;
        int y = g.guiHeight() - 22;

        RenderSystem.enableBlend();
        g.blitSprite(HOTBAR, x, y, 182, 22);
        g.blitSprite(HOTBAR_SELECTION, x - 1 + GhostHotbar.selected() * 20, y - 1, 24, 23);
        RenderSystem.disableBlend();
        for (int i = 0; i < GhostHotbar.SIZE; i++) {
            g.renderItem(GhostHotbar.get(i), x + 3 + i * 20, y + 3);
        }

        ItemStack sel = GhostHotbar.selectedStack();
        Blueprints.Blueprint bp = GhostBlocks.sessionBlueprint();
        Component label = Component.translatable(GhostBlocks.isDirty() ? "hud.freecam.ghostUnsaved" : "hud.freecam.ghost",
                bp == null ? "?" : bp.name(), GhostBlocks.session.size());
        int lx = x + 182 + 6;
        g.drawString(mc.font, label, lx, y + 3, GhostBlocks.isDirty() ? 0xFFFFD060 : 0xFF9FD8FF, true);
        if (!sel.isEmpty()) {
            g.drawString(mc.font, sel.getHoverName(), lx, y + 13, 0xFFFFFFFF, true);
        }
    }
}
