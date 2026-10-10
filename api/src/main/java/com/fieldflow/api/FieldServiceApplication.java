package com.fieldflow.api;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class FieldServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FieldServiceApplication.class, args);
    }
}

@Component
class SeedData implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    SeedData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedTechnician("alex", "Alex Morgan", "plumbing", 9.1900, 45.4642, 50);
        seedTechnician("sam", "Sam Rivera", "plumbing", 9.2800, 45.5100, 30);
        seedTechnician("jordan", "Jordan Lee", "electrical", 9.2000, 45.4700, 40);

        UUID id = UUID.nameUUIDFromBytes("fieldflow-initial-request".getBytes(StandardCharsets.UTF_8));
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end = start.plusHours(2);
        int inserted = jdbc.update("""
            INSERT INTO service_requests (id, customer_username, client_request_key, request_fingerprint,
                problem, skill, location, window_start, window_end, status)
            VALUES (?, 'customer', 'initial-request', 'seed', 'Kitchen sink is leaking', 'plumbing',
                ST_SetSRID(ST_MakePoint(9.1950, 45.4650), 4326)::geography, ?, ?, 'REQUESTED')
            ON CONFLICT (customer_username, client_request_key) DO NOTHING
            """, id, start, end);
        if (inserted == 1) {
            jdbc.update("INSERT INTO status_history (request_id, status, actor_username) VALUES (?, 'REQUESTED', 'customer')", id);
        }
    }

    private void seedTechnician(String username, String name, String skill, double longitude, double latitude, int radiusKm) {
        jdbc.update("""
            INSERT INTO technicians (username, display_name, skill, available_from, available_to, location, max_distance_km)
            VALUES (?, ?, ?, now() - interval '1 day', now() + interval '365 days',
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
            ON CONFLICT (username) DO UPDATE SET available_to = EXCLUDED.available_to
            """, username, name, skill, longitude, latitude, radiusKm);
    }
}
