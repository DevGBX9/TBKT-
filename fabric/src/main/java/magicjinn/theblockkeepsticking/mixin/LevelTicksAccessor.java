package magicjinn.theblockkeepsticking.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only view of the per-chunk scheduled tick containers. Used only to
 * transfer pending ticks into the event-driven simulator when a debug session
 * starts.
 */
@Mixin(LevelTicks.class)
public interface LevelTicksAccessor {
    @Accessor("allContainers")
    Long2ObjectMap<LevelChunkTicks<?>> tbkt$getAllContainers();
}
