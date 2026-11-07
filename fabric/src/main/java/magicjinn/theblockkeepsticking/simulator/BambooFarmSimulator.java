package magicjinn.theblockkeepsticking.simulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import magicjinn.theblockkeepsticking.Constants;
import magicjinn.theblockkeepsticking.mixin.LevelTicksAccessor;
import magicjinn.theblockkeepsticking.mixin.ServerLevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.phys.AABB;

/**
 * Event-driven, deterministic simulator for the classic bamboo farm (mud,
 * observer, redstone dust, piston, hopper).
 *
 * <p>
 * Vanilla methods are used for the mechanics themselves: bamboo random ticks,
 * observer scheduled ticks, piston block events and moving-piston ticks, and
 * hopper item transfer. This class only owns the clock, the event queue, the
 * deterministic random source, and the target chunk boundary.
 *
 * <p>
 * The simulator is deliberately independent from the normal server tick loop.
 * A debug run of 72,000 ticks therefore completes in one server tick and never
 * logs per-tick lines. Only aggregate counters and errors are reported.
 */
public final class BambooFarmSimulator {
    public static final int TICKS_PER_HOUR = 72_000;
    public static final long MAX_DEBUG_TICKS = 24L * TICKS_PER_HOUR;
    public static final int MAX_EVENTS_PER_TICK = 65_536;
    public static final int MAX_RECORDED_EXCEPTIONS = 20;
    private static final int ITEM_DESPAWN_AGE = 5_999;

    private static final Map<SessionKey, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final java.util.function.Predicate<BlockState> HAS_BAMBOO = state -> state
            .getBlock() instanceof BambooStalkBlock
            || state.getBlock() instanceof BambooSaplingBlock;
    private static final java.util.function.Predicate<BlockState> HAS_OBSERVER = state -> state
            .is(Blocks.OBSERVER);

    private BambooFarmSimulator() {
    }

    public static Result simulate(ServerLevel level, LevelChunk chunk, long ticks, boolean reset,
            boolean verbose) {
        // Debug entry points validate the range before calling. Automatic
        // catch-up can legitimately ask for more than 24 hours after a very long
        // unload, so the simulator clamps instead of throwing.
        long requested = Math.max(0L, ticks);
        long effective = Math.min(requested, MAX_DEBUG_TICKS);
        if (ticks > MAX_DEBUG_TICKS) {
            Constants.LOG.warn(
                    "TBKT bamboo simulation requested {} ticks, clamping to the maximum of {}. "
                            + "Use the debug command for longer controlled runs.",
                    ticks, MAX_DEBUG_TICKS);
        }

        ChunkPos chunkPos = chunk.getPos();
        if (reset) {
            reset(level, chunkPos);
        }

        SessionKey key = new SessionKey(level.dimension().location().toString(), chunkPos.pack());
        Session session = SESSIONS.computeIfAbsent(key,
                ignored -> new Session(level, chunk, seedFor(level, chunkPos)));

        if (verbose) {
            Constants.LOG.info("TBKT bamboo simulation start: {} ticks in chunk [{}, {}]",
                    effective, chunkPos.x(), chunkPos.z());
        }

        long started = System.nanoTime();
        long executed = session.run(level, chunk, effective);
        long elapsed = System.nanoTime() - started;

        Result result = session.snapshot(requested, executed, elapsed);
        if (verbose) {
            Constants.LOG.info(
                    "TBKT bamboo simulation done: ticks={} growths={} broken={} observerPulses={} pistonExtends={} pistonRetracts={} items={} collected={} delivered={} errors={}",
                    result.executedTicks(), result.bambooGrowths(), result.bambooBroken(),
                    result.observerPulses(), result.pistonExtensions(), result.pistonRetractions(),
                    result.itemsSpawned(), result.itemsCollected(), result.itemsDelivered(),
                    result.exceptions().size());
        }
        return result;
    }

    /** Drops every session, e.g. when the server stops. */
    public static void clearAll() {
        SESSIONS.clear();
    }

