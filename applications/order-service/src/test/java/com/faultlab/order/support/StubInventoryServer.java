package com.faultlab.order.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real HTTP server (JDK built-in) standing in for inventory-service, with scripted responses. */
public final class StubInventoryServer implements AutoCloseable {

    public record Response(int status, long delayMs) { }

    private static final Pattern ORDER_ID = Pattern.compile("\"orderId\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Deque<Response> script = new ConcurrentLinkedDeque<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> correlationIds = new CopyOnWriteArrayList<>();
    private volatile Response fallback = new Response(201, 0);

    private StubInventoryServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/api/inventory/reservations", this::handle);
        server.start();
    }

    public static StubInventoryServer start() {
        try {
            return new StubInventoryServer();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public int requestCount() { return requests.get(); }

    public List<String> correlationIds() { return correlationIds; }

    public StubInventoryServer enqueue(int... statuses) {
        for (int s : statuses) {
            script.addLast(new Response(s, 0));
        }
        return this;
    }

    public StubInventoryServer fallback(int status, long delayMs) {
        fallback = new Response(status, delayMs);
        return this;
    }

    public void reset() {
        script.clear();
        requests.set(0);
        correlationIds.clear();
        fallback = new Response(201, 0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            requests.incrementAndGet();
            String cid = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            if (cid != null) {
                correlationIds.add(cid);
            }
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response r = script.pollFirst();
            if (r == null) {
                r = fallback;
            }
            if (r.delayMs() > 0) {
                try {
                    Thread.sleep(r.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String body = r.status() < 300 ? success(requestBody) : "{\"detail\":\"stub status " + r.status() + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(r.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException clientGone) {
                // the client timed out and closed the connection: expected in timeout tests
            }
        } finally {
            exchange.close();
        }
    }

    private static String success(String requestBody) {
        Matcher m = ORDER_ID.matcher(requestBody);
        String orderId = m.find() ? m.group(1) : UUID.randomUUID().toString();
        return """
                {"reservationId":"%s","orderId":"%s","productId":"P-0001","quantity":1,"status":"RESERVED","replayed":false}"""
                .formatted(UUID.randomUUID(), orderId);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
