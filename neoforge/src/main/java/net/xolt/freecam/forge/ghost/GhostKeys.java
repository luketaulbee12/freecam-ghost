package net.xolt.freecam.forge.ghost;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

public final class GhostKeys {
    private static final String CATEGORY = "category.freecam.freecam";

    public static final KeyMapping SAVE = key("ghostSave", KeyModifier.NONE, GLFW.GLFW_KEY_I);
    public static final KeyMapping UNDO = key("ghostUndo", KeyModifier.CONTROL, GLFW.GLFW_KEY_Z);
    public static final KeyMapping REDO = key("ghostRedo", KeyModifier.CONTROL, GLFW.GLFW_KEY_Y);
    public static final KeyMapping CLEAR = key("ghostClear", KeyModifier.CONTROL, GLFW.GLFW_KEY_BACKSPACE);
    public static final KeyMapping TOGGLE_VISIBLE = key("ghostToggleVisible", KeyModifier.CONTROL, GLFW.GLFW_KEY_G);
    public static final KeyMapping FLIGHT_MODE = key("flightMode", KeyModifier.CONTROL, GLFW.GLFW_KEY_F);
    public static final KeyMapping AUTO_BUILD = key("ghostAutoBuild", KeyModifier.CONTROL, GLFW.GLFW_KEY_B);
    public static final KeyMapping OPEN_MENU = key("ghostMenu", KeyModifier.NONE, GLFW.GLFW_KEY_J);

    private GhostKeys() {}

    private static KeyMapping key(String name, KeyModifier modifier, int key) {
        return new KeyMapping("key.freecam." + name, KeyConflictContext.IN_GAME, modifier,
                InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(SAVE);
        event.register(UNDO);
        event.register(REDO);
        event.register(CLEAR);
        event.register(TOGGLE_VISIBLE);
        event.register(FLIGHT_MODE);
        event.register(AUTO_BUILD);
        event.register(OPEN_MENU);
    }
}
