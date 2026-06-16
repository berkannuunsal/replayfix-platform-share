package com.etiya.replayfix.api;

import com.etiya.replayfix.model.DemoScenarioResult;
import com.etiya.replayfix.service.ReplayFixDemoScenarioSeeder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/demo/scenarios")
@Profile("demo")
@ConditionalOnProperty(
        prefix = "replayfix.demo",
        name = "enabled",
        havingValue = "true"
)
public class ReplayFixDemoScenarioController {

    private final ReplayFixDemoScenarioSeeder seeder;

    public ReplayFixDemoScenarioController(ReplayFixDemoScenarioSeeder seeder) {
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
