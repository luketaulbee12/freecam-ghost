package net.xolt.freecam.forge.mixins;

import net.minecraft.client.Minecraft;
import net.xolt.freecam.forge.ghost.builder.AutoBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class AutoBuildMinecraftMixin {
    // Vanilla calls stopDestroyBlock() every tick the attack key isn't held, which would cancel
    // the auto-builder's mining. The builder drives block breaking itself while it runs.
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void freecam$autoBuildContinueAttack(boolean leftClick, CallbackInfo ci) {
        if (AutoBuilder.isRunning()) {
            ci.cancel();
        }
    }
}
