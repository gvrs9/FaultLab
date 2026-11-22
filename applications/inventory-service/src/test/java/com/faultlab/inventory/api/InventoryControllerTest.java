package com.faultlab.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.faultlab.inventory.db.DbOperations;
import com.faultlab.inventory.failure.FailureMode;
import com.faultlab.inventory.failure.FailureSettings;
import com.faultlab.inventory.failure.FailureSimulator;
import com.faultlab.inventory.failure.SimulatedFailureException;
import com.faultlab.inventory.inventory.InventoryExceptions.InsufficientStockException;
import com.faultlab.inventory.inventory.InventoryExceptions.ProductNotFoundException;
import com.faultlab.inventory.inventory.Reservation;
import com.faultlab.inventory.inventory.ReservationResult;
import com.faultlab.inventory.inventory.ReservationService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({InventoryController.class, AdminController.class})
class InventoryControllerTest {

    private static final UUID ORDER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BODY = "{\"orderId\":\"" + ORDER + "\",\"productId\":\"P-0001\",\"quantity\":1}";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ReservationService service;

    @MockitoBean
    FailureSimulator failures;

    @MockitoBean
    DbOperations db;

    private static ReservationResult result(boolean replayed) {
        return new ReservationResult(new Reservation(UUID.randomUUID(), ORDER.toString(), ORDER, "P-0001", 1,
                "RESERVED", OffsetDateTime.now()), replayed);
    }

    @Test
    void newReservationReturns201AndUsesOrderIdAsDefaultKey() throws Exception {
        when(service.reserve(anyString(), any(), anyString(), anyInt())).thenReturn(result(false));
        mvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false));
        verify(service).reserve(eq(ORDER.toString()), eq(ORDER), eq("P-0001"), eq(1));
    }

    @Test
    void replayReturns200() throws Exception {
        when(service.reserve(anyString(), any(), anyString(), anyInt())).thenReturn(result(true));
        mvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "custom-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        verify(service).reserve(eq("custom-key"), eq(ORDER), eq("P-0001"), eq(1));
    }

    @Test
    void invalidRequestReturns400() throws Exception {
        mvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"P-0001\",\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).reserve(any(), any(), any(), anyInt());
    }

    @Test
    void injectedFailureMapsToItsStatus() throws Exception {
        doThrow(new SimulatedFailureException(503, "boom")).when(failures).beforeProcessing(anyString());
        mvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable());
        verify(service, never()).reserve(any(), any(), any(), anyInt());
    }

    @Test
    void businessErrorsMapTo404And409() throws Exception {
        when(service.reserve(anyString(), any(), anyString(), anyInt()))
                .thenThrow(new InsufficientStockException("P-0001", 1));
        mvc.perform(post("/api/inventory/reservations").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
        when(service.product("NOPE")).thenThrow(new ProductNotFoundException("NOPE"));
        mvc.perform(get("/api/inventory/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void adminSetsFailureMode() throws Exception {
        when(failures.current()).thenReturn(new FailureSettings(FailureMode.NORMAL, 5000, 0.3, 0));
        when(failures.update(any())).thenAnswer(inv -> inv.getArgument(0));
        mvc.perform(post("/admin/failure-mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"RANDOM_FAILURE\",\"failureRate\":0.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RANDOM_FAILURE"))
                .andExpect(jsonPath("$.failureRate").value(0.5));
    }

    @Test
    void adminRejectsUnknownModeAndInvalidValues() throws Exception {
        mvc.perform(post("/admin/failure-mode").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"EXPLODE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/failure-mode").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"RANDOM_FAILURE\",\"failureRate\":2}"))
                .andExpect(status().isBadRequest());
    }
}
