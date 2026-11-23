package net.xolt.freecam.forge.ghost;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/** Lists this world's saved blueprints. Click one for details and required resources. */
public class BlueprintListScreen extends Screen {
    private static final int ROW = 24;

    private int left, top, listWidth, rows, scroll;

    public BlueprintListScreen() {
        super(Component.translatable("gui.freecam.blueprints"));
    }

    @Override
    protected void init() {
        listWidth = Math.min(340, width - 40);
        left = (width - listWidth) / 2;
        top = 36;
        rows = Math.max(1, (height - top - 40) / ROW);
        scroll = Mth.clamp(scroll, 0, maxScroll());

        int by = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gui.freecam.blueprintNew"), b -> {
            Blueprints.Blueprint bp = Blueprints.create();
            if (GhostBlocks.sessionBlueprint() == null) {
                Blueprints.setActive(bp);
            }
            minecraft.setScreen(new BlueprintDetailScreen(this, bp));
        }).bounds(width / 2 - 154, by, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 + 4, by, 150, 20).build());
    }

    private List<Blueprints.Blueprint> items() {
        return Blueprints.all();
    }

    private int maxScroll() {
        return Math.max(0, items().size() - rows);
    }

    private int rowAt(double mx, double my) {
        if (mx < left || mx >= left + listWidth || my < top) {
            return -1;
        }
        int r = (int) ((my - top) / ROW);
        int idx = r + scroll;
        return r < rows && idx < items().size() ? idx : -1;
    }

    private boolean onEye(double mx) {
        return mx >= left + listWidth - 56;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        int idx = rowAt(mx, my);
        if (idx < 0) {
            return false;
        }
        Blueprints.Blueprint bp = items().get(idx);
        if (onEye(mx)) {
            Blueprints.setVisible(bp, !bp.visible());
        } else {
            minecraft.setScreen(new BlueprintDetailScreen(this, bp));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Mth.clamp(scroll - (int) Math.signum(sy), 0, maxScroll());
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        g.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);

        List<Blueprints.Blueprint> list = items();
        if (list.isEmpty()) {
            g.drawCenteredString(font, Component.translatable("gui.freecam.blueprintNone"), width / 2, top + 20, 0xAAAAAA);
            return;
        }
        Blueprints.Blueprint active = Blueprints.activeOrNull();
        BlockPos me = minecraft.player != null ? minecraft.player.blockPosition() : BlockPos.ZERO;
        for (int r = 0; r < rows && r + scroll < list.size(); r++) {
            Blueprints.Blueprint bp = list.get(r + scroll);
            int y = top + r * ROW;
            boolean hover = rowAt(mx, my) == r + scroll;
            g.fill(left, y, left + listWidth, y + ROW - 2, hover ? 0xC0404050 : 0xA0202028);
            if (bp == active) {
                g.fill(left, y, left + 3, y + ROW - 2, 0xFF6AA8FF);
            }
            g.drawString(font, bp.name(), left + 8, y + 3, 0xFFFFFF);
            BlockPos c = bp.center();
            String info = bp.blocks.size() + " blocks"
                    + (c != null ? "  ·  " + (int) Math.sqrt(c.distSqr(me)) + "m away" : "")
                    + (bp == active ? "  ·  active" : "");
            g.drawString(font, info, left + 8, y + 13, 0xAAAAAA);

            int ex = left + listWidth - 52;
            boolean eyeHover = hover && onEye(mx);
            g.fill(ex, y + 3, ex + 48, y + ROW - 5, eyeHover ? 0xFF505064 : 0xFF33333D);
            g.drawCenteredString(font, Component.translatable(bp.visible() ? "gui.freecam.shown" : "gui.freecam.hidden"),
                    ex + 24, y + 7, bp.visible() ? 0x9FD8FF : 0x888888);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
