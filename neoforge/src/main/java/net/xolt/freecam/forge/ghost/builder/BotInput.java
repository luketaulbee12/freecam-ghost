package net.xolt.freecam.forge.ghost.builder;

import net.minecraft.client.player.Input;

/** Movement input driven by the auto-builder instead of the keyboard. */
final class BotInput extends Input {
    boolean forward;
    boolean jump;
    boolean sneak;

    void clear() {
        forward = false;
        jump = false;
        sneak = false;
    }

    @Override
    public void tick(boolean movingSlowly, float slowFactor) {
        up = forward;
        down = false;
        left = false;
        right = false;
        forwardImpulse = forward ? 1.0F : 0.0F;
        leftImpulse = 0.0F;
        jumping = jump;
        shiftKeyDown = sneak;
        if (movingSlowly) {
            forwardImpulse *= slowFactor;
        }
    }
}