    /** Clears the session and any pending vanilla block ticks/events for the chunk. */
    public static void reset(Level level, ChunkPos chunkPos) {
        SESSIONS.remove(new SessionKey(level.dimension().location().toString(), chunkPos.pack()));
        if (level instanceof ServerLevel serverLevel) {
            clearPendingTicks(serverLevel, chunkPos);
            clearPendingBlockEvents(serverLevel, chunkPos);
        }
    }

    public static String status(Level level, ChunkPos chunkPos) {
        Session session = SESSIONS
                .get(new SessionKey(level.dimension().location().toString(), chunkPos.pack()));
        if (session == null)
            return "no session";
        return "clock=" + session.tick + " pendingTicks=" + session.scheduledByKey.size()
                + " pendingEvents=" + session.blockEventQueue.size() + " buckets="
                + session.bambooRandomTicks + " growths=" + session.bambooGrowths + " broken="
                + session.bambooBroken + " pulses=" + session.observerPulses + " items="
                + session.itemsSpawned + " collected=" + session.itemsCollected
                + " delivered=" + session.itemsDelivered;
    }

    private static long seedFor(Level level, ChunkPos pos) {
        long h = mix64(pos.pack() * 0x9E3779B97F4A7C15L);
        h ^= mix64(level.dimension().location().toString().hashCode() * 0xC2B2AE3D27D4EB4FL);
        return h;
    }

    static long mix64(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        value ^= value >>> 33;
        return value;
    }

    static long mix(long seed, long tick, long salt) {
        return mix64(seed ^ mix64(tick + 0x9E3779B97F4A7C15L) ^ mix64(salt));
    }

    private static void clearPendingTicks(ServerLevel level, ChunkPos chunkPos) {
        LevelChunkTicks<?> container = getTickContainer(level, chunkPos);
        if (container != null) {
            // Fluid ticks are left alone; only block ticks are owned by the simulator.
            container.removeIf(tick -> tick.type() instanceof Block);
        }
    }

    private static void clearPendingBlockEvents(ServerLevel level, ChunkPos chunkPos) {
        ((ServerLevelAccessor) (Object) level).tbkt$getBlockEvents()
                .removeIf(event -> chunkPos.contains(event.pos()));
    }

    private static LevelChunkTicks<?> getTickContainer(ServerLevel level, ChunkPos chunkPos) {
        Long2ObjectMap<LevelChunkTicks<?>> containers = ((LevelTicksAccessor) (Object) level
                .getBlockTicks()).tbkt$getAllContainers();
        return containers.get(chunkPos.pack());
    }

    /** Immutable result of one simulation call. */
    public record Result(long requestedTicks, long executedTicks, long wallNanos,
            long bambooRandomTicks, long bambooGrowths, long bambooBroken, long observerPulses,
            long observerTicks, long observerOn, long observerOff, long pistonExtensions,
            long pistonRetractions, long scheduledTicksProcessed, long blockEventsProcessed,
            long itemsSpawned, long bambooItemsSpawned, long itemsCollected, long itemsDelivered,
            long hopperTransferItems, long hopperTransferOperations, long itemsOnGround,
            long itemsInContainers, long itemsAddedToContainers, long itemsRemaining,
            long trackedItemLosses, long minObserverPulseGap, List<String> exceptions,
            Map<String, Long> counters, Map<String, Long> skipped) {
    }

    private record SessionKey(String dimension, long packedChunk) {
    }

    private record TickKey(BlockPos pos, Block block) {
    }

    private record BlockEventKey(Block block, BlockPos pos, int paramA, int paramB) {
    }

    private static final class ScheduledTask implements Comparable<ScheduledTask> {
        final TickKey key;
        final long due;
        final TickPriority priority;
        final long order;
        final long sequence;

        ScheduledTask(TickKey key, long due, TickPriority priority, long order, long sequence) {
            this.key = key;
            this.due = due;
            this.priority = priority;
            this.order = order;
            this.sequence = sequence;
        }

        @Override
        public int compareTo(ScheduledTask other) {
            int byDue = Long.compare(due, other.due);
            if (byDue != 0)
                return byDue;
            int byPriority = Integer.compare(priority.getValue(), other.priority.getValue());
            if (byPriority != 0)
                return byPriority;
            int byOrder = Long.compare(order, other.order);
            if (byOrder != 0)
                return byOrder;
            return Long.compare(sequence, other.sequence);
        }
    }

