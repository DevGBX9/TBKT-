package magicjinn.theblockkeepsticking.mixin;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to the server level's pending block event queue. */
@Mixin(ServerLevel.class)
public interface ServerLevelAccessor {
    @Accessor("blockEvents")
    ObjectLinkedOpenHashSet<BlockEventData> tbkt$getBlockEvents();
}
