package com.faultlab.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.faultlab.inventory.db.DbOperations;
import com.faultlab.inventory.support.PostgresContainerSupport;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pool exhaustion: 1 connection, 300 ms acquisition timeout, 1 s artificial query delay.
 * Concurrent requests must fail fast with 503 (not hang, not 500), and the pool must recover.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HikariExhaustionIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        PostgresContainerSupport.register(r);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        r.add("spring.datasource.hikari.minimum-idle", () -> "1");
        r.add("spring.datasource.hikari.connection-timeout", () -> "300");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    DbOperations db;

    @Autowired
    HikariDataSource dataSource;

    @Test
    void exhaustedPoolFailsFastWith503AndRecovers() throws Exception {
        db.setArtificialDelay(Duration.ofSeconds(1));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return mvc.perform(get("/api/inventory/P-0001")).andReturn().getResponse().getStatus();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
            assertThat(statuses).contains(200, 503).doesNotContain(500);
        } finally {
            db.setArtificialDelay(Duration.ZERO);
            pool.shutdownNow();
        }
        // connections were released: the pool serves requests again
        assertThat(mvc.perform(get("/api/inventory/P-0001")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(dataSource.getHikariPoolMXBean().getActiveConnections()).isZero();
    }
}