    private static final class BlockEventTask implements Comparable<BlockEventTask> {
        final Block block;
        final BlockPos pos;
        final int paramA;
        final int paramB;
        final long due;
        final long sequence;

        BlockEventTask(Block block, BlockPos pos, int paramA, int paramB, long due, long sequence) {
            this.block = block;
            this.pos = pos;
            this.paramA = paramA;
            this.paramB = paramB;
            this.due = due;
            this.sequence = sequence;
        }

        @Override
        public int compareTo(BlockEventTask other) {
            int byDue = Long.compare(due, other.due);
            return byDue != 0 ? byDue : Long.compare(sequence, other.sequence);
        }
    }

    static final class Session {
        final long seed;
        final ChunkPos chunkPos;
        final AABB chunkBox;

        long tick;
        long sequence;
        long bambooRandomTicks;
        long bambooGrowths;
        long bambooBroken;
        long observerPulses;
        long observerTicks;
        long observerOn;
        long observerOff;
        long lastObserverPulseTick = Long.MIN_VALUE;
        long minObserverPulseGap = Long.MAX_VALUE;
        long pistonExtensions;
        long pistonRetractions;
        long scheduledTicksProcessed;
        long blockEventsProcessed;
        long itemsSpawned;
        long bambooItemsSpawned;
        long itemsCollected;
        long itemsDelivered;
        long hopperTransferItems;
        long hopperTransferOperations;
        long lastContainerTotal;
        long initialContainerTotal;

        final PriorityQueue<ScheduledTask> scheduledQueue = new PriorityQueue<>();
        final Map<TickKey, ScheduledTask> scheduledByKey = new LinkedHashMap<>();
        final PriorityQueue<BlockEventTask> blockEventQueue = new PriorityQueue<>();
        final Map<BlockEventKey, BlockEventTask> queuedBlockEvents = new LinkedHashMap<>();
        final List<BlockPos> observers = new ArrayList<>();
        final Map<BlockPos, BlockState> observerWatched = new HashMap<>();
        final Map<UUID, ItemEntity> trackedItems = new LinkedHashMap<>();
        final Set<UUID> seenItems = new HashSet<>();
        final List<String> exceptions = new ArrayList<>();
        final Map<String, Long> skipped = new LinkedHashMap<>();

        Session(ServerLevel level, LevelChunk chunk, long seed) {
            this.seed = seed;
            this.chunkPos = chunk.getPos();
            int minX = chunkPos.getMinBlockX();
            int minZ = chunkPos.getMinBlockZ();
            this.chunkBox = new AABB(minX, level.getMinY(), minZ, minX + 16, level.getMaxY() + 1,
                    minZ + 16);

            scanObservers(level, chunk);
            harvestScheduledTicks(level);
            harvestBlockEvents(level);
            scanItems(level);
            lastContainerTotal = containerTotal(chunk);
            initialContainerTotal = lastContainerTotal;
        }

        long run(ServerLevel level, LevelChunk chunk, long ticks) {
            SimulationContext context = new SimulationContext(this, level, chunkPos);
            SimulationContext.push(context);
            long executed = 0;
            try {
                harvestExternal(level, chunk);
                for (long i = 0; i < ticks; i++) {
                    context.setCurrentTick(tick);
                    try {
                        processScheduled(level);
                        processBlockEvents(level);
                        tickBlockEntities(level, chunk);
                        randomTicks(level, chunk);
                        checkObservers(level);
                        tickItems();
                    } catch (Throwable throwable) {
                        recordException("tick " + tick + ": " + throwable.getClass().getSimpleName()
                                + ": " + throwable.getMessage());
                        Constants.LOG.error("TBKT bamboo simulator error at tick {}", tick, throwable);
                    }
                    tick++;
                    executed++;
                }
            } finally {
                SimulationContext.pop();
            }
            scanItems(level);
            lastContainerTotal = containerTotal(chunk);
            return executed;
        }

