package com.etiya.replaylab.api;

import com.etiya.replaylab.model.ApplicationDbEvidenceResponse;
import com.etiya.replaylab.service.ApplicationDbEvidenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cases")
public class ApplicationDbEvidenceController {

    private static final Logger log = LoggerFactory.getLogger(
            ApplicationDbEvidenceController.class
    );

    private final ApplicationDbEvidenceService service;

    public ApplicationDbEvidenceController(ApplicationDbEvidenceService service) {
        this.service = service;
    }

    @GetMapping("/{caseId}/db-evidence")
    public ApplicationDbEvidenceResponse collect(
            @PathVariable UUID caseId,
            @RequestParam(defaultValue = "backend") String dataSourceKey,
            @RequestParam(defaultValue = "false") boolean execute,
            @RequestParam(defaultValue = "20") int maxRows
    ) {
        try {
            return service.collect(
                    caseId,
                    dataSourceKey,
                    execute,
                    Math.max(1, maxRows)
            );
        } catch (Exception exception) {
            log.warn(
                    "Application DB evidence endpoint fallback caseId={} exceptionClass={} exceptionMessage={}",
                    caseId,
                    exception.getClass().getName(),
                    exception.getMessage(),
                    exception
            );
            return new ApplicationDbEvidenceResponse(
                    caseId,
                    "",
                    "HYPOTHESIS",
                    dataSourceKey,
                    true,
                    List.of(),
                    List.of(),
                    List.of(),
                    true,
                    List.of(ApplicationDbEvidenceService
                            .APPLICATION_DB_DATASOURCE_NOT_CONFIGURED)
            );
        }
    }
}
