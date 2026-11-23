package net.xolt.freecam.forge.ghost;

import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.xolt.freecam.config.ModConfig;

/** In-game controls for Freecam's flight mode and speed (persisted to Freecam's config). */
public final class GhostFlight {
    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 10.0;

    private GhostFlight() {}

    public static boolean isCreative() {
        return ModConfig.INSTANCE.movement.flightMode == ModConfig.FlightMode.CREATIVE;
    }

    public static void toggleMode() {
        ModConfig.INSTANCE.movement.flightMode = isCreative() ? ModConfig.FlightMode.DEFAULT : ModConfig.FlightMode.CREATIVE;
        persist();
        notifyPlayer(Component.translatable("msg.freecam.flightMode", modeName()));
    }

    public static Component modeName() {
        return Component.translatable(isCreative() ? "gui.freecam.flightCreative" : "gui.freecam.flightInstant");
    }

    public static double speed() {
        return ModConfig.INSTANCE.movement.horizontalSpeed;
    }

    /** Scales horizontal and vertical speed together, keeping their ratio. */
    public static void adjustSpeed(int steps) {
        ModConfig.MovementConfig m = ModConfig.INSTANCE.movement;
        double factor = Math.pow(1.15, steps);
        double h = clamp(m.horizontalSpeed * factor);
        double ratio = m.horizontalSpeed > 0 ? m.verticalSpeed / m.horizontalSpeed : 1.0;
        m.horizontalSpeed = round(h);
        m.verticalSpeed = round(clamp(h * ratio));
        persist();
        notifyPlayer(Component.translatable("msg.freecam.flightSpeed", String.format("%.2f", m.horizontalSpeed)));
    }

    private static double clamp(double v) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, v));
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static void persist() {
        try {
            AutoConfig.getConfigHolder(ModConfig.class).save();
        } catch (Exception ignored) {
        }
    }

    private static void notifyPlayer(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.screen == null) {
            mc.player.displayClientMessage(c, true);
        }
    }
}
