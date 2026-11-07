package magicjinn.theblockkeepsticking.simulator;

import java.nio.file.Files;
import java.nio.file.Path;

import magicjinn.theblockkeepsticking.Constants;
import magicjinn.theblockkeepsticking.config.DebugFileConfig;
import magicjinn.theblockkeepsticking.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The only entry point for debug simulations. Both the debug file and the
 * command use it; nothing here runs during normal server startup.
 */
public final class DebugSimulation {
    private static volatile LastRun lastRun;

    private DebugSimulation() {
    }

    public record LastRun(String dimension, int chunkX, int chunkZ, long ticks, double hours,
            BambooFarmSimulator.Result result, Path report) {
    }

    public record Outcome(boolean success, boolean skipped, String message, Path report,
            LastRun run) {
        static Outcome failure(String message) {
            return new Outcome(false, false, message, null, null);
        }

        static Outcome skipped(String message) {
            return new Outcome(true, true, message, null, null);
        }
    }

    public static Outcome runFromFile(MinecraftServer server) {
        DebugFileConfig.Config config;
        try {
            config = DebugFileConfig.load(DebugFileConfig.defaultPath());
        } catch (IllegalArgumentException e) {
            return Outcome.failure("Debug file error: " + e.getMessage());
        }

        if (!config.enabled()) {
            return Outcome.skipped("Debug file is present but enabled=false; nothing was run.");
        }

        ServerLevel level = findLevel(server, config.dimension());
        if (level == null) {
            return Outcome.failure("Unknown or unloaded dimension: " + config.dimension());
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(config.chunkX(), config.chunkZ());
        if (chunk == null) {
            return Outcome.failure("Chunk [" + config.chunkX() + ", " + config.chunkZ()
                    + "] in " + config.dimension() + " is not loaded.");
        }

        return runExact(level, chunk, config.ticks(), config, true, config.generateReport());
    }

    public static Outcome runExact(ServerLevel level, LevelChunk chunk, long ticks,
            boolean reset, boolean generateReport) {
        DebugFileConfig.Config config = new DebugFileConfig.Config(true,
                level.dimension().location().toString(), chunk.getPos().x(), chunk.getPos().z(),
                (double) ticks / BambooFarmSimulator.TICKS_PER_HOUR, false, generateReport);
        return runExact(level, chunk, ticks, config, reset, generateReport);
    }

    private static Outcome runExact(ServerLevel level, LevelChunk chunk, long ticks,
            DebugFileConfig.Config config, boolean reset, boolean generateReport) {
        if (ticks < 1) {
            return Outcome.failure("Ticks must be at least 1.");
        }
        if (ticks > BambooFarmSimulator.MAX_DEBUG_TICKS) {
            return Outcome.failure("Ticks exceed the maximum of "
                    + BambooFarmSimulator.MAX_DEBUG_TICKS + ".");
        }

        try {
            if (reset) {
                BambooFarmSimulator.reset(level, chunk.getPos());
            }

            BambooFarmSimulator.Result result = BambooFarmSimulator.simulate(level, chunk, ticks,
                    false, config.debugLogging());
            WorldSimulator.simulateNonBambooExact(chunk, ticks);

            Path report = null;
            if (generateReport) {
                report = DebugReport.write(Services.PLATFORM.getConfigDirectory(),
                        config.dimension(), chunk.getPos().x(), chunk.getPos().z(), ticks,
                        config.simulateHours(), result);
            }

            LastRun run = new LastRun(config.dimension(), chunk.getPos().x(), chunk.getPos().z(),
                    ticks, config.simulateHours(), result, report);
            lastRun = run;

            String message = "Simulated " + result.executedTicks() + " ticks in chunk ["
                    + chunk.getPos().x() + ", " + chunk.getPos().z() + "] (" + config.dimension()
                    + "), bambooGrowths=" + result.bambooGrowths() + ", bambooBroken="
                    + result.bambooBroken() + ", observerPulses=" + result.observerPulses()
                    + ", pistonExtensions=" + result.pistonExtensions() + ", itemsSpawned="
                    + result.itemsSpawned() + ", itemsRemaining=" + result.itemsRemaining()
                    + ", skipped=" + result.skipped().size() + ", exceptions="
                    + result.exceptions().size() + ".";
            if (report != null) {
                message += " Report: " + report;
            }
            return new Outcome(true, false, message, report, run);
        } catch (Throwable throwable) {
            Constants.LOG.error("TBKT debug simulation failed", throwable);
            return Outcome.failure("Simulation failed: " + throwable.getClass().getSimpleName()
                    + ": " + throwable.getMessage());
        }
    }

    /** Re-writes the last result to a fresh report file without simulating again. */
    public static Path reportLast() {
        LastRun run = lastRun;
        if (run == null)
            return null;
        Path report = DebugReport.write(Services.PLATFORM.getConfigDirectory(), run.dimension(),
                run.chunkX(), run.chunkZ(), run.ticks(), run.hours(), run.result());
        if (report != null) {
            lastRun = new LastRun(run.dimension(), run.chunkX(), run.chunkZ(), run.ticks(),
                    run.hours(), run.result(), report);
        }
        return report;
    }

    public static String status(MinecraftServer server) {
        StringBuilder out = new StringBuilder();
        Path debugFile = DebugFileConfig.defaultPath();
        out.append("Minecraft: ").append(DebugReport.minecraftVersion()).append('\n');
        out.append("Mod: ").append(DebugReport.modVersion()).append('\n');
        out.append("Debug file: ").append(debugFile).append(" (exists=")
                .append(Files.exists(debugFile)).append(")\n");
        out.append("Reports: ")
                .append(Services.PLATFORM.getConfigDirectory().resolve(DebugReport.DIRECTORY_NAME))
                .append('\n');
        LastRun run = lastRun;
        if (run == null) {
            out.append("Last run: none\n");
        } else {
            out.append("Last run: ").append(run.dimension()).append(" chunk [")
                    .append(run.chunkX()).append(", ").append(run.chunkZ()).append("], ticks=")
                    .append(run.ticks()).append(", growths=").append(run.result().bambooGrowths())
                    .append(", broken=").append(run.result().bambooBroken())
                    .append(", pulses=").append(run.result().observerPulses())
                    .append(", itemsRemaining=").append(run.result().itemsRemaining())
                    .append(", skipped=").append(run.result().skipped().size())
                    .append(", exceptions=").append(run.result().exceptions().size())
                    .append('\n');
        }

        ServerLevel overworld = server.overworld();
        ChunkPos spawn = ChunkPos.containing(overworld.getRespawnData().pos());
        out.append("Overworld spawn chunk session: ")
                .append(BambooFarmSimulator.status(overworld, spawn));
        return out.toString();
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension))
                return level;
        }
        return null;
    }
}
