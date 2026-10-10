package com.fieldflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.StripeClient;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

record CheckoutLink(String sessionId, String url, String status) {}

interface CheckoutGateway {
    CheckoutLink create(UUID requestId, String customerUsername, int amountCents, String currency);
}

@Component
class StripeCheckoutGateway implements CheckoutGateway {
    private final String secretKey;
    private final String portalUrl;

    StripeCheckoutGateway(@Value("${app.stripe-secret-key:}") String secretKey,
        @Value("${app.portal-url}") String portalUrl) {
        this.secretKey = secretKey;
        this.portalUrl = portalUrl;
    }

    @Override
    public CheckoutLink create(UUID requestId, String customerUsername, int amountCents, String currency) {
        if (!secretKey.startsWith("sk_test_")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure a Stripe test secret key first");
        }
        SessionCreateParams params = SessionCreateParams.builder()
            .setMode(SessionCreateParams.Mode.PAYMENT)
            .setClientReferenceId(requestId.toString())
            .putMetadata("request_id", requestId.toString())
            .putMetadata("customer_username", customerUsername)
            .setSuccessUrl(portalUrl + "/?checkout=returned")
            .setCancelUrl(portalUrl + "/?checkout=cancelled")
            .addLineItem(SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                    .setCurrency(currency)
                    .setUnitAmount((long) amountCents)
                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                        .setName("FieldFlow completed service visit")
                        .build())
                    .build())
                .build())
            .build();
        RequestOptions options = RequestOptions.builder()
            .setIdempotencyKey("fieldflow-checkout-" + requestId)
            .build();
        try {
            Session session = new StripeClient(secretKey).v1().checkout().sessions().create(params, options);
            return new CheckoutLink(session.getId(), session.getUrl(), "PENDING");
        } catch (StripeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Stripe test Checkout could not be created", error);
        }
    }
}

@Service
class PaymentService {
    private final JdbcTemplate jdbc;
    private final ServiceRequestService requests;
    private final CheckoutGateway gateway;
    private final ObjectMapper json;
    private final String webhookSecret;
    private final int amountCents;
    private final String currency;

    PaymentService(JdbcTemplate jdbc, ServiceRequestService requests, CheckoutGateway gateway,
        ObjectMapper json, @Value("${app.stripe-webhook-secret:}") String webhookSecret,
        @Value("${app.payment-amount-cents}") int amountCents,
        @Value("${app.payment-currency}") String currency) {
        this.jdbc = jdbc;
        this.requests = requests;
        this.gateway = gateway;
        this.json = json;
        this.webhookSecret = webhookSecret;
        this.amountCents = amountCents;
        this.currency = currency;
    }

    @Transactional
    CheckoutLink checkout(Actor actor, UUID requestId) {
        if (!actor.role().equals("customer")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only customers can start payment");
        }
        Map<String, Object> locked = requests.lockRequest(requestId);
        if (!actor.username().equals(locked.get("customer_username"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found");
        }
        if (!"COMPLETED".equals(locked.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment is available after completion");
        }
        List<CheckoutLink> existing = jdbc.query("""
            SELECT stripe_session_id, checkout_url, status FROM payments WHERE request_id = ?
            """, (rs, row) -> new CheckoutLink(rs.getString(1), rs.getString(2), rs.getString(3)), requestId);
        if (!existing.isEmpty()) return existing.getFirst();

        // Stripe and PostgreSQL do not share a transaction; the Stripe idempotency key covers retries.
        CheckoutLink created = gateway.create(requestId, actor.username(), amountCents, currency);
        jdbc.update("""
            INSERT INTO payments (request_id, stripe_session_id, checkout_url, status, amount_cents, currency)
            VALUES (?, ?, ?, 'PENDING', ?, ?)
            """, requestId, created.sessionId(), created.url(), amountCents, currency);
        return created;
    }

    @Transactional
    void acceptWebhook(String payload, String signature) {
        if (!webhookSecret.startsWith("whsec_")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure a Stripe webhook secret first");
        }
        final Event event;
        try {
            event = Webhook.constructEvent(payload, signature, webhookSecret);
        } catch (SignatureVerificationException | RuntimeException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe signature", error);
        }
        if (!"checkout.session.completed".equals(event.getType())) return;

        final JsonNode object;
        try {
            JsonNode root = json.readTree(payload);
            if (root.path("livemode").asBoolean(true)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Live Stripe events are not accepted");
            }
            object = root.path("data").path("object");
        } catch (ResponseStatusException error) {
            throw error;
        } catch (Exception error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Stripe event body", error);
        }
        if (!"paid".equals(object.path("payment_status").asText())) return;
        String requestIdText = object.path("metadata").path("request_id").asText();
        UUID requestId;
        try {
            requestId = UUID.fromString(requestIdText);
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing request reference", error);
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT stripe_session_id, amount_cents, currency, status FROM payments
            WHERE request_id = ? FOR UPDATE
            """, requestId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown payment request");
        }
        Map<String, Object> payment = rows.getFirst();
        if (!object.path("id").asText().equals(payment.get("stripe_session_id"))
            || object.path("amount_total").asInt(-1) != ((Number) payment.get("amount_cents")).intValue()
            || !object.path("currency").asText().equals(payment.get("currency"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Checkout session does not match the stored payment");
        }
        int inserted = jdbc.update("""
            INSERT INTO stripe_events (event_id, request_id) VALUES (?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """, event.getId(), requestId);
        if (inserted == 1) {
            jdbc.update("UPDATE payments SET status = 'PAID', paid_at = now() WHERE request_id = ? AND status = 'PENDING'", requestId);
        }
    }
}
