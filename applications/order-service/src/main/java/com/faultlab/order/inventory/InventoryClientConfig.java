package com.faultlab.order.inventory;

import com.faultlab.order.web.CorrelationIdFilter;
import java.net.http.HttpClient;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class InventoryClientConfig {

    /**
     * Built from Spring Boot's RestClient.Builder so Micrometer observations (http.client.requests) are applied.
     * Connect timeout: TCP connect. Response timeout: time to receive the response after sending the request.
     * W3C trace context headers are injected by the OpenTelemetry agent's JDK HttpClient instrumentation.
     */
    @Bean
    RestClient inventoryRestClient(RestClient.Builder builder, InventoryClientProperties props) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(props.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.responseTimeout());
        return builder.clone()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = ThreadContext.get(CorrelationIdFilter.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    @Bean
    InventoryClient inventoryClient(RestClient inventoryRestClient) {
        return new InventoryClient(inventoryRestClient);
    }
}
