package magicjinn.theblockkeepsticking.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import magicjinn.theblockkeepsticking.Constants;
import magicjinn.theblockkeepsticking.config.DebugFileConfig;
import magicjinn.theblockkeepsticking.config.ModConfig;
import magicjinn.theblockkeepsticking.mixin.CactusBlockAccessor;
import magicjinn.theblockkeepsticking.simulator.BambooFarmSimulator;
import magicjinn.theblockkeepsticking.simulator.DebugSimulation;
import magicjinn.theblockkeepsticking.simulator.WorldSimulator;
import magicjinn.theblockkeepsticking.util.TickingCalculator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/**
 * Deterministic in-process self-test. Places blocks via vanilla commands,
 * advances them with {@code /tbkt simulate}, then compares resulting states to
 * expectations derived from {@link TickingCalculator} (same formulas as the
 * mixins).
 *
 * <p>
 * Opt-in only via {@code -Dtbkt.selftest=true} or {@code TBKT_SELFTEST=true}
 * (e.g. {@code runSelftest}). Halts the JVM with exit 0/1 when finished.
 */
public final class TbktSelfTest {
    public static final String SYSTEM_PROPERTY = "tbkt.selftest";
    public static final String ENV_VAR = "TBKT_SELFTEST";
    public static final String RESULT_MARKER = "TBKT_SELFTEST_RESULT";

    /** High, fixed platform away from spawn noise. Chunk (10, 10). */
    private static final int ORIGIN_X = 160;
    private static final int ORIGIN_Y = 200;
    private static final int ORIGIN_Z = 160;
    private static final int CHUNK_X = ORIGIN_X >> 4;
    private static final int CHUNK_Z = ORIGIN_Z >> 4;

    /** Settling ticks after SERVER_STARTED before auto-run. */
    private static final int AUTO_SETTLE_TICKS = 40;

    /** Random ticks per growth pulse (enough for >1 full age cycle). */
    private static final int CACTUS_PULSE_RANDOM_TICKS = 64;
    /** Upper bound so a stuck world cannot hang the selftest. */
    private static final int CACTUS_MAX_PULSES = 10_000;
    /** Colony size for the high-volume RNG soak (not literally a billion). */
    private static final int CACTUS_COLONY_SIZE = 16;
    /**
     * Vanilla {@code BambooStalkBlock.growBamboo} may mark STAGE_DONE from this
     * height (unnamed magic number in code).
     */
    private static final int MIN_BAMBOO_HEIGHT = 11;
    private static final int RNG_PULSE_RANDOM_TICKS = 128;
    private static final int RNG_MAX_PULSES = 10_000;

    private static final AtomicInteger autoCountdown = new AtomicInteger(-1);
    private static boolean autoArmed;

    @FunctionalInterface
    private interface TestCase {
        boolean run(MinecraftServer server, ServerLevel level, CommandSourceStack source);
    }

    /** Ordered registry: name -> case. */
    private static final Map<String, TestCase> CASES = new LinkedHashMap<>();
    static {
        CASES.put("wheat_growth", TbktSelfTest::caseWheatGrowth);
        CASES.put("wheat_mature", TbktSelfTest::caseWheatAlreadyMature);
        CASES.put("nether_wart", TbktSelfTest::caseNetherWartGrowth);
        CASES.put("farmland_hydrate", TbktSelfTest::caseFarmlandHydrate);
        CASES.put("farmland_dry", TbktSelfTest::caseFarmlandDry);
        CASES.put("sweet_berry", TbktSelfTest::caseSweetBerryGrowth);
        CASES.put("cocoa", TbktSelfTest::caseCocoaGrowth);
        CASES.put("sugar_cane_age", TbktSelfTest::caseSugarCaneAge);
        CASES.put("cactus_max_height", TbktSelfTest::caseCactusMaxHeight);
        CASES.put("cactus_colony", TbktSelfTest::caseCactusColony);
        CASES.put("sapling_tree", TbktSelfTest::caseSaplingBecomesTree);
        CASES.put("stem_fruit", TbktSelfTest::caseStemGrowsFruit);
        CASES.put("bamboo_height", TbktSelfTest::caseBambooMaxHeight);
        CASES.put("leaves_decay", TbktSelfTest::caseLeavesDecay);
        CASES.put("amethyst_full", TbktSelfTest::caseAmethystFullyGrown);
        CASES.put("dripstone_clay", TbktSelfTest::caseDripstoneMudToClay);
        CASES.put("dripstone_cauldron", TbktSelfTest::caseDripstoneFillsCauldron);
        CASES.put("chunk_simulate", TbktSelfTest::caseChunkSimulate);

        // Event-driven bamboo farm simulator (Plus features), tests 1-13.
        CASES.put("bamboo_farm_growth", TbktSelfTest::caseFarmBambooGrowth);
        CASES.put("bamboo_farm_observer_detect", TbktSelfTest::caseFarmObserverDetect);
        CASES.put("bamboo_farm_observer_timing", TbktSelfTest::caseFarmObserverTiming);
        CASES.put("bamboo_farm_piston", TbktSelfTest::caseFarmPiston);
        CASES.put("bamboo_farm_item_generation", TbktSelfTest::caseFarmItemGeneration);
        CASES.put("bamboo_farm_hopper", TbktSelfTest::caseFarmHopper);
        CASES.put("bamboo_farm_hopper_capacity", TbktSelfTest::caseFarmHopperCapacity);
        CASES.put("bamboo_farm_no_duplication", TbktSelfTest::caseFarmNoDuplication);
        CASES.put("bamboo_farm_72000_ticks", TbktSelfTest::caseFarm72000Ticks);
        CASES.put("bamboo_farm_segmentation", TbktSelfTest::caseFarmSegmentation);
        CASES.put("bamboo_farm_repeatability", TbktSelfTest::caseFarmRepeatability);
        CASES.put("debug_file_edge_cases", TbktSelfTest::caseDebugFileEdgeCases);
        CASES.put("original_regression", TbktSelfTest::caseOriginalRegression);
    }

    public static boolean isAutoEnabled() {
        if (Boolean.getBoolean(SYSTEM_PROPERTY))
            return true;

        String env = System.getenv(ENV_VAR);
        return env != null && (env.equalsIgnoreCase("true") || env.equals("1"));
    }

    public static void armAutoRun(MinecraftServer server) {
        if (!isAutoEnabled() || autoArmed)
            return;

        autoArmed = true;
        autoCountdown.set(AUTO_SETTLE_TICKS);
        Constants.LOG.info("TBKT selftest armed. Running in {} ticks.", AUTO_SETTLE_TICKS);
    }

    public static void onServerTick(MinecraftServer server) {
        if (autoCountdown.get() < 0)
            return;

        if (autoCountdown.decrementAndGet() == 0) {
            boolean passed = run(server.createCommandSourceStack());
            // halt() alone can leave the Gradle run hanging. Force the JVM out.
            Runtime.getRuntime().halt(passed ? 0 : 1);
        }
    }

