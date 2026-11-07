package magicjinn.theblockkeepsticking.mixin;

import magicjinn.theblockkeepsticking.simulator.SimulationContext;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts item movement performed by a hopper while the debug simulator is
 * advancing the target chunk.
 *
 * <p>
 * Both directions go through the four-argument {@code addItem} overload: a
 * hopper pulling an {@link net.minecraft.world.entity.item.ItemEntity} is
 * implemented as {@code addItem(null, hopper, stack, null)}, while pushing to a
 * chest calls {@code addItem(hopper, chest, stack, direction)}.
 *
 * <p>
 * Vanilla mutates the input stack in place while merging it into the
 * destination, so the original count must be captured at method entry.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntityMixin {
    private static final ThreadLocal<Integer> TBKT_INPUT_COUNT = ThreadLocal.withInitial(() -> 0);

    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"))
    private static void tbkt$captureInputCount(Container from, Container to, ItemStack input,
            Direction direction, CallbackInfoReturnable<ItemStack> callback) {
        TBKT_INPUT_COUNT.set(input.getCount());
    }

    @Inject(method = "addItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
    private static void tbkt$countHopperTransfer(Container from, Container to, ItemStack input,
            Direction direction, CallbackInfoReturnable<ItemStack> callback) {
        int before = TBKT_INPUT_COUNT.get();
        TBKT_INPUT_COUNT.remove();

        SimulationContext context = SimulationContext.current();
        if (context == null)
            return;

        ItemStack remaining = callback.getReturnValue();
        int moved = before - (remaining == null ? 0 : remaining.getCount());
        context.recordHopperTransfer(moved, from, to);
    }
}
