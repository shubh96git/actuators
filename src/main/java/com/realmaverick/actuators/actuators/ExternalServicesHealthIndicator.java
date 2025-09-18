package com.realmaverick.actuators.actuators;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.*;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class ExternalServicesHealthIndicator implements HealthIndicator {

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private DataSource dataSource;

    @Value("${nmt.url}")
    private String ext_service1;

    @Value("${solr.url}")
    private String ext_service2;

    @Value("${asr.rivaUrl}")
    private String asrUrl;

    private final MeterRegistry meterRegistry;

    // Track alert notifications
    private final Map<String, Boolean> alertSent = new ConcurrentHashMap<>();
    private final Map<String, Integer> serviceStatus = new ConcurrentHashMap<>();

    @Autowired
    public ExternalServicesHealthIndicator(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @jakarta.annotation.PostConstruct
    public void initMetrics() {
        Gauge.builder("external_service_up", () -> checkExt1Service(ext_service1) ? 1 : 0)
                .description("EXT_SERVICE 1 service availability")
                .tag("service", "EXT_SERVICE 1")
                .register(meterRegistry);

        Gauge.builder("external_service_up", () -> checkExt2Service(ext_service2) ? 1 : 0)
                .description("EXT_SERVICE 2 service availability")
                .tag("service", "EXT_SERVICE 2")
                .register(meterRegistry);

        Gauge.builder("external_service_up", () -> checkExt_WebSocketService(asrUrl) ? 1 : 0)
                .description("EXT_SERVICE 3 service availability")
                .tag("service", "EXT_SERVICE 3")
                .register(meterRegistry);

        Gauge.builder("external_service_up", () -> checkDatabase() ? 1 : 0)
                .description("Database availability")
                .tag("service", "DB")
                .register(meterRegistry);
    }

    @Override
    public Health health() {
        boolean nmtUp = checkExt1Service(ext_service1);
        boolean solrUp = checkExt2Service(ext_service2);
        boolean asrUp = checkExt_WebSocketService(asrUrl);
        boolean dbUp = checkDatabase();

        Health.Builder builder = (nmtUp && solrUp && asrUp && dbUp) ? Health.up() : Health.down();

        builder.withDetail("NMT", nmtUp ? "Available" : "Unavailable")
                .withDetail("Solr", solrUp ? "Available" : "Unavailable")
                .withDetail("ASR", asrUp ? "Available" : "Unavailable")
                .withDetail("DB", dbUp ? "Available" : "Unavailable");

        return builder.build();
    }

    // ✅ NMT health check (POST)
    private boolean checkExt1Service(String url) {
        try {
            boolean up = restTemplate.postForEntity(url, null, String.class)
                    .getStatusCode()
                    .is2xxSuccessful();

            serviceStatus.put("NMT", up ? 1 : 0);
            if (up) alertSent.put("NMT", false);
            return up;
        } catch (Exception e) {
            serviceStatus.put("NMT", 0);
            sendAlertOnce("NMT");
            return false;
        }
    }

    // ✅ Solr health check (GET)
    private boolean checkExt2Service(String url) {
        try {
            boolean up = restTemplate.getForEntity(url, String.class)
                    .getStatusCode()
                    .is2xxSuccessful();

            serviceStatus.put("Solr", up ? 1 : 0);
            if (up) alertSent.put("Solr", false);
            return up;
        } catch (Exception e) {
            serviceStatus.put("Solr", 0);
            sendAlertOnce("Solr");
            return false;
        }
    }

    // ✅ ASR health check (WebSocket with 3s timeout)
    private boolean checkExt_WebSocketService(String url) {
        try {
            WebSocketClient client = new StandardWebSocketClient();
            CompletableFuture<Boolean> future = new CompletableFuture<>();

            client.doHandshake(new SimpleWebSocketHandler(future), url).get();

            boolean up = future.get(3, TimeUnit.SECONDS); // wait max 3s

            serviceStatus.put("ASR", up ? 1 : 0);
            if (up) alertSent.put("ASR", false);
            return up;
        } catch (Exception e) {
            serviceStatus.put("ASR", 0);
            sendAlertOnce("ASR");
            return false;
        }
    }

    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            boolean up = (conn != null && !conn.isClosed());
            serviceStatus.put("DB", up ? 1 : 0);
            if (up) {
                alertSent.put("DB", false);
                return true;
            } else {
                sendAlertOnce("DB");
                return false;
            }
        } catch (Exception e) {
            serviceStatus.put("DB", 0);
            sendAlertOnce("DB");
            return false;
        }
    }

    private void sendAlertOnce(String serviceName) {
        if (!alertSent.getOrDefault(serviceName, false)) {
            alertSent.put(serviceName, true);

            ZonedDateTime istTime = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
            String formattedTime = istTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));

            String message = String.format(
                    "⚠ ALERT: [%s] is DOWN!\n" +
                            "Time: %s\n" +
                            "Environment: %s\n" +
                            "Action Required: Please check immediately.",
                    serviceName,
                    formattedTime,
                    System.getProperty("spring.profiles.active", "production")
            );

            // TODO: integrate SMS/email service
            System.out.println(message);
        }
    }

    // ✅ Inner WebSocketHandler for ASR check
    private static class SimpleWebSocketHandler extends AbstractWebSocketHandler {

        private final CompletableFuture<Boolean> future;

        public SimpleWebSocketHandler(CompletableFuture<Boolean> future) {
            this.future = future;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            future.complete(true);
            try {
                session.close();
            } catch (Exception ignored) {}
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            future.complete(false);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            if (!future.isDone()) {
                future.complete(false);
            }
        }
    }
}
