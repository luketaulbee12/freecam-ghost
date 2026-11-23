package net.xolt.freecam.forge.mixins;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@link BlockItem#getPlacementState} so ghost placement picks the same
 * state a real placement would (wall torches, wall signs, etc.).
 */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {
    @Invoker("getPlacementState")
    BlockState freecam$getPlacementState(BlockPlaceContext context);
}
