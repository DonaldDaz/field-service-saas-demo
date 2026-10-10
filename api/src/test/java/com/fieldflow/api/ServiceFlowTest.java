package com.fieldflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class ServiceFlowTest {
    private static final String WEBHOOK_SECRET = "whsec_local_integration_test";

    @Container
    static PostgreSQLContainer database = new PostgreSQLContainer(
        DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("fieldflow").withUsername("fieldflow").withPassword("local-test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("app.stripe-webhook-secret", () -> WEBHOOK_SECRET);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost:9999/realms/fieldflow");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost:9999/certs");
    }

    @TestConfiguration
    static class TestGateway {
        @Bean
        @Primary
        CheckoutGateway checkoutGateway() {
            return (id, customer, amount, currency) -> new CheckoutLink(
                "cs_test_" + id.toString().replace("-", ""), "https://checkout.stripe.com/test/" + id, "PENDING");
        }
    }

    @Autowired ServiceRequestService requests;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;

    private static CreateRequest input(String problem, String skill, double longitude, OffsetDateTime start) {
        return new CreateRequest(problem, skill, 45.4650, longitude, start, start.plusHours(2));
    }

    @Test
    void assignmentRequiresSkillAvailabilityAndDistanceAndTransitionsAreIdempotent() {
        Actor customer = new Actor("customer", "customer");
        Actor operator = new Actor("operator", "operator");
        Actor alex = new Actor("alex", "technician");
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(4);
        String key = UUID.randomUUID().toString();
        CreateRequest payload = input("A bathroom pipe is leaking", "plumbing", 9.1950, start);
        RequestView job = requests.create(customer, payload, key);
        assertThat(requests.create(customer, payload, key).id()).isEqualTo(job.id());
        assertThatThrownBy(() -> requests.create(customer,
            input("Different problem at the same site", "plumbing", 9.1950, start), key))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(requests.candidates(operator, job.id())).extracting(Candidate::username)
            .contains("alex", "sam").doesNotContain("jordan");
        assertThatThrownBy(() -> requests.assign(operator, job.id(), "jordan"))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(requests.assign(operator, job.id(), "alex").status()).isEqualTo("ASSIGNED");
        requests.assign(operator, job.id(), "alex");
        assertThat(requests.history(operator, job.id())).hasSize(2);

        RequestView second = requests.create(customer,
            input("Another pipe is leaking nearby", "plumbing", 9.1950, start), UUID.randomUUID().toString());
        assertThat(requests.candidates(operator, second.id())).extracting(Candidate::username)
            .doesNotContain("alex").contains("sam");
        assertThatThrownBy(() -> requests.assign(customer, second.id(), "sam"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> requests.transition(new Actor("sam", "technician"), job.id(), "ACCEPTED"))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> requests.transition(alex, job.id(), "COMPLETED"))
            .isInstanceOf(ResponseStatusException.class);

        for (String state : new String[] {"ACCEPTED", "EN_ROUTE", "ON_SITE", "COMPLETED"}) {
            assertThat(requests.transition(alex, job.id(), state).status()).isEqualTo(state);
            requests.transition(alex, job.id(), state);
        }
        assertThat(requests.history(customer, job.id())).extracting(StatusEvent::status)
            .containsExactly("REQUESTED", "ASSIGNED", "ACCEPTED", "EN_ROUTE", "ON_SITE", "COMPLETED");
        assertThatThrownBy(() -> requests.get(new Actor("other", "customer"), job.id()))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void paymentChangesOnlyAfterVerifiedTestWebhookAndReplayDoesNotChargeTwice() throws Exception {
        Actor customer = new Actor("customer", "customer");
        Actor operator = new Actor("operator", "operator");
        Actor sam = new Actor("sam", "technician");
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
        RequestView job = requests.create(customer,
            input("The kitchen drain is leaking", "plumbing", 9.2800, start), UUID.randomUUID().toString());
        assertThatThrownBy(() -> payments.checkout(customer, job.id())).isInstanceOf(ResponseStatusException.class);
        requests.assign(operator, job.id(), "sam");
        for (String state : new String[] {"ACCEPTED", "EN_ROUTE", "ON_SITE", "COMPLETED"}) {
            requests.transition(sam, job.id(), state);
        }
        CheckoutLink checkout = payments.checkout(customer, job.id());
        assertThat(payments.checkout(customer, job.id()).sessionId()).isEqualTo(checkout.sessionId());
        assertThat(requests.get(customer, job.id()).paymentStatus()).isEqualTo("PENDING");
        String eventId = "evt_test_" + UUID.randomUUID().toString().replace("-", "");
        String payload = """
            {"id":"%s","object":"event","type":"checkout.session.completed","livemode":false,
             "data":{"object":{"id":"%s","payment_status":"paid","amount_total":4900,
             "currency":"eur","metadata":{"request_id":"%s"}}}}
            """.formatted(eventId, checkout.sessionId(), job.id());
        assertThatThrownBy(() -> payments.acceptWebhook(payload, "t=1,v1=invalid"))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(requests.get(customer, job.id()).paymentStatus()).isEqualTo("PENDING");
        String signature = sign(payload);
        payments.acceptWebhook(payload, signature);
        payments.acceptWebhook(payload, signature);
        assertThat(requests.get(customer, job.id()).paymentStatus()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stripe_events WHERE request_id = ?", Integer.class, job.id()))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE request_id = ?", Integer.class, job.id()))
            .isEqualTo(1);
    }

    private static String sign(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String digest = HexFormat.of().formatHex(hmac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return "t=" + timestamp + ",v1=" + digest;
    }
}
