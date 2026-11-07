package magicjinn.theblockkeepsticking.mixin;

import magicjinn.theblockkeepsticking.simulator.SimulationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures piston/block events for the simulated chunk. Block events are
 * normally run one tick later by the server; the simulator keeps that delay.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelBlockEventMixin {
    @Inject(method = "blockEvent", at = @At("HEAD"), cancellable = true)
    private void tbkt$captureBlockEvent(BlockPos pos, Block block, int paramA, int paramB,
            CallbackInfo ci) {
        SimulationContext context = SimulationContext.current();
        if (context != null && context.captureBlockEvent(block, pos, paramA, paramB)) {
            ci.cancel();
        }
    }
}
