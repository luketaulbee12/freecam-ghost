package net.xolt.freecam.forge.ghost;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.xolt.freecam.Freecam;

/** "You have unsaved ghost edits" prompt shown when leaving freecam. */
public class GhostExitScreen extends Screen {

    public GhostExitScreen() {
        super(Component.translatable("gui.freecam.ghostExitTitle"));
    }

    @Override
    protected void init() {
        int w = 200, x = width / 2 - w / 2, y = height / 2 - 10;
        addRenderableWidget(Button.builder(Component.translatable("gui.freecam.ghostExitSave"), b -> {
            GhostBlocks.saveSession();
            exitFreecam();
        }).bounds(x, y, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.freecam.ghostExitDiscard"), b -> exitFreecam())
                .bounds(x, y + 24, w, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.freecam.ghostExitCancel"), b -> onClose())
                .bounds(x, y + 48, w, 20).build());
    }

    private void exitFreecam() {
        onClose();
        if (Freecam.isEnabled()) {
            Freecam.toggle();
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawCenteredString(font, title, width / 2, height / 2 - 44, 0xFFFFFF);
        g.drawCenteredString(font, Component.translatable("gui.freecam.ghostExitBody", GhostBlocks.session.size()),
                width / 2, height / 2 - 30, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
