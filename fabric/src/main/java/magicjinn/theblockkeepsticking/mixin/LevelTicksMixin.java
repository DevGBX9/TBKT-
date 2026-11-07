package magicjinn.theblockkeepsticking.mixin;

import magicjinn.theblockkeepsticking.simulator.SimulationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a simulation context is active, block ticks that belong to the target
 * chunk are moved into the simulator's own queue instead of the real level
 * queue. This lets vanilla observers, redstone dust, and pistons keep their
 * normal scheduling semantics while the simulator controls time.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksMixin {
    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void tbkt$captureScheduledTick(ScheduledTick<?> tick, CallbackInfo ci) {
        SimulationContext context = SimulationContext.current();
        if (context == null || !(tick.type() instanceof Block block))
            return;
        if (context.captureBlockTick(block, tick.pos(), tick.triggerTick(), tick.priority(),
                tick.subTickOrder())) {
            ci.cancel();
        }
    }

    @Inject(method = "hasScheduledTick", at = @At("HEAD"), cancellable = true)
    private void tbkt$hasScheduledTick(BlockPos pos, Object type,
            CallbackInfoReturnable<Boolean> cir) {
        SimulationContext context = SimulationContext.current();
        if (context != null && type instanceof Block block && context.hasScheduled(pos, block)) {
            cir.setReturnValue(true);
        }
    }
}
