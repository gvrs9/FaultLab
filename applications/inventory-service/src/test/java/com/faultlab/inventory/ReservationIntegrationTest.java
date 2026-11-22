package com.faultlab.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.faultlab.inventory.inventory.InventoryExceptions.IdempotencyMismatchException;
import com.faultlab.inventory.inventory.InventoryExceptions.InsufficientStockException;
import com.faultlab.inventory.inventory.InventoryExceptions.ProductNotFoundException;
import com.faultlab.inventory.inventory.ReservationResult;
import com.faultlab.inventory.inventory.ReservationService;
import com.faultlab.inventory.support.PostgresContainerSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
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

/** Real PostgreSQL via Testcontainers: Flyway, HikariCP, idempotency, concurrency and failure modes. */
@SpringBootTest
@AutoConfigureMockMvc
class ReservationIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        PostgresContainerSupport.register(r);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    @Autowired
    ReservationService service;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mvc;

    @AfterEach
    void resetFailureMode() throws Exception {
        mvc.perform(post("/admin/reset")).andExpect(status().isOk());
    }

    private int available(String productId) {
        return jdbc.sql("SELECT available FROM inventory_service.products WHERE product_id = :p")
                .param("p", productId).query(Integer.class).single();
    }

    private int reservations(String key) {
        return jdbc.sql("SELECT count(*) FROM inventory_service.reservations WHERE idempotency_key = :k")
                .param("k", key).query(Integer.class).single();
    }

    private ResultActions postReservation(UUID orderId, String productId, int qty) throws Exception {
        return mvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"" + orderId + "\",\"productId\":\"" + productId + "\",\"quantity\":" + qty + "}"));
    }

    @Test
    void migrationsSeededProducts() {
        assertThat(jdbc.sql("SELECT count(*) FROM inventory_service.products").query(Integer.class).single())
                .isEqualTo(1001);
    }

    @Test
    void reserveDecrementsStockAndReplayDoesNot() {
        UUID order = UUID.randomUUID();
        int before = available("P-0100");
        ReservationResult first = service.reserve(order.toString(), order, "P-0100", 3);
        ReservationResult second = service.reserve(order.toString(), order, "P-0100", 3);
        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.reservation().reservationId()).isEqualTo(first.reservation().reservationId());
        assertThat(available("P-0100")).isEqualTo(before - 3);
    }

    @Test
    void concurrentDuplicatesReserveExactlyOnce() throws Exception {
        UUID order = UUID.randomUUID();
        String key = order.toString();
        int before = available("P-0200");
        List<ReservationResult> results = runConcurrently(16, () -> service.reserve(key, order, "P-0200", 2));
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(reservations(key)).isEqualTo(1);
        assertThat(available("P-0200")).isEqualTo(before - 2);
    }

    @Test
    void concurrentDistinctReservationsAreAllApplied() throws Exception {
        int before = available("P-0300");
        runConcurrently(40, () -> {
            UUID order = UUID.randomUUID();
            return service.reserve(order.toString(), order, "P-0300", 1);
        });
        assertThat(available("P-0300")).isEqualTo(before - 40);
    }

    @Test
    void insufficientStockRollsBackTheReservation() {
        UUID order = UUID.randomUUID();
        int before = available("P-LIMITED");
        assertThatThrownBy(() -> service.reserve(order.toString(), order, "P-LIMITED", before + 1))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(reservations(order.toString())).isZero();
        assertThat(available("P-LIMITED")).isEqualTo(before);
    }

    @Test
    void unknownProductAndPayloadMismatchAreRejected() {
        UUID order = UUID.randomUUID();
        assertThatThrownBy(() -> service.reserve(order.toString(), order, "NOPE", 1))
                .isInstanceOf(ProductNotFoundException.class);
        service.reserve(order.toString(), order, "P-0400", 1);
        assertThatThrownBy(() -> service.reserve(order.toString(), order, "P-0400", 2))
                .isInstanceOf(IdempotencyMismatchException.class);
    }

    @Test
    void httpEndpointsWork() throws Exception {
        UUID order = UUID.randomUUID();
        postReservation(order, "P-0500", 1).andExpect(status().isCreated()).andExpect(jsonPath("$.replayed").value(false));
        postReservation(order, "P-0500", 1).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        postReservation(order, "P-0500", 9).andExpect(status().is(422));
        postReservation(UUID.randomUUID(), "P-LIMITED", 999).andExpect(status().isConflict());
        mvc.perform(get("/api/inventory/P-0500")).andExpect(status().isOk()).andExpect(jsonPath("$.productId").value("P-0500"));
        mvc.perform(get("/api/inventory/reservations/" + order)).andExpect(status().isOk());
        mvc.perform(get("/api/inventory/NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void failFirst2ModeOverHttp() throws Exception {
        mvc.perform(post("/admin/failure-mode").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"FAIL_FIRST_2\"}"))
                .andExpect(status().isOk());
        UUID order = UUID.randomUUID();
        postReservation(order, "P-0600", 1).andExpect(status().isServiceUnavailable());
        postReservation(order, "P-0600", 1).andExpect(status().isServiceUnavailable());
        postReservation(order, "P-0600", 1).andExpect(status().isCreated());
    }

    @Test
    void processThenFailOnceCommitsButReturns500ThenReplays() throws Exception {
        mvc.perform(post("/admin/failure-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"PROCESS_THEN_FAIL_ONCE\"}")).andExpect(status().isOk());
        UUID order = UUID.randomUUID();
        int before = available("P-0700");
        postReservation(order, "P-0700", 1).andExpect(status().isInternalServerError());
        assertThat(available("P-0700")).isEqualTo(before - 1);   // processed despite the error
        postReservation(order, "P-0700", 1).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertThat(available("P-0700")).isEqualTo(before - 1);   // not reserved twice
    }

    @Test
    void artificialDbDelayIsApplied() throws Exception {
        mvc.perform(post("/admin/failure-mode").contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"NORMAL\",\"dbDelayMs\":300}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.dbDelayMs").value(300));
        long start = System.nanoTime();
        mvc.perform(get("/api/inventory/P-0001")).andExpect(status().isOk());
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(290);
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
