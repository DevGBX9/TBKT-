package magicjinn.theblockkeepsticking.platform.services;

import java.nio.file.Path;
import java.util.function.Consumer;
import com.mojang.brigadier.CommandDispatcher;
import magicjinn.theblockkeepsticking.api.InitializeTickingBlocks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public interface ITBKTPlatform {
    String getPlatformName();

    /**
     * Published Fabric mod id (hyphenated).
     */
    String getPublishedModId();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    Path getConfigDirectory();

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

    /** Register persistent chunk attachments used for catch-up timing. */
    void registerChunkAttachments();

    long getChunkLong(LevelChunk chunk, ChunkLongKey key, long defaultValue);

    void setChunkLong(LevelChunk chunk, ChunkLongKey key, long value);

    void registerServerTickEnd(Consumer<MinecraftServer> callback);

    void registerServerStarted(Consumer<MinecraftServer> callback);

    void registerServerStopped(Consumer<MinecraftServer> callback);

    void registerCommands(Consumer<CommandDispatcher<CommandSourceStack>> callback);

    void registerChunkFullStatusChange(ChunkFullStatusCallback callback);

    void invokeTickingBlockEntrypoints(InitializeTickingBlocks.TickingBlockRegistry registry);

    /**
     * Vanilla accepts a {@code Block} here; the platform bridge also passes the
     * current state for mappings that need it.
     */
    float getCropGrowthSpeed(Block block, BlockState state, BlockGetter level, BlockPos pos);

    enum ChunkLongKey {
        LAST_UPDATE_TIME,
        LAST_REALTIME_UPDATE_MS
    }

    @FunctionalInterface
    interface ChunkFullStatusCallback {
        void onChange(ServerLevel level, LevelChunk chunk, FullChunkStatus oldStatus,
                FullChunkStatus newStatus);
    }
}
