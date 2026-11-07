package magicjinn.theblockkeepsticking.simulator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.ticks.TickPriority;

/**
 * Thread-local bridge between vanilla scheduling calls and the bamboo farm
 * simulator. Only the server thread can run a simulation, so a thread local is
 * sufficient and does not leak state between worlds.
 *
 * <p>
 * The methods here are public because Mixin classes live in a different
 * package. They all no-op outside an active simulation context.
 */
public final class SimulationContext {
    private static final ThreadLocal<SimulationContext> CURRENT = new ThreadLocal<>();

    private final BambooFarmSimulator.Session session;
    private final ServerLevel level;
    private final ChunkPos chunkPos;
    private long currentTick;

    SimulationContext(BambooFarmSimulator.Session session, ServerLevel level, ChunkPos chunkPos) {
        this.session = session;
        this.level = level;
        this.chunkPos = chunkPos;
    }

    static void push(SimulationContext context) {
        CURRENT.set(context);
    }

    static void pop() {
        CURRENT.remove();
    }

    public static SimulationContext current() {
        return CURRENT.get();
    }

    void setCurrentTick(long currentTick) {
        this.currentTick = currentTick;
    }

    public long currentTick() {
        return currentTick;
    }

    boolean isTarget(BlockPos pos) {
        return (pos.getX() >> 4) == chunkPos.x() && (pos.getZ() >> 4) == chunkPos.z();
    }

    /** Captures a scheduled block tick for the target chunk. */
    public boolean captureBlockTick(Block block, BlockPos pos, long triggerTick,
            TickPriority priority, long subTickOrder) {
        if (!isTarget(pos))
            return false;

        long delay = Math.max(0L, triggerTick - level.getGameTime());
        session.enqueueScheduled(block, pos.immutable(), currentTick + delay, priority,
                subTickOrder);
        return true;
    }

    /** Captures a piston/redstone block event for the target chunk. */
    public boolean captureBlockEvent(Block block, BlockPos pos, int paramA, int paramB) {
        if (!isTarget(pos))
            return false;

        session.enqueueBlockEvent(block, pos.immutable(), paramA, paramB, currentTick + 1L);
        return true;
    }

    /** Captures an item entity that vanilla just added to the level. */
    public void captureSpawnedItem(ItemEntity item) {
        if (item == null || !isTarget(item.blockPosition()))
            return;
        session.trackSpawnedItem(item);
    }

    /**
     * Records a bamboo-to-air transition. Called for every {@code Level.setBlock}
     * while the simulator owns time; the session filters non-bamboo replacements.
     */
    public void recordBlockReplacement(BlockPos pos, BlockState oldState, BlockState newState) {
        if (pos == null || oldState == null || newState == null)
            return;
        if (!isTarget(pos))
            return;
        if (newState.isAir()
                && (oldState.getBlock() instanceof BambooStalkBlock
                        || oldState.getBlock() instanceof BambooSaplingBlock)) {
            session.bambooBroken++;
        }
    }

    /**
     * Records a successful hopper item move. The four-argument vanilla
     * {@code HopperBlockEntity.addItem} is the single funnel for both sucking an
     * item entity into a hopper and pushing a stack into the next container.
     */
    public void recordHopperTransfer(int moved, net.minecraft.world.Container from,
            net.minecraft.world.Container to) {
        if (moved <= 0)
            return;
        session.hopperTransferItems += moved;
        session.hopperTransferOperations++;
        // A null source means the stack came from an ItemEntity on the ground.
        if (from == null
                && to instanceof net.minecraft.world.level.block.entity.HopperBlockEntity) {
            session.itemsCollected += moved;
        }
        if (from instanceof net.minecraft.world.level.block.entity.HopperBlockEntity
                && !(to instanceof net.minecraft.world.level.block.entity.HopperBlockEntity)) {
            session.itemsDelivered += moved;
        }
    }

    public boolean hasScheduled(BlockPos pos, Block block) {
        return isTarget(pos) && session.hasScheduled(pos, block);
    }
}
