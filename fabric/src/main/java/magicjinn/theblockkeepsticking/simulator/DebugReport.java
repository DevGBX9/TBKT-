package magicjinn.theblockkeepsticking.simulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import magicjinn.theblockkeepsticking.Constants;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/** Writes one Arabic, human-readable debug report. No per-tick lines. */
public final class DebugReport {
    public static final String DIRECTORY_NAME = "tbkt-debug-reports";
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final AtomicLong COUNTER = new AtomicLong();

    private DebugReport() {
    }

    public static String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    public static String modVersion() {
        return FabricLoader.getInstance().getModContainer(Constants.FABRIC_MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("غير معروف");
    }

    public static Path write(Path configDirectory, String dimension, int chunkX, int chunkZ,
            long requestedTicks, double requestedHours, BambooFarmSimulator.Result result) {
        Path directory = configDirectory.resolve(DIRECTORY_NAME);
        String stamp = FILE_TIME.format(LocalDateTime.now());
        Path path = directory.resolve("tbkt-debug-" + stamp + "-" + COUNTER.incrementAndGet()
                + ".txt");

        double executedMinutes = result.executedTicks() / 1_200.0;
        double executedHours = result.executedTicks()
                / (double) BambooFarmSimulator.TICKS_PER_HOUR;

        StringBuilder out = new StringBuilder(4096);
        out.append("# تقرير محاكاة The Block Keeps Ticking Plus\n\n");
        out.append("- تاريخ ووقت التقرير: ").append(DISPLAY_TIME.format(LocalDateTime.now()))
                .append('\n');
        out.append("- إصدار ماينكرافت: ").append(minecraftVersion()).append('\n');
        out.append("- إصدار المود: ").append(modVersion()).append('\n');
        out.append("- البعد: ").append(dimension).append('\n');
        out.append("- إحداثيات الجزء (Chunk): [").append(chunkX).append(", ").append(chunkZ)
                .append("]\n");
        out.append("- الساعات المطلوبة: ").append(requestedHours).append('\n');
        out.append("- التكات المطلوبة: ").append(requestedTicks).append('\n');
        out.append("- التكات المنفذة: ").append(result.executedTicks()).append('\n');
        out.append("- الدقائق المكافئة: ")
                .append(String.format(Locale.ROOT, "%.3f", executedMinutes)).append('\n');
        out.append("- الساعات المكافئة: ")
                .append(String.format(Locale.ROOT, "%.3f", executedHours)).append('\n');
        out.append("- زمن التنفيذ الفعلي (مللي ثانية): ")
                .append(String.format(Locale.ROOT, "%.3f", result.wallNanos() / 1_000_000.0))
                .append('\n');
        out.append("- التكات المجدولة المعالجة: ").append(result.scheduledTicksProcessed())
                .append('\n');
        out.append("- أحداث البلوك المعالجة: ").append(result.blockEventsProcessed()).append('\n');
        out.append("- نموات البامبو: ").append(result.bambooGrowths()).append('\n');
        out.append("- كتل البامبو المكسورة: ").append(result.bambooBroken()).append('\n');
        out.append("- تفعيلات المراقب: ").append(result.observerPulses()).append('\n');
        out.append("- تكات المراقب المجدولة: ").append(result.observerTicks()).append('\n');
        out.append("- تشغيل المراقب: ").append(result.observerOn()).append('\n');
        out.append("- إطفاء المراقب: ").append(result.observerOff()).append('\n');
        out.append("- أقل فاصل بين تفعيلات المراقب: ")
                .append(result.minObserverPulseGap()).append(" تكة\n");
        out.append("- تمددات المكبس: ").append(result.pistonExtensions()).append('\n');
        out.append("- انكماشات المكبس: ").append(result.pistonRetractions()).append('\n');
        out.append("- العناصر المتولدة: ").append(result.itemsSpawned()).append('\n');
        out.append("- عناصر البامبو المتولدة: ").append(result.bambooItemsSpawned()).append('\n');
        out.append("- عناصر جمعها القمع: ").append(result.itemsCollected()).append('\n');
        out.append("- عناصر أوصلها القمع للحاويات: ").append(result.itemsDelivered())
                .append('\n');
        out.append("- عناصر نقلها القمع إجمالاً: ").append(result.hopperTransferItems())
                .append('\n');
        out.append("- عمليات نقل القمع: ").append(result.hopperTransferOperations()).append('\n');
        out.append("- العناصر المتبقية على الأرض: ").append(result.itemsOnGround()).append('\n');
        out.append("- العناصر المتبقية داخل الحاويات: ").append(result.itemsInContainers())
                .append('\n');
        out.append("- العناصر المضافة إلى الحاويات في هذه المحاكاة: ")
                .append(result.itemsAddedToContainers()).append('\n');
        out.append("- إجمالي العناصر المتبقية: ").append(result.itemsRemaining()).append('\n');
        out.append("- العناصر المفقودة/المدمرة: ").append(result.trackedItemLosses()).append('\n');
        out.append("- ملاحظة: لا يتم تسجيل أي سطر لكل تكة.\n");

        out.append("\n## العمليات المتخطاة والأسباب\n");
        if (result.skipped().isEmpty()) {
            out.append("- لا توجد عمليات متخطاة.\n");
        } else {
            for (Map.Entry<String, Long> entry : result.skipped().entrySet()) {
                out.append("- ").append(entry.getKey()).append(": ").append(entry.getValue())
                        .append('\n');
            }
        }

        out.append("\n## العدادات التفصيلية\n");
        for (Map.Entry<String, Long> entry : result.counters().entrySet()) {
            out.append("- ").append(entry.getKey()).append(": ").append(entry.getValue())
                    .append('\n');
        }

        out.append("\n## الاستثناءات والتحذيرات\n");
        if (result.exceptions().isEmpty()) {
            out.append("- لا توجد استثناءات.\n");
        } else {
            for (String exception : result.exceptions()) {
                out.append("- ").append(exception).append('\n');
            }
        }

        try {
            Files.createDirectories(directory);
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
            return path;
        } catch (IOException e) {
            Constants.LOG.error("Failed to write TBKT debug report to {}", path, e);
            return null;
        }
    }
}
