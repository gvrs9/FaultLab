package com.faultlab.order.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.faultlab.order.inventory.ReservationOutcome.Result;
import com.faultlab.order.resilience.BackoffCalculator;
import com.faultlab.order.resilience.ManualRetryExecutor;
import com.faultlab.order.resilience.ResilienceFactory;
import com.faultlab.order.resilience.ResilienceProperties;
import com.faultlab.order.resilience.ResilienceProperties.RetryMode;
import com.faultlab.order.resilience.RetryPolicy;
import com.faultlab.order.support.StubInventoryServer;
import com.faultlab.order.support.TestResilience;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** HTTP client integration, retry, circuit-breaker and bulkhead tests against a real (stub) HTTP server. */
class InventoryGatewayTest {

    private static final StubInventoryServer stub = StubInventoryServer.start();

    private CircuitBreaker circuitBreaker;

    @AfterAll
    static void stop() { stub.close(); }

    @BeforeEach
    void reset() { stub.reset(); }

    private InventoryGateway gateway(ResilienceProperties p, String baseUrl, Duration responseTimeout) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(300)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(responseTimeout);
        RestClient rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .requestInterceptor((req, body, exec) -> {
                    String cid = ThreadContext.get("correlation_id");
                    if (cid != null) {
                        req.getHeaders().set("X-Correlation-Id", cid);
                    }
                    return exec.execute(req, body);
                })
                .build();
        RetryPolicy policy = new RetryPolicy(p.retry());
        BackoffCalculator backoff = new BackoffCalculator(p.retry());
        circuitBreaker = ResilienceFactory.circuitBreaker(p.circuitBreaker());
        return new InventoryGateway(new InventoryClient(rest), p, circuitBreaker,
                ResilienceFactory.retry(p.retry(), policy, backoff), ResilienceFactory.bulkhead(p.bulkhead()),
                new ManualRetryExecutor(p.retry(), policy, backoff), new SimpleMeterRegistry());
    }

    private InventoryGateway gateway(ResilienceProperties p) { return gateway(p, stub.baseUrl(), Duration.ofSeconds(2)); }

    private static ReservationRequest request() { return new ReservationRequest(UUID.randomUUID(), "P-0001", 1); }

    @Test
    void successfulCallReservesWithOneAttempt() {
        var outcome = gateway(TestResilience.props(RetryMode.RESILIENCE4J, 3, true, 10, true, 10)).reserve(request());
        assertThat(outcome.result()).isEqualTo(Result.RESERVED);
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void manualRetryRecoversFromTransient503s() {
        stub.enqueue(503, 503);
        var outcome = gateway(TestResilience.props(RetryMode.MANUAL, 3, false, 10, false, 10)).reserve(request());
        assertThat(outcome.result()).isEqualTo(Result.RESERVED);
        assertThat(outcome.attempts()).isEqualTo(3);
        assertThat(stub.requestCount()).isEqualTo(3);
    }

    @Test
    void resilience4jRetryRecoversFromTransient503s() {
        stub.enqueue(503, 502);
        var outcome = gateway(TestResilience.props(RetryMode.RESILIENCE4J, 3, true, 10, true, 10)).reserve(request());
        assertThat(outcome.result()).isEqualTo(Result.RESERVED);
        assertThat(outcome.attempts()).isEqualTo(3);
    }

    @Test
    void retriesAreExhaustedAndOutcomeIsUnknown() {
        stub.fallback(503, 0);
        var outcome = gateway(TestResilience.props(RetryMode.RESILIENCE4J, 3, false, 10, false, 10)).reserve(request());
        assertThat(outcome.result()).isEqualTo(Result.UNKNOWN);
        assertThat(stub.requestCount()).isEqualTo(3);
    }

    @Test
    void businessRejectionIsNeverRetried() {
        stub.enqueue(409);
        var outcome = gateway(TestResilience.props(RetryMode.MANUAL, 3, true, 10, true, 10)).reserve(request());
        assertThat(outcome.result()).isEqualTo(Result.REJECTED);
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void http500IsNotRetriedByDefaultAndOutcomeIsUnknown() {
        stub.enqueue(500);
        var outcome = gateway(TestResilience.props(RetryMode.MANUAL, 3, false, 10, false, 10)).reserve(request());
        assertThat(outcome.result()).isEqualTo(Result.UNKNOWN);
        assertThat(stub.requestCount()).isEqualTo(1);
    }

    @Test
    void responseTimeoutIsRetriedAndNeverAssumedUnprocessed() {
        stub.fallback(201, 800);
        var gw = gateway(TestResilience.props(RetryMode.MANUAL, 3, false, 10, false, 10), stub.baseUrl(),
                Duration.ofMillis(200));
        var outcome = gw.reserve(request());
        assertThat(outcome.result()).isEqualTo(Result.UNKNOWN);   // the server may well have processed it
        assertThat(outcome.attempts()).isEqualTo(3);
    }

    @Test
    void connectionRefusedIsRetried() {
        var outcome = gateway(TestResilience.props(RetryMode.MANUAL, 2, false, 10, false, 10), "http://127.0.0.1:1",
                Duration.ofSeconds(1)).reserve(request());
        assertThat(outcome.result()).isEqualTo(Result.UNKNOWN);
        assertThat(outcome.attempts()).isEqualTo(2);
    }

    @Test
    void circuitBreakerOpensAndStopsCallingInventory() {
        stub.fallback(503, 0);
        var gw = gateway(TestResilience.props(RetryMode.NONE, 1, true, 4, false, 10));
        for (int i = 0; i < 4; i++) {
            assertThat(gw.reserve(request()).result()).isEqualTo(Result.UNKNOWN);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        var rejected = gw.reserve(request());
        assertThat(rejected.result()).isEqualTo(Result.NOT_SENT);
        assertThat(rejected.attempts()).isZero();
        assertThat(stub.requestCount()).isEqualTo(4);
    }

    @Test
    void circuitBreakerIgnoresBusinessRejections() {
        stub.fallback(409, 0);
        var gw = gateway(TestResilience.props(RetryMode.NONE, 1, true, 4, false, 10));
        for (int i = 0; i < 10; i++) {
            assertThat(gw.reserve(request()).result()).isEqualTo(Result.REJECTED);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void bulkheadRejectsExcessConcurrentCalls() {
        stub.fallback(201, 500);
        var gw = gateway(TestResilience.props(RetryMode.NONE, 1, false, 10, true, 1));
        var a = CompletableFuture.supplyAsync(() -> gw.reserve(request()));
        var b = CompletableFuture.supplyAsync(() -> gw.reserve(request()));
        List<Result> results = List.of(a.join().result(), b.join().result());
        assertThat(results).containsExactlyInAnyOrder(Result.RESERVED, Result.NOT_SENT);
    }

    @Test
    void propagatesCorrelationId() {
        ThreadContext.put("correlation_id", "test-cid-123");
        try {
            gateway(TestResilience.props(RetryMode.NONE, 1, false, 10, false, 10)).reserve(request());
        } finally {
            ThreadContext.remove("correlation_id");
        }
        assertThat(stub.correlationIds()).containsExactly("test-cid-123");
    }
}
