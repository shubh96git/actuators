package com.realmaverick.actuators.actuators;


import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@Endpoint(id = "system-health-trend-24h")
public class SystemHealthTrend24hController {

    private final MeterRegistry meterRegistry;
    private final Map<String, Deque<Double>> trends = new ConcurrentHashMap<>();
    private final int maxPoints = 1440; // last 24 hours, 1 point per minute

    public SystemHealthTrend24hController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        // Initialize metrics
        trends.put("cpuUsagePercent", new ArrayDeque<>());
        trends.put("heapUsagePercent", new ArrayDeque<>());
        trends.put("liveThreads", new ArrayDeque<>());
        trends.put("gcPauseMillis", new ArrayDeque<>());
    }

    @ReadOperation
    public Map<String, List<Double>> getTrends() {
        Map<String, List<Double>> snapshot = new HashMap<>();
        trends.forEach((k, v) -> snapshot.put(k, new ArrayList<>(v)));
        return snapshot;
    }

    // Scheduled to run every minute
    @Scheduled(fixedRate = 60_000)
    public void updateTrends() {
        addPoint("cpuUsagePercent", safeGaugeValue("system.cpu.usage") * 100);
        addPoint("heapUsagePercent", calculateHeapUsage());
        addPoint("liveThreads", safeGaugeValue("jvm.threads.live"));
        addPoint("gcPauseMillis", safeTimerTotalTime("jvm.gc.pause"));
    }

    private void addPoint(String metric, Double value) {
        Deque<Double> deque = trends.get(metric);
        if (deque.size() >= maxPoints) deque.pollFirst(); // remove oldest
        deque.offerLast(value != null ? value : 0.0);
    }

    private Double safeGaugeValue(String name) {
        try {
            var gauge = meterRegistry.find(name).gauge();
            return gauge != null ? gauge.value() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Double safeTimerTotalTime(String name) {
        try {
            var timer = meterRegistry.find(name).timer();
            return timer != null ? timer.totalTime(TimeUnit.MILLISECONDS) : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Double calculateHeapUsage() {
        try {
            double used = meterRegistry.get("jvm.memory.used").gauges().stream()
                    .filter(g -> "heap".equals(g.getId().getTag("area")))
                    .mapToDouble(g -> g.value()).sum();
            double max = meterRegistry.get("jvm.memory.max").gauges().stream()
                    .filter(g -> "heap".equals(g.getId().getTag("area")))
                    .mapToDouble(g -> g.value()).sum();
            return max > 0 ? (used / max) * 100 : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
