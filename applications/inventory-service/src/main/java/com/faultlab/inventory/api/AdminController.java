package com.faultlab.inventory.api;

import com.faultlab.inventory.api.ApiModels.FailureModeRequest;
import com.faultlab.inventory.api.ApiModels.FailureModeResponse;
import com.faultlab.inventory.db.DbOperations;
import com.faultlab.inventory.failure.FailureSettings;
import com.faultlab.inventory.failure.FailureSimulator;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lab-only control plane. Not meant to be exposed outside the lab network. */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final FailureSimulator failures;
    private final DbOperations db;

    public AdminController(FailureSimulator failures, DbOperations db) {
        this.failures = failures;
        this.db = db;
    }

    @GetMapping("/failure-mode")
    public FailureModeResponse current() { return response(failures.current()); }

    @PostMapping("/failure-mode")
    public FailureModeResponse setFailureMode(@Valid @RequestBody FailureModeRequest req) {
        FailureSettings cur = failures.current();
        FailureSettings next = new FailureSettings(req.mode(),
                req.slowLatencyMs() != null ? req.slowLatencyMs() : cur.slowLatencyMs(),
                req.failureRate() != null ? req.failureRate() : cur.failureRate(),
                req.baseLatencyMs() != null ? req.baseLatencyMs() : cur.baseLatencyMs());
        if (req.dbDelayMs() != null) {
            db.setArtificialDelay(Duration.ofMillis(req.dbDelayMs()));
        }
        return response(failures.update(next));
    }

    @PostMapping("/reset")
    public FailureModeResponse reset() {
        db.setArtificialDelay(Duration.ZERO);
        return response(failures.reset());
    }

    private FailureModeResponse response(FailureSettings s) {
        return new FailureModeResponse(s.mode(), s.slowLatencyMs(), s.failureRate(), s.baseLatencyMs(),
                db.artificialDelayMs());
    }
}
