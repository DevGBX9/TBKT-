package magicjinn.theblockkeepsticking.mixin;

import magicjinn.theblockkeepsticking.simulator.SimulationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts bamboo blocks removed while the simulator is active.
 *
 * <p>
 * Pistons do not call {@code Level.destroyBlock} for a bamboo block: vanilla
 * drops the block and then replaces it with air through {@code setBlock}. This
 * hook observes the old and new states and records only bamboo-to-air
 * transitions.
 */
@Mixin(Level.class)
public abstract class LevelSetBlockMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void tbkt$countBambooRemoval(BlockPos pos, BlockState newState, int flags,
            int recursion, CallbackInfoReturnable<Boolean> callback) {
        SimulationContext context = SimulationContext.current();
        if (context == null)
            return;

        Level level = (Level) (Object) this;
        context.recordBlockReplacement(pos, level.getBlockState(pos), newState);
    }
}