        void enqueueScheduled(Block block, BlockPos pos, long due, TickPriority priority,
                long subTickOrder) {
            TickKey key = new TickKey(pos.immutable(), block);
            ScheduledTask existing = scheduledByKey.get(key);
            long effectiveDue = Math.max(due, tick);
            if (existing != null && existing.due <= effectiveDue) {
                return;
            }
            ScheduledTask task = new ScheduledTask(key, effectiveDue,
                    priority == null ? TickPriority.NORMAL : priority, subTickOrder, ++sequence);
            scheduledByKey.put(key, task);
            scheduledQueue.add(task);
        }

        boolean hasScheduled(BlockPos pos, Block block) {
            return scheduledByKey.containsKey(new TickKey(pos, block));
        }

        void enqueueBlockEvent(Block block, BlockPos pos, int paramA, int paramB, long due) {
            long effectiveDue = Math.max(due, tick);
            BlockPos immutablePos = pos.immutable();
            BlockEventKey key = new BlockEventKey(block, immutablePos, paramA, paramB);
            if (queuedBlockEvents.containsKey(key)) {
                // Vanilla keeps the already queued event and ignores duplicates.
                skip("duplicate_block_event");
                return;
            }
            BlockEventTask task = new BlockEventTask(block, immutablePos, paramA, paramB, effectiveDue,
                    ++sequence);
            queuedBlockEvents.put(key, task);
            blockEventQueue.add(task);
        }

        void trackSpawnedItem(ItemEntity item) {
            if (item == null || !item.isAlive())
                return;
            if (!chunkPos.contains(item.blockPosition())) {
                skip("item_outside_chunk");
                return;
            }

            UUID id = item.getUUID();
            if (!seenItems.add(id))
                return;

            trackedItems.put(id, item);
            int count = item.getItem().getCount();
            if (count <= 0)
                return;
            itemsSpawned += count;
            if (item.getItem().is(Items.BAMBOO)) {
                bambooItemsSpawned += count;
            }
        }

        private void harvestExternal(ServerLevel level, LevelChunk chunk) {
            refreshObservers(level, chunk);
            harvestScheduledTicks(level);
            harvestBlockEvents(level);
            scanItems(level);
            lastContainerTotal = containerTotal(chunk);
        }

        /**
         * Picks up observers placed since the session started without losing the
         * previous watched state of observers we already track. Losing that state
         * would hide a change made outside the simulator.
         */
        private void refreshObservers(ServerLevel level, LevelChunk chunk) {
            Map<BlockPos, BlockState> previous = new HashMap<>(observerWatched);
            scanObservers(level, chunk);
            for (BlockPos pos : observers) {
                BlockState watched = previous.get(pos);
                if (watched != null) {
                    observerWatched.put(pos, watched);
                }
            }
        }

        private void processScheduled(ServerLevel level) {
            int processed = 0;
            while (!scheduledQueue.isEmpty() && scheduledQueue.peek().due <= tick) {
                if (++processed > MAX_EVENTS_PER_TICK) {
                    skip("scheduled_tick_guard");
                    recordException("scheduled tick guard exceeded at tick " + tick);
                    break;
                }
                ScheduledTask task = scheduledQueue.poll();
                if (scheduledByKey.get(task.key) != task) {
                    continue;
                }
                scheduledByKey.remove(task.key);

                BlockState state = level.getBlockState(task.key.pos());
                if (!state.is(task.key.block())) {
                    skip("stale_scheduled_tick");
                    continue;
                }

                if (task.key.block() instanceof ObserverBlock) {
                    boolean powered = state.getValue(ObserverBlock.POWERED);
                    observerTicks++;
                    if (powered) {
                        observerOff++;
                    } else {
                        observerOn++;
                        observerPulses++;
                        if (lastObserverPulseTick != Long.MIN_VALUE) {
                            long gap = tick - lastObserverPulseTick;
                            if (gap < minObserverPulseGap)
                                minObserverPulseGap = gap;
                        }
                        lastObserverPulseTick = tick;
                    }
                }

                RandomSource random = RandomSource
                        .create(mix(seed, tick, task.key.pos().asLong() ^ task.sequence));
                state.tick(level, task.key.pos(), random);
                scheduledTicksProcessed++;
            }
        }

