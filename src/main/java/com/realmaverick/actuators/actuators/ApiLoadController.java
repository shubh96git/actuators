package com.realmaverick.actuators.actuators;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
@Endpoint(id = "api-load")
public class ApiLoadController {

    private final MeterRegistry meterRegistry;
    private final Environment environment;

    // store API stats for Prometheus gauges
    private final Map<String, ApiStats> apiStatsMap = new HashMap<>();
    private final Set<String> registeredApis = new HashSet<>();

    public ApiLoadController(MeterRegistry meterRegistry, Environment environment) {
        this.meterRegistry = meterRegistry;
        this.environment = environment;
    }

    @PostConstruct
    public void initMetrics() {
        // At startup, register gauges for all known APIs
        registerApiGauges();
    }

    // 🔹 Register gauges dynamically (only once per API key)
    private void registerApiGauges() {
        meterRegistry.find("http.server.requests").timers().forEach(timer -> {
            String uri = timer.getId().getTag("uri");
            String method = timer.getId().getTag("method");
            if (uri == null || method == null) return;

            String key = method + " " + uri;

            if (!registeredApis.contains(key)) {
                apiStatsMap.putIfAbsent(key, new ApiStats());

                Gauge.builder("api_request_count", apiStatsMap, m -> m.getOrDefault(key, new ApiStats()).count)
                        .tag("api", key)
                        .register(meterRegistry);

                Gauge.builder("api_request_avg_seconds", apiStatsMap, m -> m.getOrDefault(key, new ApiStats()).avg)
                        .tag("api", key)
                        .register(meterRegistry);

                Gauge.builder("api_request_max_seconds", apiStatsMap, m -> m.getOrDefault(key, new ApiStats()).max)
                        .tag("api", key)
                        .register(meterRegistry);

                registeredApis.add(key);
            }
        });
    }

    // 🔹 Update apiStatsMap every 30 seconds
    @Scheduled(fixedDelay = 30000)
    public void refreshApiStats() {
        Map<String, List<Timer>> apiTimers = new HashMap<>();
        meterRegistry.find("http.server.requests").timers().forEach(timer -> {
            String uri = timer.getId().getTag("uri");
            String method = timer.getId().getTag("method");
            if (uri == null || method == null) return;

            String key = method + " " + uri;
            apiTimers.computeIfAbsent(key, k -> new ArrayList<>()).add(timer);
        });

        apiTimers.forEach((api, timers) -> {
            long totalCount = 0;
            double totalTime = 0.0;
            double maxTime = 0.0;

            for (Timer timer : timers) {
                totalCount += timer.count();
                totalTime += timer.totalTime(TimeUnit.SECONDS);
                maxTime = Math.max(maxTime, timer.max(TimeUnit.SECONDS));
            }

            double avgTime = totalCount > 0 ? totalTime / totalCount : 0.0;

            ApiStats statsHolder = apiStatsMap.computeIfAbsent(api, k -> new ApiStats());
            statsHolder.count = totalCount;
            statsHolder.avg = avgTime;
            statsHolder.max = maxTime;
        });

        // make sure new APIs get registered as gauges too
        registerApiGauges();
    }

    // Actuator custom endpoint response (JSON for humans)
    @ReadOperation
    public Map<String, Object> getApiLoad() {
        Map<String, Object> result = new HashMap<>();

        String[] activeProfiles = environment.getActiveProfiles();
        String profile = activeProfiles.length > 0 ? String.join(",", activeProfiles) : "default";

        apiStatsMap.forEach((api, stats) -> {
            Map<String, Object> statMap = new HashMap<>();
            statMap.put("count", stats.count);
            statMap.put("avg", stats.avg);
            statMap.put("max", stats.max);
            result.put(profile + " -> " + api, statMap);
        });

        return result;
    }

    private static class ApiStats {
        long count;
        double avg;
        double max;
    }
}
