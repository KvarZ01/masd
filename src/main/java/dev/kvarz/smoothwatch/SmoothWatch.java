package dev.kvarz.smoothwatch;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * 1) При старте проверяет JVM: сборщик мусора, размер кучи, опасные флаги.
 * 2) Во время игры ловит "фризы" (тик клиента дольше порога) и пишет в лог,
 *    была ли в это время сборка мусора. Ничего не меняет в самой игре -
 *    поэтому не может её сломать.
 */
public class SmoothWatch implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("SmoothWatch");

    /** Тик в норме = 50 мс. Всё что дольше 120 мс считаем фризом. */
    private static final long FREEZE_NS = 120_000_000L;
    /** Не чаще одной записи в лог раз в 2 секунды. */
    private static final long LOG_COOLDOWN_NS = 2_000_000_000L;

    private final List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
    private long lastTick = 0;
    private long lastGcCount = 0;
    private long lastGcTimeMs = 0;
    private long lastLog = 0;

    @Override
    public void onInitializeClient() {
        reportJvm();
        snapshotGc();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.nanoTime();
            long gcCount = totalGcCount();
            long gcTime = totalGcTimeMs();

            // Пока мир не загружен (загрузка/меню) - только обновляем счётчики
            if (client.level != null && lastTick != 0) {
                long delta = now - lastTick;
                if (delta > FREEZE_NS && now - lastLog > LOG_COOLDOWN_NS) {
                    long dCount = gcCount - lastGcCount;
                    long dTime = gcTime - lastGcTimeMs;
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
                    long maxMb = rt.maxMemory() >> 20;
                    String cause = dCount > 0
                            ? "GC (" + dCount + " сборок, ~" + dTime + " мс)"
                            : "НЕ GC (смотри чанки/мобов/моды, а не память)";
                    LOG.warn("Фриз {} мс | причина: {} | куча {}/{} МБ",
                            delta / 1_000_000, cause, usedMb, maxMb);
                    lastLog = now;
                }
            }
            lastTick = now;
            lastGcCount = gcCount;
            lastGcTimeMs = gcTime;
        });
    }

    private void reportJvm() {
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        long maxMb = Runtime.getRuntime().maxMemory() >> 20;
        StringBuilder gcNames = new StringBuilder();
        for (GarbageCollectorMXBean g : gcs) gcNames.append(g.getName()).append("; ");

        LOG.info("JVM: Java {}, макс. куча {} МБ, сборщики: {}",
                System.getProperty("java.version"), maxMb, gcNames);

        if (maxMb > 10_000) {
            LOG.warn("Куча {} МБ - слишком много для ванили/Sodium: паузы GC станут длиннее. Хватит 4-6 ГБ.", maxMb);
        }
        if (maxMb < 2_500) {
            LOG.warn("Куча {} МБ - маловато, GC будет работать постоянно (микро-фризы). Поставь 4 ГБ.", maxMb);
        }
        if (args.contains("-XX:+DisableExplicitGC") == false) {
            LOG.info("Совет: добавь -XX:+DisableExplicitGC, чтобы моды не вызывали System.gc() (это фриз на сотни мс).");
        }
        boolean xms = args.stream().anyMatch(a -> a.startsWith("-Xms"));
        if (!xms) {
            LOG.info("Совет: задай -Xms равным -Xmx, чтобы куча не растягивалась во время игры.");
        }
    }

    private void snapshotGc() {
        lastGcCount = totalGcCount();
        lastGcTimeMs = totalGcTimeMs();
    }

    private long totalGcCount() {
        long c = 0;
        for (GarbageCollectorMXBean g : gcs) c += Math.max(0, g.getCollectionCount());
        return c;
    }

    private long totalGcTimeMs() {
        long t = 0;
        for (GarbageCollectorMXBean g : gcs) t += Math.max(0, g.getCollectionTime());
        return t;
    }
}
