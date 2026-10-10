package com.fieldflow.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class ServiceRequestService {
    private static final Map<String, String> NEXT_STATUS = Map.of(
        "ASSIGNED", "ACCEPTED",
        "ACCEPTED", "EN_ROUTE",
        "EN_ROUTE", "ON_SITE",
        "ON_SITE", "COMPLETED"
    );

    private static final RowMapper<RequestView> REQUEST_MAPPER = (rs, row) -> new RequestView(
        rs.getObject("id", UUID.class), rs.getString("customer_username"), rs.getString("problem"),
        rs.getString("skill"), rs.getDouble("latitude"), rs.getDouble("longitude"),
        rs.getObject("window_start", OffsetDateTime.class), rs.getObject("window_end", OffsetDateTime.class),
        rs.getString("status"), rs.getString("technician_username"), rs.getString("payment_status"),
        rs.getObject("created_at", OffsetDateTime.class)
    );
    private static final String REQUEST_SELECT = """
        SELECT r.id, r.customer_username, r.problem, r.skill,
            ST_Y(r.location::geometry) AS latitude, ST_X(r.location::geometry) AS longitude,
            r.window_start, r.window_end, r.status, r.technician_username, r.created_at,
            COALESCE(p.status, 'UNPAID') AS payment_status
        FROM service_requests r LEFT JOIN payments p ON p.request_id = r.id
        """;

    private final JdbcTemplate jdbc;

    ServiceRequestService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    RequestView create(Actor actor, CreateRequest input, String key) {
        requireRole(actor, "customer");
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw badRequest("Idempotency-Key must contain 8 to 100 characters");
        }
        if (!input.windowStart().isAfter(OffsetDateTime.now()) || !input.windowEnd().isAfter(input.windowStart())) {
            throw badRequest("Choose a future time window with an end after its start");
        }
        String fingerprint = fingerprint(input);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
            INSERT INTO service_requests (id, customer_username, client_request_key, request_fingerprint,
                problem, skill, location, window_start, window_end, status)
            VALUES (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, 'REQUESTED')
            ON CONFLICT (customer_username, client_request_key) DO NOTHING
            """, id, actor.username(), key, fingerprint, input.problem().trim(), input.skill(),
            input.longitude(), input.latitude(), input.windowStart(), input.windowEnd());
        if (inserted == 1) {
            jdbc.update("INSERT INTO status_history (request_id, status, actor_username) VALUES (?, 'REQUESTED', ?)", id, actor.username());
        } else {
            Map<String, Object> previous = jdbc.queryForMap("""
                SELECT id, request_fingerprint FROM service_requests
                WHERE customer_username = ? AND client_request_key = ?
                """, actor.username(), key);
            if (!fingerprint.equals(previous.get("request_fingerprint"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key was already used for different request data");
            }
            id = (UUID) previous.get("id");
        }
        return get(actor, id);
    }

    List<RequestView> list(Actor actor) {
        String condition = switch (actor.role()) {
            case "operator" -> "";
            case "customer" -> "WHERE r.customer_username = ?";
            case "technician" -> "WHERE r.technician_username = ?";
            default -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role not allowed");
        };
        Object[] args = actor.role().equals("operator") ? new Object[] {} : new Object[] {actor.username()};
        return jdbc.query(REQUEST_SELECT + condition + " ORDER BY r.created_at DESC, r.id", REQUEST_MAPPER, args);
    }

    RequestView get(Actor actor, UUID id) {
        List<RequestView> rows = jdbc.query(REQUEST_SELECT + " WHERE r.id = ?", REQUEST_MAPPER, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
        }
        RequestView view = rows.getFirst();
        if (!actor.role().equals("operator")
            && !(actor.role().equals("customer") && actor.username().equals(view.customerUsername()))
            && !(actor.role().equals("technician") && actor.username().equals(view.technicianUsername()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
        }
        return view;
    }

    List<StatusEvent> history(Actor actor, UUID id) {
        get(actor, id);
        return jdbc.query("""
            SELECT status, actor_username, created_at FROM status_history
            WHERE request_id = ? ORDER BY id
            """, (rs, row) -> new StatusEvent(rs.getString("status"), rs.getString("actor_username"),
            rs.getObject("created_at", OffsetDateTime.class)), id);
    }

    List<Candidate> candidates(Actor actor, UUID id) {
        requireRole(actor, "operator");
        get(actor, id);
        return eligibleTechnicians(id);
    }

    private List<Candidate> eligibleTechnicians(UUID id) {
        return jdbc.query("""
            SELECT t.username, t.display_name, t.skill,
                round((ST_Distance(t.location, r.location) / 1000.0)::numeric, 1) AS distance_km
            FROM technicians t CROSS JOIN service_requests r
            WHERE r.id = ? AND r.status = 'REQUESTED'
                AND t.skill = r.skill
                AND t.available_from <= r.window_start AND t.available_to >= r.window_end
                AND ST_DWithin(t.location, r.location, t.max_distance_km * 1000.0)
                AND NOT EXISTS (
                    SELECT 1 FROM service_requests busy
                    WHERE busy.technician_username = t.username
                        AND busy.status IN ('ASSIGNED', 'ACCEPTED', 'EN_ROUTE', 'ON_SITE')
                        AND busy.window_start < r.window_end AND busy.window_end > r.window_start
                )
            ORDER BY distance_km, t.username
            """, (rs, row) -> new Candidate(rs.getString("username"), rs.getString("display_name"),
            rs.getString("skill"), rs.getDouble("distance_km")), id);
    }

    @Transactional
    RequestView assign(Actor actor, UUID id, String technicianUsername) {
        requireRole(actor, "operator");
        Map<String, Object> locked = lockRequest(id);
        String status = (String) locked.get("status");
        if (status.equals("ASSIGNED") && technicianUsername.equals(locked.get("technician_username"))) {
            return get(actor, id);
        }
        if (!status.equals("REQUESTED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only requested work can be assigned");
        }
        // Serialize competing assignments for the same technician before checking time overlap.
        List<String> technicians = jdbc.query("SELECT username FROM technicians WHERE username = ? FOR UPDATE",
            (rs, row) -> rs.getString(1), technicianUsername);
        if (technicians.isEmpty() || eligibleTechnicians(id).stream().noneMatch(t -> t.username().equals(technicianUsername))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Technician is not eligible for this request");
        }
        jdbc.update("UPDATE service_requests SET status = 'ASSIGNED', technician_username = ? WHERE id = ?",
            technicianUsername, id);
        addHistory(id, "ASSIGNED", actor.username());
        return get(actor, id);
    }

    @Transactional
    RequestView transition(Actor actor, UUID id, String target) {
        requireRole(actor, "technician");
        Map<String, Object> locked = lockRequest(id);
        if (!actor.username().equals(locked.get("technician_username"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assignment not found");
        }
        String current = (String) locked.get("status");
        if (current.equals(target)) {
            return get(actor, id);
        }
        if (!target.equals(NEXT_STATUS.get(current))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid state transition: " + current + " to " + target);
        }
        jdbc.update("UPDATE service_requests SET status = ? WHERE id = ?", target, id);
        addHistory(id, target, actor.username());
        return get(actor, id);
    }

    Map<String, Object> lockRequest(UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT status, technician_username, customer_username FROM service_requests WHERE id = ? FOR UPDATE", id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
        }
        return rows.getFirst();
    }

    void addHistory(UUID id, String status, String actor) {
        jdbc.update("INSERT INTO status_history (request_id, status, actor_username) VALUES (?, ?, ?)", id, status, actor);
    }

    private static void requireRole(Actor actor, String role) {
        if (!actor.role().equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role not allowed");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String fingerprint(CreateRequest input) {
        String canonical = String.join("|", input.problem().trim(), input.skill(),
            Double.toString(input.latitude()), Double.toString(input.longitude()),
            input.windowStart().toInstant().toString(), input.windowEnd().toInstant().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
