package com.faultlab.order.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.faultlab.order.order.Order;
import com.faultlab.order.order.OrderExceptions.IdempotencyConflictException;
import com.faultlab.order.order.OrderExceptions.OrderNotFoundException;
import com.faultlab.order.order.OrderService;
import com.faultlab.order.order.OrderStatus;
import com.faultlab.order.order.PlaceOrderResult;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String BODY = "{\"productId\":\"P-0001\",\"quantity\":2}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    OrderService orders;

    private static Order order(OrderStatus status) {
        return new Order(UUID.randomUUID(), "k", "P-0001", 2, status, null, 1, OffsetDateTime.now(), OffsetDateTime.now());
    }

    private void givenResult(OrderStatus status, boolean replayed, int attempts) {
        when(orders.place(anyString(), anyString(), anyInt()))
                .thenReturn(new PlaceOrderResult(order(status), replayed, attempts));
    }

    @Test
    void confirmedOrderReturns201WithHeaders() throws Exception {
        givenResult(OrderStatus.CONFIRMED, false, 2);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "abc-1").header("X-Correlation-Id", "cid-1"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Downstream-Attempts", "2"))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(header().string("X-Correlation-Id", "cid-1"))
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        verify(orders).place(eq("abc-1"), eq("P-0001"), eq(2));
    }

    @Test
    void replayReturns200() throws Exception {
        givenResult(OrderStatus.CONFIRMED, true, 0);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY).header("Idempotency-Key", "k"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void statusMapping() throws Exception {
        givenResult(OrderStatus.REJECTED, false, 1);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
        givenResult(OrderStatus.RESERVATION_UNKNOWN, false, 3);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isAccepted());
        givenResult(OrderStatus.FAILED, false, 0);
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void invalidBodyReturns400WithoutCallingService() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("{\"productId\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
        verify(orders, never()).place(any(), any(), anyInt());
    }

    @Test
    void invalidIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "has spaces!"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void idempotencyConflictReturns422() throws Exception {
        when(orders.place(anyString(), anyString(), anyInt())).thenThrow(new IdempotencyConflictException("k"));
        mvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY).header("Idempotency-Key", "k"))
                .andExpect(status().is(422));
    }

    @Test
    void getUnknownOrderReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(orders.get(id)).thenThrow(new OrderNotFoundException(id));
        mvc.perform(get("/api/orders/" + id)).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders/not-a-uuid")).andExpect(status().isBadRequest());
    }
}