    /** @return true if every case passed */
    public static boolean run(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.overworld();
        List<String> failures = new ArrayList<>();
        int passed = 0;

        pinEnvironment(server, level, source);

        // Run each test case, count passed and failures.
        try {
            for (Map.Entry<String, TestCase> entry : CASES.entrySet()) {
                if (entry.getValue().run(server, level, source))
                    passed++;
                else
                    failures.add(entry.getKey());
            }
        } catch (Exception e) {
            Constants.LOG.error("TBKT selftest aborted with exception", e);
            failures.add("exception:" + e.getClass().getSimpleName());
        }

        boolean ok = failures.isEmpty();
        String summary = ok
                ? RESULT_MARKER + "=PASS cases=" + passed
                : RESULT_MARKER + "=FAIL passed=" + passed + " failed=" + String.join(",", failures);

        Constants.LOG.info(summary);
        return ok;
    }

    /** Sets up world and mod settings for deterministic testing. */
    private static void pinEnvironment(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        ModConfig.setLazyTaxPercent(0);
        ModConfig.setSimulateChunksWhenSleeping(false);
        ModConfig.setTimeMode(ModConfig.TimeMode.WORLD_TIME);
        ModConfig.setDebugLogging(false);
        ModConfig.setMinInhabitedTicks(0);
        ModConfig.ensureDefaultsPresent();
        for (String key : ModConfig.getEnabledByName().keySet()) {
            ModConfig.getEnabledByName().put(key, Boolean.TRUE);
        }

        // Freeze world clocks / spawning so only our /tbkt calls advance blocks.
        level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 3, server);
        level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
        level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
        level.getGameRules().set(GameRules.SPAWN_MONSTERS, false, server);
        level.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, -1, server);

        // API force-load first so /setblock cannot race an unloaded chunk.
        level.setChunkForced(CHUNK_X, CHUNK_Z, true);
        level.getChunk(CHUNK_X, CHUNK_Z);

        // /forceload takes block coordinates (chunk containing that block).
        runCmd(server, source, "forceload add " + ORIGIN_X + " " + ORIGIN_Z);
        runCmd(server, source, "time set day");
        runCmd(server, source, "weather clear 1000000");
        clearPlatform(server, source);
    }

    private static void clearPlatform(MinecraftServer server, CommandSourceStack source) {
        // Keep the whole fixture inside chunk (10, 10). A cross-chunk /fill would
        // fail whenever the neighbouring chunk is not loaded on a headless server.
        // High enough for a full 16-block bamboo stalk plus room for observers.
        clearVolume(server, source, 0, -1, 0, 10, 30, 10);
        runCmd(server, source,
                "fill " + ORIGIN_X + " " + ORIGIN_Y + " " + ORIGIN_Z + " "
                        + (ORIGIN_X + 10) + " " + ORIGIN_Y + " " + (ORIGIN_Z + 10)
                        + " minecraft:stone");

        ServerLevel level = server.overworld();
        AABB box = new AABB(ORIGIN_X, ORIGIN_Y - 1, ORIGIN_Z, ORIGIN_X + 11,
                ORIGIN_Y + 31, ORIGIN_Z + 11);
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box))
            item.discard();
    }

    private static void clearVolume(MinecraftServer server, CommandSourceStack source, int dx0,
            int dy0, int dz0, int dx1, int dy1, int dz1) {
        runCmd(server, source,
                "fill " + (ORIGIN_X + dx0) + " " + (ORIGIN_Y + dy0) + " " + (ORIGIN_Z + dz0) + " "
                        + (ORIGIN_X + dx1) + " " + (ORIGIN_Y + dy1) + " " + (ORIGIN_Z + dz1)
                        + " minecraft:air");
    }

    /** Test wheat growth from age 0 to mature. */
    private static boolean caseWheatGrowth(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(0, 0, 0);
        BlockPos crop = soil.above();
        BlockPos water = pos(1, 0, 0);

        runCmd(server, source, setblock(soil, "minecraft:farmland[moisture=7]"));
        runCmd(server, source, setblock(water, "minecraft:water"));
        runCmd(server, source, setblock(crop, "minecraft:wheat[age=0]"));
        if (!requireBlock(level, crop, Blocks.WHEAT, "wheat_growth"))
            return false;

        long ticks = ticksForRandomTicks(level, 40);
        BlockState before = level.getBlockState(crop);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        int expected = Math.min(wheat.getMaxAge(),
                wheat.getAge(before) + TickingCalculator.CropGrowthAmount(ticks, wheat, level,
                        before, crop));

        runCmd(server, source, tbktSimulateBlock(ticks, crop));
        int actual = wheat.getAge(level.getBlockState(crop));
        return check("wheat_growth", expected, actual, "age");
    }

    /** Test potential changes in an already mature crop. */
    private static boolean caseWheatAlreadyMature(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(3, 0, 0);
        BlockPos crop = soil.above();
        BlockPos water = pos(4, 0, 0);

        runCmd(server, source, setblock(soil, "minecraft:farmland[moisture=7]"));
        runCmd(server, source, setblock(water, "minecraft:water"));
        runCmd(server, source, setblock(crop, "minecraft:wheat[age=7]"));
        if (!requireBlock(level, crop, Blocks.WHEAT, "wheat_mature"))
            return false;

        long ticks = ticksForRandomTicks(level, 40);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        runCmd(server, source, tbktSimulateBlock(ticks, crop));
        int actual = wheat.getAge(level.getBlockState(crop));
        // Crop should remain at max age after the given number of ticks
        return check("wheat_mature", wheat.getMaxAge(), actual, "age");
    }

    /** Test nether wart growth from age 0 to mature. */
    private static boolean caseNetherWartGrowth(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(0, 0, 3);
        BlockPos wart = soil.above();

        runCmd(server, source, setblock(soil, "minecraft:soul_sand"));
        runCmd(server, source, setblock(wart, "minecraft:nether_wart[age=0]"));

        long ticks = ticksForRandomTicks(level, 30);
        int growth = TickingCalculator.RandomTickAmount(ticks, level, 10);
        int expected = Math.min(NetherWartBlock.MAX_AGE, growth);

        runCmd(server, source, tbktSimulateBlock(ticks, wart));
        int actual = level.getBlockState(wart).getValue(NetherWartBlock.AGE);
        return check("nether_wart", expected, actual, "age");
    }

    /** Test farmland hydration from moisture 0 to 7. */
    private static boolean caseFarmlandHydrate(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(5, 0, 0);
        BlockPos water = pos(6, 0, 0);

        runCmd(server, source, setblock(soil, "minecraft:farmland[moisture=0]"));
        runCmd(server, source, setblock(water, "minecraft:water"));

        long ticks = ticksForRandomTicks(level, 2);
        runCmd(server, source, tbktSimulateBlock(ticks, soil));
        int actual = level.getBlockState(soil).getValue(FarmlandBlock.MOISTURE);
        return check("farmland_hydrate", FarmlandBlock.MAX_MOISTURE, actual, "moisture");
    }

    /** Test farmland drying from moisture 3. */
    private static boolean caseFarmlandDry(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(5, 0, 3);
        // No water nearby, no crop above, start moist.
        runCmd(server, source, setblock(soil, "minecraft:farmland[moisture=3]"));
        runCmd(server, source, setblock(soil.above(), "minecraft:air"));

        long ticks = ticksForRandomTicks(level, 2);
        int randomTicks = TickingCalculator.RandomTickAmount(ticks, level);
        // randomTicks (2) <= moisture (3), so moisture drops but farmland remains
        int expectedMoisture = Math.max(0, 3 - randomTicks);

        runCmd(server, source, tbktSimulateBlock(ticks, soil));
        BlockState after = level.getBlockState(soil);
        if (!after.is(Blocks.FARMLAND)) {
            Constants.LOG.error("farmland_dry: expected farmland, got {}", after);
            return false;
        }
        int actual = after.getValue(FarmlandBlock.MOISTURE);
        return check("farmland_dry", expectedMoisture, actual, "moisture");
    }

    /** Test sweet berry growth from age 0 to mature. */
    private static boolean caseSweetBerryGrowth(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(0, 0, 6);
        BlockPos bush = soil.above();

        runCmd(server, source, setblock(soil, "minecraft:grass_block"));
        runCmd(server, source, setblock(bush, "minecraft:sweet_berry_bush[age=0]"));

        long ticks = ticksForRandomTicks(level, 10);
        int growth = TickingCalculator.RandomTickAmount(ticks, level, 5);
        int expected = Math.min(SweetBerryBushBlock.MAX_AGE, growth);

        runCmd(server, source, tbktSimulateBlock(ticks, bush));
        int actual = level.getBlockState(bush).getValue(SweetBerryBushBlock.AGE);
        return check("sweet_berry", expected, actual, "age");
    }

    private static boolean caseCocoaGrowth(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos log = pos(8, 1, 0);
        BlockPos cocoa = pos(8, 1, 1);

        runCmd(server, source, setblock(log, "minecraft:jungle_log"));
        runCmd(server, source, setblock(cocoa, "minecraft:cocoa[age=0,facing=north]"));

        long ticks = ticksForRandomTicks(level, 10);
        int growth = TickingCalculator.RandomTickAmount(ticks, level, 5);
        int expected = Math.min(CocoaBlock.MAX_AGE, growth);

        runCmd(server, source, tbktSimulateBlock(ticks, cocoa));
        BlockState after = level.getBlockState(cocoa);
        if (!after.is(Blocks.COCOA)) {
            Constants.LOG.error("cocoa: expected cocoa, got {}", after);
            return false;
        }
        int actual = after.getValue(CocoaBlock.AGE);
        return check("cocoa", expected, actual, "age");
    }

    private static boolean caseSugarCaneAge(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(8, 0, 3);
        BlockPos cane = soil.above();
        BlockPos water = pos(9, 0, 3);

        runCmd(server, source, setblock(soil, "minecraft:sand"));
        runCmd(server, source, setblock(water, "minecraft:water"));
        runCmd(server, source, setblock(cane, "minecraft:sugar_cane[age=0]"));

        // Stay below age 15 so we only age, never place a new cane (still
        // deterministic).
        long ticks = ticksForRandomTicks(level, 10);
        int expected = TickingCalculator.RandomTickAmount(ticks, level);

        runCmd(server, source, tbktSimulateBlock(ticks, cane));
        int actual = level.getBlockState(cane).getValue(SugarCaneBlock.AGE);
        return check("sugar_cane_age", expected, actual, "age");
    }

    /**
     * Cactus growth rolls flower RNG at age 8 (can abort a single simulate). Pulse
     * many large tick budgets and clear flowers so the tower still reaches max
     * height.
     */
    private static boolean caseCactusMaxHeight(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos sand = pos(0, 0, 0);
        BlockPos base = sand.above();

        runCmd(server, source, setblock(sand, "minecraft:sand"));
        runCmd(server, source, setblock(base, "minecraft:cactus[age=0]"));
        if (!requireBlock(level, base, Blocks.CACTUS, "cactus_max_height"))
            return false;

        long pulseTicks = ticksForRandomTicks(level, CACTUS_PULSE_RANDOM_TICKS);
        int pulses = growCactusColumn(server, level, source, base, pulseTicks, CACTUS_MAX_PULSES);
        int height = cactusHeight(level, base);
        Constants.LOG.info("cactus_max_height: pulses={} height={}", pulses, height);
        return check("cactus_max_height", CactusBlockAccessor.getMaxGrowingHeight(), height,
                "height");
    }

    /**
     * Same idea at colony scale..Many columns, huge cumulative simulate work, every
     * column must hit max height (flower blockers cleared between pulses).
     */
    private static boolean caseCactusColony(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos[] bases = new BlockPos[CACTUS_COLONY_SIZE];
        for (int i = 0; i < CACTUS_COLONY_SIZE; i++) {
            int dx = (i % 4) * 2;
            int dz = (i / 4) * 2;
            BlockPos sand = pos(dx, 0, dz);
            BlockPos base = sand.above();
            bases[i] = base;
            runCmd(server, source, setblock(sand, "minecraft:sand"));
            runCmd(server, source, setblock(base, "minecraft:cactus[age=0]"));
            if (!requireBlock(level, base, Blocks.CACTUS, "cactus_colony"))
                return false;
        }

        long pulseTicks = ticksForRandomTicks(level, CACTUS_PULSE_RANDOM_TICKS);
        for (int pulse = 0; pulse < CACTUS_MAX_PULSES; pulse++) {
            boolean allMax = true;
            for (BlockPos base : bases) {
                if (cactusHeight(level, base) < CactusBlockAccessor.getMaxGrowingHeight())
                    allMax = false;
                growCactusColumnOnce(server, level, source, base, pulseTicks);
            }
            if (allMax) {
                Constants.LOG.info("cactus_colony: all {} columns maxed after {} pulses",
                        CACTUS_COLONY_SIZE, pulse + 1);
                return check("cactus_colony", CACTUS_COLONY_SIZE, CACTUS_COLONY_SIZE, "columns");
            }
        }

        int grown = 0;
        for (BlockPos base : bases) {
            if (cactusHeight(level, base) >= CactusBlockAccessor.getMaxGrowingHeight())
                grown++;
        }
        return check("cactus_colony", CACTUS_COLONY_SIZE, grown, "columns_at_max");
    }

    private static int growCactusColumn(MinecraftServer server, ServerLevel level,
            CommandSourceStack source, BlockPos base, long pulseTicks, int maxPulses) {
        for (int pulse = 0; pulse < maxPulses; pulse++) {
            if (cactusHeight(level, base) >= CactusBlockAccessor.getMaxGrowingHeight())
                return pulse;
            growCactusColumnOnce(server, level, source, base, pulseTicks);
        }
        return maxPulses;
    }

    private static void growCactusColumnOnce(MinecraftServer server, ServerLevel level,
            CommandSourceStack source, BlockPos base, long pulseTicks) {
        BlockPos top = cactusTop(level, base);
        BlockPos above = top.above();
        // Flower RNG can cap a pulse and block further growth. Clear and keep going.
        if (level.getBlockState(above).is(Blocks.CACTUS_FLOWER))
            runCmdQuiet(server, source, setblock(above, "minecraft:air"));
        if (!level.getBlockState(top).is(Blocks.CACTUS))
            return;
        runCmdQuiet(server, source, tbktSimulateBlock(pulseTicks, top));
    }

    private static BlockPos cactusTop(ServerLevel level, BlockPos base) {
        BlockPos top = base;
        while (level.getBlockState(top.above()).is(Blocks.CACTUS))
            top = top.above();
        return top;
    }

    private static int cactusHeight(ServerLevel level, BlockPos base) {
        int height = 0;
        BlockPos pos = base;
        while (level.getBlockState(pos).is(Blocks.CACTUS)) {
            height++;
            pos = pos.above();
        }
        return height;
    }

    /** Sapling {@code advanceTree} is RNG. Pulse until the sapling is gone. */
    private static boolean caseSaplingBecomesTree(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        // Room for an oak canopy. Keep the sapling near chunk center.
        clearVolume(server, source, 0, 1, 0, 15, 20, 15);
        BlockPos dirt = pos(8, 0, 8);
        BlockPos sapling = dirt.above();
        runCmd(server, source, setblock(dirt, "minecraft:dirt"));
        runCmd(server, source, setblock(sapling, "minecraft:oak_sapling[stage=0]"));
        if (!requireBlock(level, sapling, Blocks.OAK_SAPLING, "sapling_tree"))
            return false;

        long pulseTicks = ticksForRandomTicks(level, RNG_PULSE_RANDOM_TICKS);
        for (int pulse = 0; pulse < RNG_MAX_PULSES; pulse++) {
            if (!level.getBlockState(sapling).is(Blocks.OAK_SAPLING)) {
                Constants.LOG.info("sapling_tree: grew away after {} pulses, now {}", pulse,
                        level.getBlockState(sapling));
                return check("sapling_tree", 1, 1, "grew");
            }
            runCmdQuiet(server, source, tbktSimulateBlock(pulseTicks, sapling));
        }
        Constants.LOG.error("sapling_tree: still a sapling after {} pulses", RNG_MAX_PULSES);
        return false;
    }

    /**
     * Fruit direction is shuffled, but with all four sides valid a fruit always
     * places once growth budget exceeds max age.
     */
    private static boolean caseStemGrowsFruit(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(4, 0, 4);
        BlockPos stem = soil.above();
        BlockPos water = pos(4, 0, 5);

        runCmd(server, source, setblock(soil, "minecraft:farmland[moisture=7]"));
        runCmd(server, source, setblock(water, "minecraft:water"));
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = soil.relative(dir);
            runCmd(server, source, setblock(side, "minecraft:farmland[moisture=7]"));
            runCmd(server, source, setblock(side.above(), "minecraft:air"));
        }
        runCmd(server, source, setblock(stem, "minecraft:pumpkin_stem[age=0]"));
        if (!requireBlock(level, stem, Blocks.PUMPKIN_STEM, "stem_fruit"))
            return false;

        long pulseTicks = ticksForRandomTicks(level, RNG_PULSE_RANDOM_TICKS);
        for (int pulse = 0; pulse < RNG_MAX_PULSES; pulse++) {
            if (hasAdjacentFruit(level, stem, Blocks.PUMPKIN)
                    || level.getBlockState(stem).is(Blocks.ATTACHED_PUMPKIN_STEM)) {
                Constants.LOG.info("stem_fruit: fruit after {} pulses", pulse);
                return check("stem_fruit", 1, 1, "fruit");
            }
            runCmdQuiet(server, source, tbktSimulateBlock(pulseTicks, stem));
        }
        return check("stem_fruit", 1, 0, "fruit");
    }

    /**
     * Bamboo {@code growBamboo} can RNG-stop from height 11. Soak to at least that.
     */
    private static boolean caseBambooMaxHeight(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        clearVolume(server, source, 0, 1, 0, 2, BambooStalkBlock.MAX_HEIGHT + 2, 2);
        BlockPos soil = pos(0, 0, 0);
        BlockPos base = soil.above();
        runCmd(server, source, setblock(soil, "minecraft:grass_block"));
        runCmd(server, source, setblock(base, "minecraft:bamboo_sapling"));
        BlockState bambooStart = level.getBlockState(base);
        if (!bambooStart.is(Blocks.BAMBOO_SAPLING) && !bambooStart.is(Blocks.BAMBOO)) {
            Constants.LOG.error("bamboo_height: expected bamboo/sapling at {}, got {}",
                    base.toShortString(), bambooStart);
            return false;
        }

        long pulseTicks = ticksForRandomTicks(level, RNG_PULSE_RANDOM_TICKS);
        for (int pulse = 0; pulse < RNG_MAX_PULSES; pulse++) {
            int height = bambooHeight(level, base);
            if (height >= MIN_BAMBOO_HEIGHT) {
                Constants.LOG.info("bamboo_height: height={} after {} pulses", height, pulse);
                return check("bamboo_height", 1, 1, "min_height");
            }
            BlockPos top = bambooTop(level, base);
            BlockState topState = level.getBlockState(top);
            // growBamboo can RNG-mark STAGE_DONE early. Clear like cactus flowers.
            if (topState.is(Blocks.BAMBOO)
                    && topState.getValue(BambooStalkBlock.STAGE) == BambooStalkBlock.STAGE_DONE_GROWING) {
                runCmdQuiet(server, source, setblock(top,
                        "minecraft:bamboo[age=" + topState.getValue(BambooStalkBlock.AGE)
                                + ",leaves="
                                + topState.getValue(BambooStalkBlock.LEAVES).getSerializedName()
                                + ",stage=0]"));
                topState = level.getBlockState(top);
            }
            if (topState.is(Blocks.BAMBOO) || topState.is(Blocks.BAMBOO_SAPLING))
                runCmdQuiet(server, source, tbktSimulateBlock(pulseTicks, top));
            else
                break;
        }
        return check("bamboo_height", MIN_BAMBOO_HEIGHT, bambooHeight(level, base), "height");
    }

    /** Persistent=false distance=7 leaves decay on randomTick. Pulse until air. */
    private static boolean caseLeavesDecay(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos leaf = pos(0, 1, 0);
        runCmd(server, source,
                setblock(leaf, "minecraft:oak_leaves[distance=7,persistent=false,waterlogged=false]"));
        if (!requireBlock(level, leaf, Blocks.OAK_LEAVES, "leaves_decay"))
            return false;
        if (!level.getBlockState(leaf).getValue(LeavesBlock.PERSISTENT)
                && level.getBlockState(leaf).getValue(LeavesBlock.DISTANCE) != 7) {
            Constants.LOG.error("leaves_decay: bad initial state {}", level.getBlockState(leaf));
            return false;
        }

        long pulseTicks = ticksForRandomTicks(level, RNG_PULSE_RANDOM_TICKS);
        for (int pulse = 0; pulse < RNG_MAX_PULSES; pulse++) {
            if (level.getBlockState(leaf).isAir()) {
                Constants.LOG.info("leaves_decay: decayed after {} pulses", pulse);
                return check("leaves_decay", 1, 1, "decayed");
            }
            runCmdQuiet(server, source, tbktSimulateBlock(pulseTicks, leaf));
        }
        return check("leaves_decay", 1, 0, "decayed");
    }

    /** Shuffle only picks faces. Enough pulses fully grow every face. */
    private static boolean caseAmethystFullyGrown(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        clearVolume(server, source, 3, 0, 3, 7, 4, 7);
        BlockPos budding = pos(5, 2, 5);
        // Solid pedestal so the budding block itself has a support, air on all grow
        // faces.
        runCmd(server, source, setblock(budding.below(), "minecraft:stone"));
        runCmd(server, source, setblock(budding, "minecraft:budding_amethyst"));
        for (Direction dir : Direction.values()) {
            runCmd(server, source, setblock(budding.relative(dir), "minecraft:air"));
        }
        // Re-place pedestal after the DOWN air wipe.
        runCmd(server, source, setblock(budding.below(), "minecraft:stone"));
        if (!requireBlock(level, budding, Blocks.BUDDING_AMETHYST, "amethyst_full"))
            return false;

        long pulseTicks = ticksForRandomTicks(level, RNG_PULSE_RANDOM_TICKS);
        for (int pulse = 0; pulse < RNG_MAX_PULSES; pulse++) {
            int fullFaces = countFullAmethystFaces(level, budding);
            // 5 faces: UP + 4 horizontal (DOWN is stone pedestal).
            if (fullFaces >= 5) {
                Constants.LOG.info("amethyst_full: {} faces after {} pulses", fullFaces, pulse);
                return check("amethyst_full", 5, fullFaces, "faces");
            }
            runCmdQuiet(server, source, tbktSimulateBlock(pulseTicks, budding));
        }
        return check("amethyst_full", 5, countFullAmethystFaces(level, budding), "faces");
    }

    /** Dripstone leftover water-transfer RNG. Soak until mud becomes clay. */
    private static boolean caseDripstoneMudToClay(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos tip = pos(0, 1, 0);
        BlockPos body = tip.above();
        BlockPos mud = tip.above(2);
        runCmd(server, source, setblock(mud, "minecraft:mud"));
        runCmd(server, source, setblock(body, "minecraft:dripstone_block"));
        runCmd(server, source,
                setblock(tip, "minecraft:pointed_dripstone[thickness=tip,vertical_direction=down]"));
        if (!requireBlock(level, tip, Blocks.POINTED_DRIPSTONE, "dripstone_clay"))
            return false;

        long pulseTicks = ticksForRandomTicks(level, RNG_PULSE_RANDOM_TICKS);
        for (int pulse = 0; pulse < RNG_MAX_PULSES; pulse++) {
            if (level.getBlockState(mud).is(Blocks.CLAY)) {
                Constants.LOG.info("dripstone_clay: converted after {} pulses", pulse);
                return check("dripstone_clay", 1, 1, "clay");
            }
            runCmdQuiet(server, source, tbktSimulateBlock(pulseTicks, tip));
        }
        return check("dripstone_clay", 1, level.getBlockState(mud).is(Blocks.CLAY) ? 1 : 0, "clay");
    }

    /** Same drip RNG path, fill a cauldron to max water level. */
    private static boolean caseDripstoneFillsCauldron(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos cauldron = pos(2, 0, 2);
        BlockPos tip = pos(2, 2, 2);
        BlockPos body = tip.above();
        BlockPos water = tip.above(2);
        runCmd(server, source, setblock(cauldron, "minecraft:cauldron"));
        runCmd(server, source, setblock(tip.below(), "minecraft:air"));
        runCmd(server, source,
                setblock(tip, "minecraft:pointed_dripstone[thickness=tip,vertical_direction=down]"));
        runCmd(server, source, setblock(body, "minecraft:dripstone_block"));
        runCmd(server, source, setblock(water, "minecraft:water"));
        if (!requireBlock(level, tip, Blocks.POINTED_DRIPSTONE, "dripstone_cauldron"))
            return false;

        long pulseTicks = ticksForRandomTicks(level, RNG_PULSE_RANDOM_TICKS);
        for (int pulse = 0; pulse < RNG_MAX_PULSES; pulse++) {
            BlockState pot = level.getBlockState(cauldron);
            if (pot.is(Blocks.WATER_CAULDRON)
                    && pot.getValue(LayeredCauldronBlock.LEVEL) >= LayeredCauldronBlock.MAX_FILL_LEVEL) {
                Constants.LOG.info("dripstone_cauldron: full after {} pulses", pulse);
                return check("dripstone_cauldron", LayeredCauldronBlock.MAX_FILL_LEVEL,
                        pot.getValue(LayeredCauldronBlock.LEVEL), "level");
            }
            runCmdQuiet(server, source, tbktSimulateBlock(pulseTicks, tip));
        }
        BlockState pot = level.getBlockState(cauldron);
        int levelValue = pot.is(Blocks.WATER_CAULDRON) ? pot.getValue(LayeredCauldronBlock.LEVEL) : 0;
        return check("dripstone_cauldron", LayeredCauldronBlock.MAX_FILL_LEVEL, levelValue, "level");
    }

    private static boolean hasAdjacentFruit(ServerLevel level, BlockPos stem,
            Block fruit) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(stem.relative(dir)).is(fruit))
                return true;
        }
        return false;
    }

    private static int countFullAmethystFaces(ServerLevel level, BlockPos budding) {
        int full = 0;
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN)
                continue;
            if (level.getBlockState(budding.relative(dir)).is(Blocks.AMETHYST_CLUSTER))
                full++;
        }
        return full;
    }

    private static BlockPos bambooTop(ServerLevel level, BlockPos base) {
        BlockPos top = base;
        while (level.getBlockState(top.above()).is(Blocks.BAMBOO)
                || level.getBlockState(top.above()).is(Blocks.BAMBOO_SAPLING))
            top = top.above();
        return top;
    }

    private static int bambooHeight(ServerLevel level, BlockPos base) {
        int height = 0;
        BlockPos pos = base;
        while (level.getBlockState(pos).is(Blocks.BAMBOO)
                || level.getBlockState(pos).is(Blocks.BAMBOO_SAPLING)) {
            height++;
            pos = pos.above();
        }
        return height;
    }

    private static boolean caseChunkSimulate(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soilA = pos(0, 0, 8);
        BlockPos cropA = soilA.above();
        BlockPos soilB = pos(2, 0, 8);
        BlockPos cropB = soilB.above();
        BlockPos water = pos(1, 0, 8);

        runCmd(server, source, setblock(soilA, "minecraft:farmland[moisture=7]"));
        runCmd(server, source, setblock(soilB, "minecraft:farmland[moisture=7]"));
        runCmd(server, source, setblock(water, "minecraft:water"));
        runCmd(server, source, setblock(cropA, "minecraft:carrots[age=1]"));
        runCmd(server, source, setblock(cropB, "minecraft:potatoes[age=1]"));

        long ticks = ticksForRandomTicks(level, 20);
        CropBlock carrots = (CropBlock) Blocks.CARROTS;
        CropBlock potatoes = (CropBlock) Blocks.POTATOES;
        BlockState beforeA = level.getBlockState(cropA);
        BlockState beforeB = level.getBlockState(cropB);
        int expectedA = Math.min(carrots.getMaxAge(),
                carrots.getAge(beforeA) + TickingCalculator.CropGrowthAmount(ticks, carrots, level,
                        beforeA, cropA));
        int expectedB = Math.min(potatoes.getMaxAge(),
                potatoes.getAge(beforeB) + TickingCalculator.CropGrowthAmount(ticks, potatoes, level,
                        beforeB, cropB));

        runCmd(server, source, "tbkt simulate chunk " + ticks + " " + CHUNK_X + " " + CHUNK_Z);

        int actualA = carrots.getAge(level.getBlockState(cropA));
        int actualB = potatoes.getAge(level.getBlockState(cropB));
        boolean okA = check("chunk_simulate.carrots", expectedA, actualA, "age");
        boolean okB = check("chunk_simulate.potatoes", expectedB, actualB, "age");
        return okA && okB;
    }

    // ------------------------------------------------------------------
    // Event-driven bamboo farm simulator tests (Plus features, tests 1-13).
    // ------------------------------------------------------------------

    private static LevelChunk selftestChunk(ServerLevel level) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(CHUNK_X, CHUNK_Z);
        if (chunk == null)
            throw new IllegalStateException("The selftest chunk is not loaded.");
        return chunk;
    }

    private static BambooFarmSimulator.Result simFarm(ServerLevel level, long ticks,
            boolean reset) {
        return BambooFarmSimulator.simulate(level, selftestChunk(level), ticks, reset, false);
    }

    private static BlockPos buildBambooFixture(MinecraftServer server,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(8, 0, 8);
        BlockPos base = soil.above();
        runCmd(server, source, setblock(soil, "minecraft:grass_block"));
        // A full bamboo block grows deterministically from age 0; a sapling
        // first has to convert and would add an extra random step.
        runCmd(server, source, setblock(base, "minecraft:bamboo"));
        return base;
    }

    private static boolean caseFarmBambooGrowth(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        BlockPos base = buildBambooFixture(server, source);
        BlockState start = level.getBlockState(base);
        if (!start.is(Blocks.BAMBOO_SAPLING) && !start.is(Blocks.BAMBOO)) {
            Constants.LOG.error("bamboo_farm_growth: unexpected start state {}", start);
            return false;
        }

        BambooFarmSimulator.Result result = simFarm(level, 36_000, true);
        int height = bambooHeight(level, base);
        boolean ok = result.executedTicks() == 36_000 && result.bambooRandomTicks() > 0
                && result.bambooGrowths() > 0 && height >= 3
                && result.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_growth: height={} growths={} randomTicks={} exceptions={} ok={}",
                height, result.bambooGrowths(), result.bambooRandomTicks(),
                result.exceptions().size(), ok);
        return ok;
    }

    private static boolean caseFarmObserverDetect(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos observer = pos(5, 1, 5);
        BlockPos watched = pos(4, 1, 5);
        runCmd(server, source, setblock(observer, "minecraft:observer[facing=west]"));
        runCmd(server, source, setblock(watched, "minecraft:air"));
        simFarm(level, 1, true);

        runCmd(server, source, setblock(watched, "minecraft:stone"));
        BambooFarmSimulator.Result result = simFarm(level, 3, false);
        boolean powered = isObserverPowered(level, observer);
        boolean ok = powered && result.observerPulses() >= 1 && result.observerOn() >= 1;
        Constants.LOG.info("bamboo_farm_observer_detect: powered={} pulses={} ok={}", powered,
                result.observerPulses(), ok);
        return ok;
    }

    private static boolean caseFarmObserverTiming(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos observer = pos(5, 1, 5);
        BlockPos watched = pos(4, 1, 5);
        runCmd(server, source, setblock(observer, "minecraft:observer[facing=west]"));
        runCmd(server, source, setblock(watched, "minecraft:air"));
        simFarm(level, 1, true);

        runCmd(server, source, setblock(watched, "minecraft:stone"));
        simFarm(level, 1, false);
        boolean notYetPowered = !isObserverPowered(level, observer);
        simFarm(level, 2, false);
        boolean poweredAfterDelay = isObserverPowered(level, observer);
        simFarm(level, 1, false);
        boolean stillPowered = isObserverPowered(level, observer);
        simFarm(level, 1, false);
        boolean offAfterPulse = !isObserverPowered(level, observer);

        runCmd(server, source, setblock(watched, "minecraft:air"));
        BambooFarmSimulator.Result second = simFarm(level, 3, false);
        boolean secondPulse = isObserverPowered(level, observer);

        boolean ok = notYetPowered && poweredAfterDelay && stillPowered && offAfterPulse
                && secondPulse && second.observerPulses() == 2
                && second.minObserverPulseGap() == 5;
        Constants.LOG.info(
                "bamboo_farm_observer_timing: notYet={} delayedOn={} duration2={} off={} second={} pulses={} gap={} ok={}",
                notYetPowered, poweredAfterDelay, stillPowered, offAfterPulse, secondPulse,
                second.observerPulses(), second.minObserverPulseGap(), ok);
        return ok;
    }

    private static boolean isObserverPowered(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.OBSERVER) && state.getValue(ObserverBlock.POWERED);
    }

    private static boolean caseFarmPiston(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos piston = pos(5, 1, 5);
        BlockPos head = pos(6, 1, 5);
        BlockPos power = pos(4, 1, 5);
        runCmd(server, source, setblock(head, "minecraft:air"));
        runCmd(server, source, setblock(piston, "minecraft:piston[facing=east]"));
        simFarm(level, 1, true);

        runCmd(server, source, setblock(power, "minecraft:redstone_block"));
        BambooFarmSimulator.Result extended = simFarm(level, 6, false);
        BlockState pistonState = level.getBlockState(piston);
        boolean isExtended = pistonState.is(Blocks.PISTON)
                && pistonState.getValue(PistonBaseBlock.EXTENDED)
                && level.getBlockState(head).is(Blocks.PISTON_HEAD);

        runCmd(server, source, setblock(power, "minecraft:air"));
        BambooFarmSimulator.Result retracted = simFarm(level, 10, false);
        pistonState = level.getBlockState(piston);
        boolean isRetracted = pistonState.is(Blocks.PISTON)
                && !pistonState.getValue(PistonBaseBlock.EXTENDED)
                && level.getBlockState(head).isAir();

        boolean ok = isExtended && isRetracted && extended.pistonExtensions() >= 1
                && retracted.pistonRetractions() >= 1 && retracted.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_piston: extended={} retracted={} extensions={} retractions={} ok={}",
                isExtended, isRetracted, extended.pistonExtensions(),
                retracted.pistonRetractions(), ok);
        return ok;
    }

    private static boolean caseFarmItemGeneration(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(6, 0, 5);
        BlockPos bamboo = pos(6, 1, 5);
        BlockPos piston = pos(5, 1, 5);
        BlockPos power = pos(4, 1, 5);
        runCmd(server, source, setblock(soil, "minecraft:grass_block"));
        runCmd(server, source, setblock(bamboo, "minecraft:bamboo"));
        runCmd(server, source, setblock(piston, "minecraft:piston[facing=east]"));
        simFarm(level, 1, true);

        runCmd(server, source, setblock(power, "minecraft:redstone_block"));
        BambooFarmSimulator.Result result = simFarm(level, 10, false);
        boolean ok = result.bambooBroken() >= 1 && result.itemsSpawned() >= 1
                && result.bambooItemsSpawned() >= 1 && result.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_item_generation: broken={} items={} bambooItems={} ok={}",
                result.bambooBroken(), result.itemsSpawned(), result.bambooItemsSpawned(), ok);
        return ok;
    }

    private static BlockPos placeHopperAndChest(MinecraftServer server,
            CommandSourceStack source) {
        BlockPos chest = pos(6, 1, 5);
        BlockPos hopper = pos(6, 2, 5);
        runCmd(server, source, setblock(chest, "minecraft:chest"));
        runCmd(server, source, setblock(hopper, "minecraft:hopper[facing=down]"));
        return hopper;
    }

    private static boolean caseFarmHopper(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos hopper = placeHopperAndChest(server, source);
        BlockPos chest = hopper.below();
        simFarm(level, 1, true);

        spawnItem(level, hopper.getX() + 0.5, hopper.getY() + 1.0, hopper.getZ() + 0.5,
                Items.BAMBOO, 4);
        BambooFarmSimulator.Result result = simFarm(level, 40, false);
        int chestCount = containerCount(level.getBlockEntity(chest), Items.BAMBOO);
        boolean ok = chestCount >= 4 && result.hopperTransferItems() >= 4
                && result.itemsDelivered() >= 4 && result.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_hopper: chestCount={} transfers={} delivered={} ok={}", chestCount,
                result.hopperTransferItems(), result.itemsDelivered(), ok);
        return ok;
    }

    private static boolean caseFarmHopperCapacity(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos hopper = placeHopperAndChest(server, source);
        BlockPos chest = hopper.below();
        fillContainer(level, chest, Items.BAMBOO, 64);
        simFarm(level, 1, true);

        spawnItem(level, hopper.getX() + 0.5, hopper.getY() + 1.0, hopper.getZ() + 0.5,
                Items.BAMBOO, 4);
        BambooFarmSimulator.Result result = simFarm(level, 40, false);
        int chestCount = containerCount(level.getBlockEntity(chest), Items.BAMBOO);
        int hopperCount = containerCount(level.getBlockEntity(hopper), Items.BAMBOO);
        boolean ok = chestCount == 27 * 64 && hopperCount == 4
                && result.itemsDelivered() == 0 && result.itemsOnGround() == 0
                && result.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_hopper_capacity: chest={} hopper={} delivered={} ground={} ok={}",
                chestCount, hopperCount, result.itemsDelivered(), result.itemsOnGround(), ok);
        return ok;
    }

    private static boolean caseFarmNoDuplication(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(6, 0, 5);
        BlockPos bamboo = pos(6, 1, 5);
        BlockPos piston = pos(5, 1, 5);
        BlockPos power = pos(4, 1, 5);
        runCmd(server, source, setblock(soil, "minecraft:grass_block"));
        runCmd(server, source, setblock(bamboo, "minecraft:bamboo"));
        runCmd(server, source, setblock(piston, "minecraft:piston[facing=east]"));
        simFarm(level, 1, true);

        runCmd(server, source, setblock(power, "minecraft:redstone_block"));
        BambooFarmSimulator.Result result = simFarm(level, 400, false);
        long accounted = result.itemsCollected() + result.itemsOnGround();
        boolean ok = result.itemsSpawned() > 0
                && result.bambooBroken() == result.bambooItemsSpawned()
                && accounted + result.trackedItemLosses() == result.itemsSpawned()
                && result.trackedItemLosses() == 0 && result.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_no_duplication: spawned={} collected={} ground={} broken={} bambooItems={} losses={} ok={}",
                result.itemsSpawned(), result.itemsCollected(), result.itemsOnGround(),
                result.bambooBroken(), result.bambooItemsSpawned(), result.trackedItemLosses(),
                ok);
        return ok;
    }

    private static boolean caseFarm72000Ticks(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        BlockPos base = buildBambooFixture(server, source);
        BambooFarmSimulator.Result result = simFarm(level,
                BambooFarmSimulator.TICKS_PER_HOUR, true);
        int height = bambooHeight(level, base);
        boolean ok = result.executedTicks() == BambooFarmSimulator.TICKS_PER_HOUR
                && result.bambooRandomTicks() > 0 && result.bambooGrowths() > 0 && height >= 3
                && result.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_72000_ticks: executed={} height={} growths={} ok={}",
                result.executedTicks(), height, result.bambooGrowths(), ok);
        return ok;
    }

    private static boolean caseFarmSegmentation(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        BlockPos fullBase = buildBambooFixture(server, source);
        BambooFarmSimulator.Result full = simFarm(level, 72_000, true);
        int fullHeight = bambooHeight(level, fullBase);

        BlockPos segmentedBase = buildBambooFixture(server, source);
        simFarm(level, 18_000, true);
        simFarm(level, 18_000, false);
        simFarm(level, 18_000, false);
        BambooFarmSimulator.Result segmented = simFarm(level, 18_000, false);
        int segmentedHeight = bambooHeight(level, segmentedBase);

        boolean ok = fullHeight == segmentedHeight
                && full.bambooGrowths() == segmented.bambooGrowths()
                && full.bambooRandomTicks() == segmented.bambooRandomTicks()
                && full.executedTicks() == 72_000 && segmented.executedTicks() == 18_000
                && full.exceptions().isEmpty() && segmented.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_segmentation: fullHeight={} segmentedHeight={} fullGrowths={} segmentedGrowths={} ok={}",
                fullHeight, segmentedHeight, full.bambooGrowths(), segmented.bambooGrowths(), ok);
        return ok;
    }

    private static boolean caseFarmRepeatability(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        BlockPos firstBase = buildBambooFixture(server, source);
        BambooFarmSimulator.Result first = simFarm(level, 36_000, true);
        int firstHeight = bambooHeight(level, firstBase);

        BlockPos secondBase = buildBambooFixture(server, source);
        BambooFarmSimulator.Result second = simFarm(level, 36_000, true);
        int secondHeight = bambooHeight(level, secondBase);

        boolean ok = firstHeight == secondHeight
                && first.bambooGrowths() == second.bambooGrowths()
                && first.bambooRandomTicks() == second.bambooRandomTicks()
                && first.scheduledTicksProcessed() == second.scheduledTicksProcessed()
                && first.blockEventsProcessed() == second.blockEventsProcessed()
                && first.exceptions().isEmpty() && second.exceptions().isEmpty();
        Constants.LOG.info(
                "bamboo_farm_repeatability: height={}/{} growths={}/{} randomTicks={}/{} ok={}",
                firstHeight, secondHeight, first.bambooGrowths(), second.bambooGrowths(),
                first.bambooRandomTicks(), second.bambooRandomTicks(), ok);
        return ok;
    }

    private static boolean caseDebugFileEdgeCases(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        List<String> validLines = validDebugLines();
        boolean ok = true;

        DebugFileConfig.Config valid = DebugFileConfig.parse(validLines);
        ok &= valid.enabled() && valid.ticks() == 72_000;

        List<String> bom = new ArrayList<>();
        bom.add("\uFEFF# comment after a UTF-8 BOM");
        bom.addAll(validLines);
        ok &= DebugFileConfig.parse(bom).ticks() == 72_000;

        List<String> upper = new ArrayList<>(validLines);
        upper.set(0, "ENABLED=TRUE");
        ok &= DebugFileConfig.parse(upper).enabled();

        List<String> maximum = new ArrayList<>(validLines);
        maximum.set(4, "simulate_hours=24.0");
        ok &= DebugFileConfig.parse(maximum).ticks() == BambooFarmSimulator.MAX_DEBUG_TICKS;

        List<String> spaced = new ArrayList<>();
        spaced.add("  # comment");
        spaced.add("");
        for (String line : validLines)
            spaced.add("  " + line + "  ");
        ok &= DebugFileConfig.parse(spaced).ticks() == 72_000;

        DebugFileConfig.Config defaults = DebugFileConfig
                .parse(DebugFileConfig.defaultFileContents().lines().toList());
        ok &= !defaults.enabled() && defaults.ticks() == 72_000;

        ok &= expectInvalid("duplicate key", append(validLines, "chunk_x=1"));
        ok &= expectInvalid("unknown key", append(validLines, "surprise=true"));
        ok &= expectInvalid("malformed line", append(validLines, "not-a-key-value"));
        ok &= expectInvalid("empty key", append(validLines, "=value"));
        ok &= expectInvalid("missing key",
                validLines.subList(0, validLines.size() - 1));
        ok &= expectInvalid("bad boolean",
                replaceLine(validLines, 5, "debug_logging=maybe"));
        ok &= expectInvalid("bad integer", replaceLine(validLines, 2, "chunk_x=abc"));
        ok &= expectInvalid("chunk out of range",
                replaceLine(validLines, 2, "chunk_x=30000001"));
        ok &= expectInvalid("negative hours",
                replaceLine(validLines, 4, "simulate_hours=-1"));
        ok &= expectInvalid("zero hours", replaceLine(validLines, 4, "simulate_hours=0"));
        ok &= expectInvalid("NaN hours", replaceLine(validLines, 4, "simulate_hours=NaN"));
        ok &= expectInvalid("infinite hours",
                replaceLine(validLines, 4, "simulate_hours=Infinity"));
        ok &= expectInvalid("over 24 hours",
                replaceLine(validLines, 4, "simulate_hours=24.1"));
        ok &= expectInvalid("bad dimension",
                replaceLine(validLines, 1, "dimension=overworld"));
        ok &= expectInvalid("overlong line", append(validLines, "#" + "x".repeat(2000)));
        ok &= expectInvalid("empty file", List.of());

        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.addAll(validLines);
        ok &= expectInvalid("null line", withNull);

        try {
            java.nio.file.Path missing = java.nio.file.Files.createTempDirectory("tbkt-selftest-")
                    .resolve("missing.txt");
            ok &= expectInvalidLoad(missing);
        } catch (java.io.IOException e) {
            Constants.LOG.error("debug_file_edge_cases: temporary directory failed", e);
            ok = false;
        }

        LevelChunk chunk = selftestChunk(level);
        DebugSimulation.Outcome zero = DebugSimulation.runExact(level, chunk, 0L, false, false);
        DebugSimulation.Outcome huge = DebugSimulation.runExact(level, chunk,
                BambooFarmSimulator.MAX_DEBUG_TICKS + 1L, false, false);
        ok &= !zero.success() && !huge.success();

        Constants.LOG.info("debug_file_edge_cases: ok={}", ok);
        return ok;
    }

    private static List<String> validDebugLines() {
        return List.of("enabled=true", "dimension=minecraft:overworld", "chunk_x=0",
                "chunk_z=0", "simulate_hours=1.0", "debug_logging=false",
                "generate_report=true");
    }

    private static List<String> append(List<String> base, String extra) {
        List<String> copy = new ArrayList<>(base);
        copy.add(extra);
        return copy;
    }

    private static List<String> replaceLine(List<String> base, int index, String value) {
        List<String> copy = new ArrayList<>(base);
        copy.set(index, value);
        return copy;
    }

    private static boolean expectInvalid(String label, List<String> lines) {
        try {
            DebugFileConfig.parse(lines);
            Constants.LOG.error("debug_file_edge_cases: {} was accepted but should be rejected",
                    label);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean expectInvalidLoad(java.nio.file.Path path) {
        try {
            DebugFileConfig.load(path);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static boolean caseOriginalRegression(MinecraftServer server, ServerLevel level,
            CommandSourceStack source) {
        clearPlatform(server, source);
        BlockPos soil = pos(0, 0, 0);
        BlockPos crop = soil.above();
        BlockPos water = pos(1, 0, 0);
        runCmd(server, source, setblock(soil, "minecraft:farmland[moisture=7]"));
        runCmd(server, source, setblock(water, "minecraft:water"));
        runCmd(server, source, setblock(crop, "minecraft:wheat[age=0]"));
        if (!requireBlock(level, crop, Blocks.WHEAT, "original_regression"))
            return false;

        long ticks = ticksForRandomTicks(level, 40);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        BlockState before = level.getBlockState(crop);
        int expected = Math.min(wheat.getMaxAge(),
                wheat.getAge(before) + TickingCalculator.CropGrowthAmount(ticks, wheat, level,
                        before, crop));

        WorldSimulator.simulateNonBambooExact(selftestChunk(level), ticks);
        int actual = wheat.getAge(level.getBlockState(crop));
        return check("original_regression", expected, actual, "age");
    }

    private static ItemEntity spawnItem(ServerLevel level, double x, double y, double z,
            Item item, int count) {
        ItemEntity entity = new ItemEntity(level, x, y, z, new ItemStack(item, count));
        level.addFreshEntity(entity);
        return entity;
    }

    private static int containerCount(BlockEntity blockEntity, Item item) {
        if (!(blockEntity instanceof Container container))
            return 0;
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item))
                total += stack.getCount();
        }
        return total;
    }

    private static void fillContainer(ServerLevel level, BlockPos pos, Item item, int count) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof Container container))
            return;
        for (int slot = 0; slot < container.getContainerSize(); slot++)
            container.setItem(slot, new ItemStack(item, count));
        blockEntity.setChanged();
    }

    private static long ticksForRandomTicks(ServerLevel level, int desiredRandomTicks) {
        int ratio = TickingCalculator.RandomTickRatio(level);
        return (long) desiredRandomTicks * ratio;
    }

    private static BlockPos pos(int dx, int dy, int dz) {
        return new BlockPos(ORIGIN_X + dx, ORIGIN_Y + dy, ORIGIN_Z + dz);
    }

    private static String setblock(BlockPos pos, String block) {
        return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
    }

    private static String tbktSimulateBlock(long ticks, BlockPos pos) {
        return "tbkt simulate block " + ticks + " " + pos.getX() + " " + pos.getY() + " "
                + pos.getZ();
    }

    private static void runCmd(MinecraftServer server, CommandSourceStack source, String command) {
        // Keep feedback so setup failures show up in selftest logs.
        server.getCommands().performPrefixedCommand(source, command);
    }

    private static void runCmdQuiet(MinecraftServer server, CommandSourceStack source, String command) {
        // Suppress output to avoid cluttering the console with irrelevant commands.
        server.getCommands().performPrefixedCommand(source.withSuppressedOutput(), command);
    }

    private static boolean requireBlock(ServerLevel level, BlockPos pos, Block block,
            String caseName) {
        BlockState state = level.getBlockState(pos);
        if (state.is(block))
            return true;

        Constants.LOG.error("{}: expected {} at {}, got {}", caseName, block, pos.toShortString(), state);
        return false;
    }

    private static boolean check(String name, int expected, int actual, String field) {
        if (expected == actual) {
            Constants.LOG.info("TBKT_SELFTEST_CASE={} PASS {}={}", name, field, actual);
            return true;
        }
        Constants.LOG.error("TBKT_SELFTEST_CASE={} FAIL expected {}={} actual={}", name, field,
                expected, actual);
        return false;
    }
}
