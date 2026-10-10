package com.fieldflow.api;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

record Actor(String username, String role) {
    static Actor from(JwtAuthenticationToken token) {
        for (String role : List.of("operator", "technician", "customer")) {
            if (token.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(("ROLE_" + role.toUpperCase())::equals)) {
                return new Actor(token.getToken().getClaimAsString("preferred_username"), role);
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN, "No field-service role assigned");
    }
}

record CreateRequest(
    @NotBlank @Size(min = 10, max = 500) String problem,
    @NotBlank @Pattern(regexp = "plumbing|electrical") String skill,
    @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
    @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
    @NotNull OffsetDateTime windowStart,
    @NotNull OffsetDateTime windowEnd
) {}

record RequestView(UUID id, String customerUsername, String problem, String skill,
    double latitude, double longitude, OffsetDateTime windowStart, OffsetDateTime windowEnd,
    String status, String technicianUsername, String paymentStatus, OffsetDateTime createdAt) {}

record Candidate(String username, String displayName, String skill, double distanceKm) {}
record StatusEvent(String status, String actorUsername, OffsetDateTime createdAt) {}
record Assignment(@NotBlank String technicianUsername) {}
record Transition(@NotBlank String status) {}

@RestController
@RequestMapping("/api")
class RequestController {
    private final ServiceRequestService requests;

    RequestController(ServiceRequestService requests) {
        this.requests = requests;
    }

    @GetMapping("/me")
    Actor me(JwtAuthenticationToken token) {
        return Actor.from(token);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a service request", description = "Customer only. Idempotency-Key deduplicates retries with identical input.")
    RequestView create(@Valid @RequestBody CreateRequest input,
        @RequestHeader("Idempotency-Key") String key, JwtAuthenticationToken token) {
        return requests.create(Actor.from(token), input, key);
    }

    @GetMapping("/requests")
    @Operation(summary = "List visible requests", description = "Customers see their own work, operators see all, technicians see assigned work.")
    List<RequestView> list(JwtAuthenticationToken token) {
        return requests.list(Actor.from(token));
    }

    @GetMapping("/requests/{id}")
    RequestView get(@PathVariable UUID id, JwtAuthenticationToken token) {
        return requests.get(Actor.from(token), id);
    }

    @GetMapping("/requests/{id}/history")
    @Operation(summary = "Read the append-only status history")
    List<StatusEvent> history(@PathVariable UUID id, JwtAuthenticationToken token) {
        return requests.history(Actor.from(token), id);
    }

    @GetMapping("/requests/{id}/candidates")
    @Operation(summary = "Rank eligible technicians by distance", description = "Operator only. Skill, time window, radius, and conflicting assignments are checked in SQL.")
    List<Candidate> candidates(@PathVariable UUID id, JwtAuthenticationToken token) {
        return requests.candidates(Actor.from(token), id);
    }

    @PostMapping("/requests/{id}/assign")
    @Operation(summary = "Assign an eligible technician", description = "Operator only. Repeating an identical assignment does not add history.")
    RequestView assign(@PathVariable UUID id, @Valid @RequestBody Assignment input, JwtAuthenticationToken token) {
        return requests.assign(Actor.from(token), id, input.technicianUsername());
    }

    @PatchMapping("/requests/{id}/status")
    @Operation(summary = "Advance an assigned job", description = "Technician only. ASSIGNED → ACCEPTED → EN_ROUTE → ON_SITE → COMPLETED.")
    RequestView transition(@PathVariable UUID id, @Valid @RequestBody Transition input, JwtAuthenticationToken token) {
        return requests.transition(Actor.from(token), id, input.status());
    }
}
