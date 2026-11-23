package net.xolt.freecam.forge.ghost;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Creative-style block picker for the ghost hotbar. Opened with the inventory key while in freecam.
 * Never touches the real inventory or sends anything to the server.
 */
public class GhostPickerScreen extends Screen {
    private static final ResourceLocation HOTBAR = ResourceLocation.withDefaultNamespace("hud/hotbar");
    private static final ResourceLocation HOTBAR_SELECTION = ResourceLocation.withDefaultNamespace("hud/hotbar_selection");
    private static final int SLOT = 18;
    private static final int TAB = 22;

    private static int rememberedTab = 0;
    private static String rememberedSearch = "";

    private record Tab(Component name, ItemStack icon, List<ItemStack> items) {}

    private final List<Tab> tabs = new ArrayList<>();
    private List<ItemStack> shown = List.of();
    private int tab;
    private int scrollRow;

    private int cols, rows, panelLeft, panelTop, panelWidth, panelBottom;
    private int tabsPerRow, gridLeft, gridTop, hotbarLeft, hotbarTop, flightY;
    private EditBox search;

    public GhostPickerScreen() {
        super(Component.translatable("gui.freecam.ghostPicker"));
    }

    @Override
    protected void init() {
        buildTabs();

        cols = Mth.clamp((width - 40) / SLOT, 9, 18);
        panelWidth = cols * SLOT + 12;
        panelLeft = (width - panelWidth) / 2;
        panelTop = 8;
        tabsPerRow = Math.max(1, panelWidth / TAB);
        int tabRows = (tabs.size() + tabsPerRow - 1) / tabsPerRow;

        int searchY = panelTop + 6 + tabRows * TAB + 4;
        search = new EditBox(font, panelLeft + 6, searchY, panelWidth - 12, 14, Component.translatable("gui.freecam.ghostSearch"));
        search.setHint(Component.translatable("gui.freecam.ghostSearch"));
        search.setMaxLength(64);
        search.setValue(rememberedSearch);
        search.setResponder(s -> {
            rememberedSearch = s;
            if (!s.isEmpty()) {
                tab = 0;
                rememberedTab = 0;
            }
            refresh();
        });
        addRenderableWidget(search);

        gridLeft = panelLeft + 6;
        flightY = searchY + 17;
        gridTop = flightY + 18;
        hotbarTop = height - 24;
        hotbarLeft = width / 2 - 91;
        panelBottom = hotbarTop - 16;
        rows = Math.max(1, (panelBottom - 6 - gridTop) / SLOT);
        panelBottom = gridTop + rows * SLOT + 6;

        // Flight controls row (under the search box)
        int bx = panelLeft + 6;
        flightButton = addRenderableWidget(net.minecraft.client.gui.components.Button.builder(flightLabel(), b -> {
            GhostFlight.toggleMode();
            b.setMessage(flightLabel());
        }).bounds(bx, flightY, 100, 14).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("-"), b -> GhostFlight.adjustSpeed(-1))
                .bounds(bx + 104, flightY, 14, 14).build());
        addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("+"), b -> GhostFlight.adjustSpeed(1))
                .bounds(bx + 150, flightY, 14, 14).build());

        tab = Mth.clamp(rememberedTab, 0, tabs.size() - 1);
        refresh();
    }

    private net.minecraft.client.gui.components.Button flightButton;

    private static Component flightLabel() {
        return Component.translatable("gui.freecam.flightButton", GhostFlight.modeName());
    }

    private void buildTabs() {
        tabs.clear();
        if (minecraft != null && minecraft.player != null && minecraft.level != null) {
            CreativeModeTabs.tryRebuildTabContents(minecraft.player.connection.enabledFeatures(), false,
                    minecraft.level.registryAccess());
        }
        tabs.add(new Tab(Component.translatable("gui.freecam.ghostAllBlocks"), new ItemStack(Items.COMPASS),
                blocksOnly(CreativeModeTabs.searchTab().getDisplayItems())));
        for (CreativeModeTab t : CreativeModeTabRegistry.getSortedCreativeModeTabs()) {
            if (t.getType() != CreativeModeTab.Type.CATEGORY) {
                continue;
            }
            List<ItemStack> items = blocksOnly(t.getDisplayItems());
            if (!items.isEmpty()) {
                tabs.add(new Tab(t.getDisplayName(), t.getIconItem(), items));
            }
        }
    }

    private static List<ItemStack> blocksOnly(Collection<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) {
            if (s.getItem() instanceof BlockItem) {
                out.add(s);
            }
        }
        return out;
    }

    private void refresh() {
        List<ItemStack> base = tabs.get(tab).items();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            shown = base;
        } else {
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack s : base) {
                if (s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)
                        || BuiltInRegistries.ITEM.getKey(s.getItem()).toString().contains(q)) {
                    out.add(s);
                }
            }
            shown = out;
        }
        scrollRow = Mth.clamp(scrollRow, 0, maxScroll());
    }

    private int maxScroll() {
        int totalRows = (shown.size() + cols - 1) / cols;
        return Math.max(0, totalRows - rows);
    }

    // ------------------------------------------------------------------ hit testing

    private int tabAt(double mx, double my) {
        for (int i = 0; i < tabs.size(); i++) {
            int x = panelLeft + 6 + (i % tabsPerRow) * TAB;
            int y = panelTop + 6 + (i / tabsPerRow) * TAB;
            if (mx >= x && mx < x + TAB - 2 && my >= y && my < y + TAB - 2) {
                return i;
            }
        }
        return -1;
    }

    private ItemStack gridAt(double mx, double my) {
        if (mx < gridLeft || my < gridTop) {
            return ItemStack.EMPTY;
        }
        int c = (int) ((mx - gridLeft) / SLOT);
        int r = (int) ((my - gridTop) / SLOT);
        if (c >= cols || r >= rows) {
            return ItemStack.EMPTY;
        }
        int idx = (r + scrollRow) * cols + c;
        return idx < shown.size() ? shown.get(idx) : ItemStack.EMPTY;
    }

    private int hotbarAt(double mx, double my) {
        if (my < hotbarTop || my >= hotbarTop + 22 || mx < hotbarLeft + 1 || mx >= hotbarLeft + 181) {
            return -1;
        }
        return (int) ((mx - hotbarLeft - 1) / 20);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        int t = tabAt(mx, my);
        if (t >= 0) {
            tab = t;
            rememberedTab = t;
            scrollRow = 0;
            if (t != 0 && !search.getValue().isEmpty()) {
                search.setValue("");
                tab = t; // responder above may have reset it
                rememberedTab = t;
            }
            refresh();
            return true;
        }
        ItemStack s = gridAt(mx, my);
        if (!s.isEmpty()) {
            GhostHotbar.set(GhostHotbar.selected(), s);
            return true;
        }
        int h = hotbarAt(mx, my);
        if (h >= 0) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                GhostHotbar.set(h, ItemStack.EMPTY);
            } else {
                GhostHotbar.select(h);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (hotbarAt(mx, my) >= 0) {
            GhostHotbar.select(GhostHotbar.selected() - (int) Math.signum(sy));
        } else {
            scrollRow = Mth.clamp(scrollRow - (int) Math.signum(sy) * 2, 0, maxScroll());
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            return super.keyPressed(key, scan, mods);
        }
        if (!search.isFocused()) {
            if (minecraft.options.keyInventory.matches(key, scan)) {
                onClose();
                return true;
            }
            for (int i = 0; i < GhostHotbar.SIZE; i++) {
                if (minecraft.options.keyHotbarSlots[i].matches(key, scan)) {
                    double mx = minecraft.mouseHandler.xpos() * width / minecraft.getWindow().getScreenWidth();
                    double my = minecraft.mouseHandler.ypos() * height / minecraft.getWindow().getScreenHeight();
                    ItemStack s = gridAt(mx, my);
                    if (!s.isEmpty()) {
                        GhostHotbar.set(i, s);
                    } else {
                        GhostHotbar.select(i);
                    }
                    return true;
                }
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (!search.isFocused() && !Character.isWhitespace(c) && !Character.isDigit(c)) {
            setFocused(search);
            search.setFocused(true);
        }
        return super.charTyped(c, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick); // background + search box

        // Panel
        g.fill(panelLeft - 1, panelTop - 1, panelLeft + panelWidth + 1, panelBottom + 1, 0xFF000000);
        g.fill(panelLeft, panelTop, panelLeft + panelWidth, panelBottom, 0xE0202028);

        // Tabs
        for (int i = 0; i < tabs.size(); i++) {
            int x = panelLeft + 6 + (i % tabsPerRow) * TAB;
            int y = panelTop + 6 + (i / tabsPerRow) * TAB;
            int bg = i == tab ? 0xFF6A7BB0 : (tabAt(mx, my) == i ? 0xFF4A4A58 : 0xFF33333D);
            g.fill(x, y, x + TAB - 2, y + TAB - 2, bg);
            g.renderItem(tabs.get(i).icon(), x + 2, y + 2);
        }

        search.render(g, mx, my, partialTick);

        // Grid
        ItemStack hovered = ItemStack.EMPTY;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = gridLeft + c * SLOT;
                int y = gridTop + r * SLOT;
                g.fill(x, y, x + SLOT - 1, y + SLOT - 1, 0xFF2C2C34);
                int idx = (r + scrollRow) * cols + c;
                if (idx < shown.size()) {
                    ItemStack s = shown.get(idx);
                    boolean hover = mx >= x && mx < x + SLOT - 1 && my >= y && my < y + SLOT - 1;
                    if (hover) {
                        g.fill(x, y, x + SLOT - 1, y + SLOT - 1, 0xFF5A5A70);
                        hovered = s;
                    }
                    g.renderItem(s, x + 1, y + 1);
                }
            }
        }

        // Scrollbar
        int max = maxScroll();
        if (max > 0) {
            int trackTop = gridTop, trackH = rows * SLOT - 1;
            int barH = Math.max(10, trackH * rows / (rows + max));
            int barY = trackTop + (trackH - barH) * scrollRow / max;
            int x = panelLeft + panelWidth - 4;
            g.fill(x, trackTop, x + 2, trackTop + trackH, 0xFF15151A);
            g.fill(x, barY, x + 2, barY + barH, 0xFFB0B0C0);
        }

        // Hint
        g.drawCenteredString(font, Component.translatable("gui.freecam.ghostHint"), width / 2, hotbarTop - 12, 0xFFAAAAAA);

        // Flight speed readout between the - and + buttons
        for (var child : children()) {
            if (child instanceof net.minecraft.client.gui.components.Button btn) {
                btn.render(g, mx, my, partialTick); // draw above the panel background
            }
        }
        g.drawCenteredString(font, String.format("%.2f", GhostFlight.speed()), panelLeft + 6 + 134, flightY + 3, 0xFFFFFFFF);

        // Ghost hotbar
        RenderSystem.enableBlend();
        g.blitSprite(HOTBAR, hotbarLeft, hotbarTop, 182, 22);
        g.blitSprite(HOTBAR_SELECTION, hotbarLeft - 1 + GhostHotbar.selected() * 20, hotbarTop - 1, 24, 23);
        RenderSystem.disableBlend();
        for (int i = 0; i < GhostHotbar.SIZE; i++) {
            ItemStack s = GhostHotbar.get(i);
            int x = hotbarLeft + 3 + i * 20;
            g.renderItem(s, x, hotbarTop + 3);
            if (!s.isEmpty() && hotbarAt(mx, my) == i) {
                hovered = s;
            }
        }

        int t = tabAt(mx, my);
        if (t >= 0) {
            g.renderTooltip(font, tabs.get(t).name(), mx, my);
        } else if (!hovered.isEmpty()) {
            g.renderTooltip(font, hovered, mx, my);
        }
    }
}
