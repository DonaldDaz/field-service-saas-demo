package com.fieldflow.api;

import io.swagger.v3.oas.annotations.Operation;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PaymentController {
    private final PaymentService payments;

    PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/api/requests/{id}/checkout")
    @Operation(summary = "Create or reuse a Stripe test Checkout Session", description = "Customer only, after work is completed. The payment remains pending until a verified webhook arrives.")
    CheckoutLink checkout(@PathVariable UUID id, JwtAuthenticationToken token) {
        return payments.checkout(Actor.from(token), id);
    }

    @PostMapping("/webhooks/stripe")
    @Operation(summary = "Verify and apply a Stripe test-mode webhook", description = "The Stripe-Signature header is checked before payment state changes. Repeated event IDs are ignored.")
    Map<String, String> webhook(@RequestBody String payload, @RequestHeader("Stripe-Signature") String signature) {
        payments.acceptWebhook(payload, signature);
        return Map.of("status", "received");
    }
}
