package com.faultlab.order.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/** Exactly one HTTP attempt. Retries, circuit breaking and bulkheading are layered on top by the gateway. */
public class InventoryClient {

    public static final String RESERVATIONS_PATH = "/api/inventory/reservations";

    private final RestClient restClient;

    public InventoryClient(RestClient restClient) { this.restClient = restClient; }

    /**
     * @throws DownstreamRejectedException on 4xx other than 429 (definitive answer)
     * @throws DownstreamHttpException     on 5xx or 429
     * @throws org.springframework.web.client.ResourceAccessException on connect/response timeouts and I/O errors
     */
    public ReservationResponse reserve(ReservationRequest request) {
        return restClient.post()
                .uri(RESERVATIONS_PATH)       // constant URI keeps http.client.requests low-cardinality
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", request.orderId().toString())
                .body(request)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    int code = res.getStatusCode().value();
                    String body = readBody(res);
                    if (code == 429 || code >= 500) {
                        throw new DownstreamHttpException(code, body);
                    }
                    throw new DownstreamRejectedException(code, body);
                })
                .body(ReservationResponse.class);
    }

    private static String readBody(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            byte[] bytes = in.readNBytes(500);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
