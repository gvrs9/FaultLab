package com.faultlab.order.api;

import com.faultlab.order.api.ApiModels.DbDelayRequest;
import com.faultlab.order.api.ApiModels.DbDelayResponse;
import com.faultlab.order.db.DbOperations;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lab-only: change order-service's artificial DB delay at runtime (HikariCP experiments). */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DbOperations db;

    public AdminController(DbOperations db) { this.db = db; }

    @GetMapping("/db-delay")
    public DbDelayResponse current() { return new DbDelayResponse(db.artificialDelayMs()); }

    @PostMapping("/db-delay")
    public DbDelayResponse set(@Valid @RequestBody DbDelayRequest request) {
        return new DbDelayResponse(db.setArtificialDelay(Duration.ofMillis(request.delayMs())));
    }

    @PostMapping("/reset")
    public DbDelayResponse reset() { return new DbDelayResponse(db.setArtificialDelay(Duration.ZERO)); }
}
