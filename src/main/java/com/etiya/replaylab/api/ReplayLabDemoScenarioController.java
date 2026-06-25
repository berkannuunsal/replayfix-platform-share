package com.etiya.replaylab.api;

import com.etiya.replaylab.model.DemoScenarioResult;
import com.etiya.replaylab.service.ReplayLabDemoScenarioSeeder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/demo/scenarios")
@Profile("demo")
@ConditionalOnProperty(
        prefix = "replaylab.demo",
        name = "enabled",
        havingValue = "true"
)
public class ReplayLabDemoScenarioController {

    private final ReplayLabDemoScenarioSeeder seeder;

    public ReplayLabDemoScenarioController(ReplayLabDemoScenarioSeeder seeder) {
        this.seeder = seeder;
    }

    @PostMapping("/http-401")
    public ResponseEntity<DemoScenarioResult> createHttp401Scenario() {
        DemoScenarioResult result = seeder.seedHttp401Scenario();
        return ResponseEntity.ok(result);
    }

    @DeleteMapping("/{caseId}")
    public ResponseEntity<Void> deleteScenario(@PathVariable UUID caseId) {
        seeder.deleteScenario(caseId);
        return ResponseEntity.noContent().build();
    }
}
