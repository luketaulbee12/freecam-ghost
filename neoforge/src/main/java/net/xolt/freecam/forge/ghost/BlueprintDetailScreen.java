package net.xolt.freecam.forge.ghost;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.xolt.freecam.forge.ghost.builder.AutoBuilder;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One blueprint: rename / activate / show / build / delete, plus the materials it still needs. */
public class BlueprintDetailScreen extends Screen {
    private static final int ROW = 20;

    private record Need(Item item, int needed, int have) {}

    private final Screen parent;
    private final Blueprints.Blueprint bp;
    private List<Need> needs = List.of();
    private int toDig;
    private int unknown;
    private EditBox nameBox;
    private Button activeButton, visibleButton, buildButton, deleteButton;
    private boolean confirmDelete;
    private int left, tableTop, tableWidth, rows, scroll;

    public BlueprintDetailScreen(Screen parent, Blueprints.Blueprint bp) {
        super(Component.literal(bp.name()));
        this.parent = parent;
        this.bp = bp;
    }

    @Override
    protected void init() {
        tableWidth = Math.min(340, width - 40);
        left = (width - tableWidth) / 2;

        nameBox = new EditBox(font, left, 12, tableWidth, 16, Component.translatable("gui.freecam.blueprintName"));
        nameBox.setMaxLength(48);
        nameBox.setValue(bp.name());
        addRenderableWidget(nameBox);

        int bw = (tableWidth - 12) / 4;
        int y = 48;
        activeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            Blueprints.setActive(bp);
            refreshButtons();
        }).bounds(left, y, bw, 20).build());
        visibleButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            Blueprints.setVisible(bp, !bp.visible());
            refreshButtons();
        }).bounds(left + (bw + 4), y, bw, 20).build());
        buildButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (AutoBuilder.isRunning()) {
                AutoBuilder.toggle(null);
            } else {
                applyRename();
                onClose();
                AutoBuilder.toggle(bp);
                return;
            }
            refreshButtons();
        }).bounds(left + 2 * (bw + 4), y, bw, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            if (!confirmDelete) {
                confirmDelete = true;
                refreshButtons();
                return;
            }
            Blueprints.delete(bp);
            minecraft.setScreen(parent);
        }).bounds(left + 3 * (bw + 4), y, bw, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(width / 2 - 75, height - 26, 150, 20).build());

        tableTop = y + 44;
        rows = Math.max(1, (height - 32 - tableTop) / ROW);
        computeNeeds();
        refreshButtons();
    }

    private void refreshButtons() {
        boolean editingOther = GhostBlocks.sessionBlueprint() != null && GhostBlocks.sessionBlueprint() != bp;
        boolean isActive = Blueprints.activeOrNull() == bp;
        activeButton.setMessage(Component.translatable(isActive ? "gui.freecam.blueprintIsActive" : "gui.freecam.blueprintMakeActive"));
        activeButton.active = !isActive && GhostBlocks.sessionBlueprint() == null;
        visibleButton.setMessage(Component.translatable(bp.visible() ? "gui.freecam.blueprintHide" : "gui.freecam.blueprintShow"));
        buildButton.setMessage(Component.translatable(AutoBuilder.isRunning() ? "gui.freecam.blueprintStopBuild" : "gui.freecam.blueprintBuild"));
        buildButton.active = AutoBuilder.isRunning() || (GhostBlocks.sessionBlueprint() == null && !bp.blocks.isEmpty());
        deleteButton.setMessage(Component.translatable(confirmDelete ? "gui.freecam.blueprintConfirmDelete" : "gui.freecam.blueprintDelete"));
        deleteButton.active = GhostBlocks.sessionBlueprint() != bp;
        if (editingOther) {
            activeButton.active = false;
        }
    }

    /** Counts what's still missing from the world, and what the player is carrying. */
    private void computeNeeds() {
        ClientLevel level = minecraft.level;
        Map<Item, Integer> needed = new LinkedHashMap<>();
        toDig = 0;
        unknown = 0;
        boolean editingThis = GhostBlocks.sessionBlueprint() == bp; // world shows ghosts, not reality
        for (Map.Entry<BlockPos, BlockState> e : bp.blocks.entrySet()) {
            BlockState target = e.getValue();
            if (level != null && !editingThis && level.isLoaded(e.getKey())
                    && GhostBlocks.isSatisfied(level.getBlockState(e.getKey()), target)) {
                continue;
            }
            if (target.isAir()) {
                toDig++;
                continue;
            }
            if (isSecondHalf(target)) {
                continue;
            }
            Item item = target.getBlock().asItem();
            if (item == Items.AIR) {
                unknown++;
                continue;
            }
            int count = target.hasProperty(BlockStateProperties.SLAB_TYPE)
                    && target.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
            needed.merge(item, count, Integer::sum);
        }
        List<Need> out = new ArrayList<>();
        Inventory inv = minecraft.player != null ? minecraft.player.getInventory() : null;
        for (Map.Entry<Item, Integer> e : needed.entrySet()) {
            out.add(new Need(e.getKey(), e.getValue(), inv == null ? 0 : inv.countItem(e.getKey())));
        }
        // Things you're short on first, then biggest amounts.
        out.sort((a, b) -> {
            boolean sa = a.have() < a.needed(), sb = b.have() < b.needed();
            if (sa != sb) {
                return sa ? -1 : 1;
            }
            return Integer.compare(b.needed(), a.needed());
        });
        needs = out;
        scroll = Mth.clamp(scroll, 0, Math.max(0, needs.size() - rows));
    }

    private static boolean isSecondHalf(BlockState s) {
        return (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                || (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD);
    }

    private void applyRename() {
        if (nameBox != null) {
            Blueprints.rename(bp, nameBox.getValue());
        }
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (nameBox.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            applyRename();
            nameBox.setFocused(false);
            setFocused(null);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scroll = Mth.clamp(scroll - (int) Math.signum(sy) * 2, 0, Math.max(0, needs.size() - rows));
        return true;
    }

    @Override
    public void onClose() {
        applyRename();
        minecraft.setScreen(parent);
    }

    @Override
    public void tick() {
        // keep "have" counts live while the screen is open
        if (minecraft.player != null && minecraft.player.tickCount % 20 == 0) {
            computeNeeds();
            refreshButtons();
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);

        BlockPos c = bp.center();
        String loc = c == null ? "" : "  ·  center " + c.getX() + ", " + c.getY() + ", " + c.getZ();
        g.drawString(font, bp.blocks.size() + " blocks" + loc, left, 34, 0xAAAAAA);

        int y = tableTop - 22;
        int missingTypes = 0;
        for (Need n : needs) {
            if (n.have() < n.needed()) {
                missingTypes++;
            }
        }
        Component summary = needs.isEmpty() && toDig == 0
                ? Component.translatable("gui.freecam.blueprintComplete")
                : Component.translatable("gui.freecam.blueprintSummary", needs.size(), missingTypes, toDig);
        g.drawString(font, summary, left, y, 0xFFFFFF);
        g.drawString(font, Component.translatable("gui.freecam.colNeed"), left + tableWidth - 110, y + 11, 0x888888);
        g.drawString(font, Component.translatable("gui.freecam.colHave"), left + tableWidth - 50, y + 11, 0x888888);

        ItemStack hovered = ItemStack.EMPTY;
        for (int r = 0; r < rows && r + scroll < needs.size(); r++) {
            Need n = needs.get(r + scroll);
            int ry = tableTop + r * ROW;
            boolean ok = n.have() >= n.needed();
            g.fill(left, ry, left + tableWidth, ry + ROW - 2, r % 2 == 0 ? 0x90202028 : 0x90282830);
            ItemStack icon = new ItemStack(n.item());
            g.renderItem(icon, left + 2, ry + 1);
            g.drawString(font, icon.getHoverName(), left + 22, ry + 5, 0xFFFFFF);
            g.drawString(font, stacks(n.needed()), left + tableWidth - 110, ry + 5, 0xFFFFFF);
            g.drawString(font, String.valueOf(n.have()), left + tableWidth - 50, ry + 5, ok ? 0x7CFC7C : 0xFF7070);
            if (mx >= left && mx < left + 18 && my >= ry && my < ry + 18) {
                hovered = icon;
            }
        }
        if (unknown > 0) {
            g.drawString(font, Component.translatable("gui.freecam.blueprintUnknown", unknown), left, height - 40, 0x888888);
        }
        if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mx, my);
        }
    }

    /** "130" -> "130 (2st+2)" */
    private static String stacks(int n) {
        if (n < 64) {
            return String.valueOf(n);
        }
        int st = n / 64, rest = n % 64;
        return n + " (" + st + "st" + (rest > 0 ? "+" + rest : "") + ")";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
