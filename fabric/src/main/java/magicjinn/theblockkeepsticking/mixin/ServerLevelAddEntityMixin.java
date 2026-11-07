package magicjinn.theblockkeepsticking.mixin;

import magicjinn.theblockkeepsticking.simulator.SimulationContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures item drops created while the bamboo farm simulator is running.
 *
 * <p>
 * Bamboo broken by a piston is turned into an {@link ItemEntity} by vanilla
 * {@code Block.dropResources}. The simulator tracks that exact entity from the
 * moment it enters the level, so the item can be ticked (falling, hopper
 * pickup) and counted without scanning or guessing.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelAddEntityMixin {
    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void tbkt$captureSpawnedItem(Entity entity,
            CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue())
            return;

        SimulationContext context = SimulationContext.current();
        if (context != null && entity instanceof ItemEntity item) {
            context.captureSpawnedItem(item);
        }
    }
}
