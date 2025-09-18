package com.realmaverick.actuators.actuators;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Endpoint(id = "system-health")
public class SystemHealthController {

    private final MeterRegistry meterRegistry;

    public SystemHealthController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @ReadOperation
    public Map<String, Object> getSystemHealth() {
        Map<String, Object> health = new HashMap<>();

        // CPU usage (safe check)
        Double cpuUsage = null;
        try {
            var cpuGauge = meterRegistry.find("system.cpu.usage").gauge();
            if (cpuGauge != null) {
                cpuUsage = cpuGauge.value() * 100; // in percentage
            }
        } catch (Exception ignored) {}

        health.put("cpuUsagePercent", cpuUsage);

        // Heap memory usage (safe)
        double heapUsedMB = 0.0;
        double heapMaxMB = 0.0;
        try {
            heapUsedMB = meterRegistry.get("jvm.memory.used").gauges().stream()
                    .filter(g -> "heap".equals(g.getId().getTag("area")))
                    .mapToDouble(g -> g.value())
                    .sum() / (1024 * 1024);

            heapMaxMB = meterRegistry.get("jvm.memory.max").gauges().stream()
                    .filter(g -> "heap".equals(g.getId().getTag("area")))
                    .mapToDouble(g -> g.value())
                    .sum() / (1024 * 1024);
        } catch (Exception ignored) {}

        health.put("heapUsedMB", heapUsedMB);
        health.put("heapMaxMB", heapMaxMB);
        health.put("heapUsagePercent", heapMaxMB > 0 ? (heapUsedMB / heapMaxMB) * 100 : null);

        // Live threads
        Integer liveThreads = null;
        try {
            var threadGauge = meterRegistry.find("jvm.threads.live").gauge();
            if (threadGauge != null) {
                liveThreads = (int) threadGauge.value();
            }
        } catch (Exception ignored) {}
        health.put("liveThreads", liveThreads);

        // GC pause time
        Double gcPauseMillis = null;
        try {
            var gcTimer = meterRegistry.find("jvm.gc.pause").timer();
            if (gcTimer != null) {
                gcPauseMillis = gcTimer.totalTime(TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignored) {}
        health.put("gcPauseMillis", gcPauseMillis);

        return health;
    }
}
