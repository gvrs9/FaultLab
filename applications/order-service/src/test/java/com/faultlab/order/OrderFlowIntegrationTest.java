package com.faultlab.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.faultlab.order.support.StubInventoryServer;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Full order-service against real PostgreSQL (Testcontainers: Flyway, HikariCP, JdbcClient) and a real HTTP
 * stub for inventory-service. Covers persistence, idempotency, retries and outcome mapping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    static final StubInventoryServer inventory = StubInventoryServer.start();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.flyway.url", postgres::getJdbcUrl);
        r.add("spring.flyway.user", postgres::getUsername);
        r.add("spring.flyway.password", postgres::getPassword);
        r.add("faultlab.inventory.base-url", inventory::baseUrl);
        r.add("faultlab.reconciler.enabled", () -> "false");
        r.add("faultlab.resilience.retry.mode", () -> "MANUAL");
        r.add("faultlab.resilience.retry.initial-interval", () -> "10ms");
        r.add("faultlab.resilience.retry.jitter", () -> "NONE");
        r.add("faultlab.resilience.circuit-breaker.enabled", () -> "false");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @AfterAll
    static void stopStub() { inventory.close(); }

    @BeforeEach
    void resetStub() { inventory.reset(); }

    private ResultActions placeOrder(String key, String productId, int qty) throws Exception {
        return mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key)
                .content("{\"productId\":\"" + productId + "\",\"quantity\":" + qty + "}"));
    }

    private int ordersWithKey(String key) {
        return jdbc.sql("SELECT count(*) FROM order_service.orders WHERE idempotency_key = :k")
                .param("k", key).query(Integer.class).single();
    }

    @Test
    void flywayCreatedSchemaInOwnSchema() {
        Integer tables = jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'order_service' AND table_name IN ('orders', 'flyway_schema_history')
                """).query(Integer.class).single();
        assertThat(tables).isEqualTo(2);
    }

    @Test
    void createsPersistsAndReturnsOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = placeOrder(key, "P-0001", 2)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.downstreamAttempts").value(1))
                .andReturn().getResponse().getContentAsString();
        String orderId = body.replaceAll(".*\"orderId\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("P-0001"))
                .andExpect(jsonPath("$.quantity").value(2));
        mvc.perform(get("/api/orders/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void sameIdempotencyKeyIsProcessedOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        placeOrder(key, "P-0002", 1).andExpect(status().isCreated());
        placeOrder(key, "P-0002", 1)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(header().string("X-Downstream-Attempts", "0"));
        assertThat(inventory.requestCount()).isEqualTo(1);
        assertThat(ordersWithKey(key)).isEqualTo(1);
    }

    @Test
    void reusedKeyWithDifferentPayloadIsRejected() throws Exception {
        String key = UUID.randomUUID().toString();
        placeOrder(key, "P-0003", 1).andExpect(status().isCreated());
        placeOrder(key, "P-0003", 5).andExpect(status().is(422));
        assertThat(ordersWithKey(key)).isEqualTo(1);
    }

    @Test
    void transientFailuresAreRetried() throws Exception {
        inventory.enqueue(503, 503);
        placeOrder(UUID.randomUUID().toString(), "P-0004", 1)
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Downstream-Attempts", "3"))
                .andExpect(jsonPath("$.downstreamAttempts").value(3));
    }

    @Test
    void unknownOutcomeIsResolvedByClientRetryWithSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        inventory.enqueue(500);   // not retryable by default: outcome unknown
        placeOrder(key, "P-0005", 1)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RESERVATION_UNKNOWN"));
        placeOrder(key, "P-0005", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.downstreamAttempts").value(2));
    }

    @Test
    void definitiveRejectionIsFinal() throws Exception {
        String key = UUID.randomUUID().toString();
        inventory.enqueue(409);
        placeOrder(key, "P-0006", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("REJECTED"));
        placeOrder(key, "P-0006", 1).andExpect(status().isConflict());
        assertThat(inventory.requestCount()).isEqualTo(1);   // the replay did not call inventory again
    }

    @Test
    void validationErrorsReturn400() throws Exception {
        placeOrder(UUID.randomUUID().toString(), "bad id!", 1).andExpect(status().isBadRequest());
        placeOrder(UUID.randomUUID().toString(), "P-0001", 0).andExpect(status().isBadRequest());
        assertThat(inventory.requestCount()).isZero();
    }

    @Test
    void healthEndpointReportsDatabaseUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }
}