        private void processBlockEvents(ServerLevel level) {
            int processed = 0;
            while (!blockEventQueue.isEmpty() && blockEventQueue.peek().due <= tick) {
                if (++processed > MAX_EVENTS_PER_TICK) {
                    skip("block_event_guard");
                    recordException("block event guard exceeded at tick " + tick);
                    break;
                }
                BlockEventTask task = blockEventQueue.poll();
                BlockEventKey key = new BlockEventKey(task.block, task.pos, task.paramA,
                        task.paramB);
                if (queuedBlockEvents.get(key) != task) {
                    skip("stale_block_event");
                    continue;
                }
                queuedBlockEvents.remove(key);
                BlockState state = level.getBlockState(task.pos);
                if (!state.is(task.block)) {
                    skip("stale_block_event");
                    continue;
                }

                boolean changed = ((BlockBehaviour) task.block).triggerEvent(state, level, task.pos,
                        task.paramA, task.paramB);
                if (changed && task.block instanceof PistonBaseBlock) {
                    if (task.paramA == PistonBaseBlock.TRIGGER_EXTEND) {
                        pistonExtensions++;
                    } else if (task.paramA == PistonBaseBlock.TRIGGER_CONTRACT) {
                        pistonRetractions++;
                    }
                }
                blockEventsProcessed++;
                if (changed) {
                    scanItems(level);
                }
            }
        }

        private void tickBlockEntities(ServerLevel level, LevelChunk chunk) {
            Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
            List<BlockEntity> snapshot = null;
            for (BlockEntity blockEntity : blockEntities.values()) {
                if (blockEntity instanceof PistonMovingBlockEntity
                        || blockEntity instanceof HopperBlockEntity) {
                    snapshot = new ArrayList<>(blockEntities.values());
                    break;
                }
            }
            if (snapshot == null)
                return;

            for (BlockEntity blockEntity : snapshot) {
                if (blockEntity instanceof PistonMovingBlockEntity piston
                        && !blockEntity.isRemoved()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    PistonMovingBlockEntity.tick(level, pos, level.getBlockState(pos), piston);
                }
            }

            for (BlockEntity blockEntity : snapshot) {
                if (blockEntity instanceof HopperBlockEntity hopper && !blockEntity.isRemoved()) {
                    HopperBlockEntity.pushItemsTick(level, blockEntity.getBlockPos(),
                            level.getBlockState(blockEntity.getBlockPos()), hopper);
                }
            }
        }

        private void randomTicks(ServerLevel level, LevelChunk chunk) {
            int randomTickSpeed = level.getGameRules().get(GameRules.RANDOM_TICK_SPEED);
            if (randomTickSpeed <= 0)
                return;

            int sectionCount = chunk.getHeight() >> 4;
            int minY = chunk.getMinY();
            int minX = chunkPos.getMinBlockX();
            int minZ = chunkPos.getMinBlockZ();
            for (int sectionY = 0; sectionY < sectionCount; sectionY++) {
                LevelChunkSection section = chunk.getSection(sectionY);
                if (section == null || section.hasOnlyAir() || !section.maybeHas(HAS_BAMBOO)) {
                    continue;
                }

                RandomSource random = RandomSource
                        .create(mix(seed, tick, sectionY * 0x9E3779B1L));
                for (int i = 0; i < randomTickSpeed; i++) {
                    int x = random.nextInt(16);
                    int y = random.nextInt(16);
                    int z = random.nextInt(16);
                    BlockState state = section.getBlockState(x, y, z);
                    if (!(state.getBlock() instanceof BambooStalkBlock)
                            && !(state.getBlock() instanceof BambooSaplingBlock)) {
                        continue;
                    }
                    if (!state.isRandomlyTicking()) {
                        skip("not_randomly_ticking");
                        continue;
                    }

                    BlockPos pos = new BlockPos(minX + x, minY + (sectionY << 4) + y, minZ + z);
                    BlockState aboveBefore = level.getBlockState(pos.above());
                    state.randomTick(level, pos, random);
                    bambooRandomTicks++;
                    BlockState after = level.getBlockState(pos);
                    if (!after.equals(state) || !level.getBlockState(pos.above()).equals(aboveBefore)) {
                        bambooGrowths++;
                    }
                }
            }
        }

