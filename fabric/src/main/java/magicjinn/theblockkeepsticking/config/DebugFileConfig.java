package magicjinn.theblockkeepsticking.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import magicjinn.theblockkeepsticking.Constants;
import magicjinn.theblockkeepsticking.platform.Services;
import magicjinn.theblockkeepsticking.simulator.BambooFarmSimulator;

/**
 * Strict parser for {@code config/the-block-keeps-ticking-debug.txt}.
 *
 * <p>
 * The file is data only. It is never read at startup and never starts a
 * simulation by itself; only {@code /tbkt run-file} may act on it.
 */
public final class DebugFileConfig {
    public static final String FILE_NAME = "the-block-keeps-ticking-debug.txt";
    public static final double MAX_HOURS = (double) BambooFarmSimulator.MAX_DEBUG_TICKS
            / BambooFarmSimulator.TICKS_PER_HOUR;
    public static final long MAX_FILE_BYTES = 64L * 1024L;
    public static final int MAX_LINES = 200;
    public static final int MAX_LINE_LENGTH = 1024;

    private static final Set<String> KNOWN_KEYS = Set.of("enabled", "dimension", "chunk_x",
            "chunk_z", "simulate_hours", "debug_logging", "generate_report");
    private static final Pattern DIMENSION_PATTERN = Pattern
            .compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MIN_CHUNK = -30_000_000;
    private static final int MAX_CHUNK = 30_000_000;

    private DebugFileConfig() {
    }

    public record Config(boolean enabled, String dimension, int chunkX, int chunkZ,
            double simulateHours, boolean debugLogging, boolean generateReport) {
        public long ticks() {
            return Math.round(simulateHours * BambooFarmSimulator.TICKS_PER_HOUR);
        }
    }

    public static Path defaultPath() {
        return Services.PLATFORM.getConfigDirectory().resolve(FILE_NAME);
    }

    /** Creates the documented template once; an existing user file is untouched. */
    public static void ensureDefaultFile() {
        Path path = defaultPath();
        if (Files.exists(path))
            return;
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, defaultFileContents(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            Constants.LOG.error("Failed to create TBKT debug file at {}", path, e);
        }
    }

    public static String defaultFileContents() {
        return """
                # The Block Keeps Ticking Plus - ملف تصحيح اختياري
                # لا يعمل تلقائياً أبداً. استخدم /tbkt run-file لتشغيله.
                enabled=false
                dimension=minecraft:overworld
                chunk_x=0
                chunk_z=0
                simulate_hours=1.0
                debug_logging=false
                generate_report=true
                """;
    }

    public static Config load(Path path) {
        try {
            if (!Files.exists(path))
                throw new IllegalArgumentException("Debug file does not exist: " + path);
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES)
                throw new IllegalArgumentException("Debug file is too large (maximum 64 KiB).");
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            return parse(lines);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not read debug file: " + e.getMessage(), e);
        }
    }

    public static Config parse(List<String> lines) {
        if (lines.size() > MAX_LINES)
            throw new IllegalArgumentException("Debug file has too many lines (maximum "
                    + MAX_LINES + ").");

        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null)
                throw new IllegalArgumentException("Line " + (i + 1) + " is null.");
            // Files saved by Windows editors often start with a UTF-8 BOM. Strip
            // it before tokenising so the first key is not rejected as unknown.
            if (i == 0 && !raw.isEmpty() && raw.charAt(0) == '\uFEFF')
                raw = raw.substring(1);
            if (raw.length() > MAX_LINE_LENGTH)
                throw new IllegalArgumentException("Line " + (i + 1)
                        + " is longer than " + MAX_LINE_LENGTH + " characters.");
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#"))
                continue;

            int equals = line.indexOf('=');
            if (equals <= 0)
                throw new IllegalArgumentException("Line " + (i + 1)
                        + " must use key=value syntax.");
            String key = line.substring(0, equals).strip().toLowerCase(Locale.ROOT);
            String value = line.substring(equals + 1).strip();
            if (!KNOWN_KEYS.contains(key))
                throw new IllegalArgumentException("Unknown key on line " + (i + 1) + ": " + key);
            if (values.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Duplicate key: " + key);
        }

        for (String key : KNOWN_KEYS) {
            if (!values.containsKey(key))
                throw new IllegalArgumentException("Missing required key: " + key);
        }

        boolean enabled = parseBoolean("enabled", values.get("enabled"));
        String dimension = values.get("dimension").strip();
        if (!DIMENSION_PATTERN.matcher(dimension).matches())
            throw new IllegalArgumentException("dimension must look like minecraft:overworld.");
        int chunkX = parseChunk("chunk_x", values.get("chunk_x"));
        int chunkZ = parseChunk("chunk_z", values.get("chunk_z"));
        double hours = parseHours(values.get("simulate_hours"));
        boolean debugLogging = parseBoolean("debug_logging", values.get("debug_logging"));
        boolean generateReport = parseBoolean("generate_report", values.get("generate_report"));

        Config config = new Config(enabled, dimension, chunkX, chunkZ, hours, debugLogging,
                generateReport);
        long ticks = config.ticks();
        if (ticks < 1 || ticks > BambooFarmSimulator.MAX_DEBUG_TICKS) {
            throw new IllegalArgumentException("simulate_hours produces " + ticks
                    + " ticks; allowed range is 1.." + BambooFarmSimulator.MAX_DEBUG_TICKS + ".");
        }
        return config;
    }

    private static boolean parseBoolean(String key, String raw) {
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(
                    key + " must be true or false, got: " + raw);
        };
    }

    private static int parseChunk(String key, String raw) {
        final int value;
        try {
            value = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got: " + raw);
        }
        if (value < MIN_CHUNK || value > MAX_CHUNK) {
            throw new IllegalArgumentException(
                    key + " is outside the allowed range " + MIN_CHUNK + ".." + MAX_CHUNK + ".");
        }
        return value;
    }

    private static double parseHours(String raw) {
        final double value;
        try {
            value = Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("simulate_hours must be a number, got: " + raw);
        }
        if (!Double.isFinite(value) || value <= 0.0 || value > MAX_HOURS) {
            throw new IllegalArgumentException(
                    "simulate_hours must be greater than 0 and at most " + MAX_HOURS + ".");
        }
        return value;
    }
}
