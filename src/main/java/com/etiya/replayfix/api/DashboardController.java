package com.etiya.replayfix.api;

import com.etiya.replayfix.model.CaseListItemView;
import com.etiya.replayfix.model.IncidentDashboardView;
import com.etiya.replayfix.service.IncidentDashboardService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final IncidentDashboardService dashboardService;

    public DashboardController(IncidentDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/cases")
    public ResponseEntity<List<CaseListItemView>> listCases(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit
    ) {
        List<CaseListItemView> cases = dashboardService.listCases(query, status, limit);
        return ResponseEntity.ok(cases);
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<IncidentDashboardView> getCaseDashboard(@PathVariable UUID caseId) {
        try {
            IncidentDashboardView dashboard = dashboardService.getCaseDashboard(caseId);
            return ResponseEntity.ok(dashboard);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