        private void checkObservers(ServerLevel level) {
            if (observers.isEmpty())
                return;

            Iterator<BlockPos> iterator = observers.iterator();
            while (iterator.hasNext()) {
                BlockPos pos = iterator.next();
                BlockState state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof ObserverBlock)) {
                    iterator.remove();
                    observerWatched.remove(pos);
                    continue;
                }

                BlockPos watchedPos = pos.relative(state.getValue(ObserverBlock.FACING));
                BlockState watched = level.getBlockState(watchedPos);
                BlockState previous = observerWatched.put(pos, watched);
                if (previous != null && !previous.equals(watched)
                        && !state.getValue(ObserverBlock.POWERED)
                        && !hasScheduled(pos, state.getBlock())) {
                    enqueueScheduled(state.getBlock(), pos, tick + 2, TickPriority.NORMAL, ++sequence);
                }
            }
        }

        private void tickItems() {
            Iterator<Map.Entry<UUID, ItemEntity>> iterator = trackedItems.entrySet().iterator();
            while (iterator.hasNext()) {
                ItemEntity item = iterator.next().getValue();
                if (!item.isAlive()) {
                    iterator.remove();
                    continue;
                }
                int ageBefore = item.getAge();
                item.tick();
                if (!item.isAlive()) {
                    iterator.remove();
                    if (!item.getItem().isEmpty() && ageBefore >= ITEM_DESPAWN_AGE) {
                        skip("item_despawned");
                    }
                }
            }
        }

        private void scanItems(ServerLevel level) {
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, chunkBox,
                    ItemEntity::isAlive)) {
                // The query box is axis-aligned and can overlap a neighbouring chunk.
                // Only items whose own block position is inside the target chunk count.
                if (!chunkPos.contains(item.blockPosition()))
                    continue;
                UUID id = item.getUUID();
                seenItems.add(id);
                trackedItems.put(id, item);
            }
            trackedItems.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        }

        private void scanObservers(ServerLevel level, LevelChunk chunk) {
            int sectionCount = chunk.getHeight() >> 4;
            int minY = chunk.getMinY();
            int minX = chunkPos.getMinBlockX();
            int minZ = chunkPos.getMinBlockZ();
            observers.clear();
            observerWatched.clear();
            for (int sectionY = 0; sectionY < sectionCount; sectionY++) {
                LevelChunkSection section = chunk.getSection(sectionY);
                if (section == null || section.hasOnlyAir() || !section.maybeHas(HAS_OBSERVER)) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (!state.is(Blocks.OBSERVER)) {
                                continue;
                            }
                            BlockPos pos = new BlockPos(minX + x, minY + (sectionY << 4) + y,
                                    minZ + z);
                            observers.add(pos);
                            observerWatched.put(pos, level.getBlockState(
                                    pos.relative(state.getValue(ObserverBlock.FACING))));
                        }
                    }
                }
            }
        }

        private void harvestScheduledTicks(ServerLevel level) {
            LevelChunkTicks<?> container = getTickContainer(level, chunkPos);
            if (container == null)
                return;

            final List<ScheduledTick<?>> pending = new ArrayList<>();
            container.getAll()
                    .filter(scheduled -> scheduled.type() instanceof Block)
                    .forEach(pending::add);
            container.removeIf(scheduled -> scheduled.type() instanceof Block);
            for (ScheduledTick<?> scheduled : pending) {
                if (!(scheduled.type() instanceof Block block)) {
                    continue;
                }
                long delay = Math.max(0L, scheduled.triggerTick() - level.getGameTime());
                enqueueScheduled(block, scheduled.pos(), tick + delay, scheduled.priority(),
                        scheduled.subTickOrder());
            }
        }

        private void harvestBlockEvents(ServerLevel level) {
            Set<BlockEventData> pending = ((ServerLevelAccessor) (Object) level)
                    .tbkt$getBlockEvents();
            List<BlockEventData> matching = pending.stream()
                    .filter(event -> chunkPos.contains(event.pos())).toList();
            pending.removeIf(event -> chunkPos.contains(event.pos()));
            for (BlockEventData event : matching) {
                enqueueBlockEvent(event.block(), event.pos(), event.paramA(), event.paramB(),
                        tick + 1);
            }
        }

        private long containerTotal(LevelChunk chunk) {
            if (chunk == null)
                return lastContainerTotal;
            long total = 0;
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                if (!(blockEntity instanceof Container container))
                    continue;
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    var stack = container.getItem(slot);
                    if (!stack.isEmpty())
                        total += stack.getCount();
                }
            }
            return total;
        }

        void recordException(String message) {
            if (exceptions.size() >= MAX_RECORDED_EXCEPTIONS)
                return;

            exceptions.add(message);
            Constants.LOG.warn("TBKT bamboo simulator exception: {}", message);
            if (exceptions.size() == MAX_RECORDED_EXCEPTIONS) {
                Constants.LOG.error("TBKT bamboo simulator reached the exception limit ({}); "
                        + "further exceptions in this run are not recorded.", MAX_RECORDED_EXCEPTIONS);
            }
        }

        void skip(String reason) {
            skipped.merge(reason, 1L, Long::sum);
        }

        Result snapshot(long requestedTicks, long executedTicks, long wallNanos) {
            long itemsOnGround = 0;
            for (ItemEntity item : trackedItems.values()) {
                if (item.isAlive()) {
                    itemsOnGround += item.getItem().getCount();
                }
            }
            // Report the real inventory total as "remaining", and the net amount
            // added by this run separately. A chest that already contained items
            // when the session started must not look like duplicated output.
            long itemsInContainers = lastContainerTotal;
            long itemsAddedToContainers = Math.max(0L, lastContainerTotal - initialContainerTotal);
            long itemsRemaining = itemsOnGround + itemsInContainers;
            long losses = Math.max(0L, itemsSpawned - itemsCollected - itemsOnGround);

            Map<String, Long> counters = new LinkedHashMap<>();
            counters.put("bamboo_random_ticks", bambooRandomTicks);
            counters.put("bamboo_growths", bambooGrowths);
            counters.put("bamboo_broken", bambooBroken);
            counters.put("observer_pulses", observerPulses);
            counters.put("observer_ticks", observerTicks);
            counters.put("observer_on", observerOn);
            counters.put("observer_off", observerOff);
            counters.put("piston_extensions", pistonExtensions);
            counters.put("piston_retractions", pistonRetractions);
            counters.put("scheduled_ticks", scheduledTicksProcessed);
            counters.put("block_events", blockEventsProcessed);
            counters.put("items_spawned", itemsSpawned);
            counters.put("bamboo_items_spawned", bambooItemsSpawned);
            counters.put("items_collected", itemsCollected);
            counters.put("items_delivered", itemsDelivered);
            counters.put("hopper_transfer_items", hopperTransferItems);
            counters.put("hopper_transfer_operations", hopperTransferOperations);
            counters.put("items_on_ground", itemsOnGround);
            counters.put("initial_container_items", initialContainerTotal);
            counters.put("items_in_containers", itemsInContainers);
            counters.put("items_added_to_containers", itemsAddedToContainers);
            counters.put("items_remaining", itemsRemaining);
            counters.put("item_losses", losses);
            counters.put("pending_ticks", (long) scheduledByKey.size());
            counters.put("pending_events", (long) blockEventQueue.size());
            counters.put("min_observer_pulse_gap",
                    minObserverPulseGap == Long.MAX_VALUE ? -1L : minObserverPulseGap);

            return new Result(requestedTicks, executedTicks, wallNanos, bambooRandomTicks,
                    bambooGrowths, bambooBroken, observerPulses, observerTicks, observerOn,
                    observerOff, pistonExtensions, pistonRetractions, scheduledTicksProcessed,
                    blockEventsProcessed, itemsSpawned, bambooItemsSpawned, itemsCollected,
                    itemsDelivered, hopperTransferItems, hopperTransferOperations, itemsOnGround,
                    itemsInContainers, itemsAddedToContainers, itemsRemaining, losses,
                    minObserverPulseGap == Long.MAX_VALUE ? -1L : minObserverPulseGap,
                    List.copyOf(exceptions), Map.copyOf(counters), Map.copyOf(skipped));
        }
    }
}
