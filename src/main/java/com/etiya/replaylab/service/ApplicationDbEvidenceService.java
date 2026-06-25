package com.etiya.replaylab.service;

import com.etiya.replaylab.config.ReplayLabProperties;
import com.etiya.replaylab.domain.ReplayCaseEntity;
import com.etiya.replaylab.model.ApplicationDbEvidenceFinding;
import com.etiya.replaylab.model.ApplicationDbEvidenceQueryResult;
import com.etiya.replaylab.model.ApplicationDbEvidenceQueryTemplate;
import com.etiya.replaylab.model.ApplicationDbEvidenceResponse;
import com.etiya.replaylab.model.FixPlanResponse;
import com.etiya.replaylab.model.SourceSuspectChangeAnalysisResponse;
import com.etiya.replaylab.repository.ReplayCaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApplicationDbEvidenceService {

    public static final String DB_EVIDENCE_DRY_RUN = "DB_EVIDENCE_DRY_RUN";
    public static final String APPLICATION_DB_DATASOURCE_NOT_CONFIGURED =
            "APPLICATION_DB_DATASOURCE_NOT_CONFIGURED";
    public static final String DB_EVIDENCE_EXECUTION_DISABLED =
            "DB_EVIDENCE_EXECUTION_DISABLED";
    public static final String DB_EVIDENCE_INPUTS_REQUIRED =
            "DB_EVIDENCE_INPUTS_REQUIRED";
    public static final String DB_EVIDENCE_QUERY_FAILED =
            "DB_EVIDENCE_QUERY_FAILED";

    private static final Logger log = LoggerFactory.getLogger(
            ApplicationDbEvidenceService.class
    );

    private final ReplayCaseRepository caseRepository;
    private final FixPlanService fixPlanService;
    private final SourceSuspectChangeAnalysisService sourceAnalysisService;
    private final ApplicationDbEvidenceQueryRegistry queryRegistry;
    private final SqlReadOnlyGuard sqlReadOnlyGuard;
    private final ReplayLabProperties properties;

    public ApplicationDbEvidenceService(
            ReplayCaseRepository caseRepository,
            FixPlanService fixPlanService,
            SourceSuspectChangeAnalysisService sourceAnalysisService,
            ApplicationDbEvidenceQueryRegistry queryRegistry,
            SqlReadOnlyGuard sqlReadOnlyGuard,
            ReplayLabProperties properties
    ) {
        this.caseRepository = caseRepository;
        this.fixPlanService = fixPlanService;
        this.sourceAnalysisService = sourceAnalysisService;
        this.queryRegistry = queryRegistry;
        this.sqlReadOnlyGuard = sqlReadOnlyGuard;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ApplicationDbEvidenceResponse collect(
            UUID caseId,
            String dataSourceKey,
            boolean execute,
            int maxRows
    ) {
        ReplayCaseEntity replayCase = caseRepository.findById(caseId)
                .orElseGet(() -> defaultCase(caseId));
        List<String> warnings = new ArrayList<>();
        FixPlanResponse fixPlan = fixPlanService.plan(caseId, false, 5);
        SourceSuspectChangeAnalysisResponse sourceAnalysis = sourceAnalysis(
                caseId,
                warnings
        );
        List<ApplicationDbEvidenceQueryTemplate> templates =
                queryRegistry.relevantTemplates(combinedSignals(
                        fixPlan,
                        sourceAnalysis
                ));

        DataSourceConfig config = dataSourceConfig(dataSourceKey);
        if (!config.configured()) {
            warnings.add(APPLICATION_DB_DATASOURCE_NOT_CONFIGURED);
        }
        if (!execute) {
            warnings.add(DB_EVIDENCE_DRY_RUN);
            return response(
                    replayCase,
                    dataSourceKey,
                    templates,
                    List.of(),
                    findings(templates, false),
                    warnings
            );
        }
        if (!properties.getDbEvidence().isEnabled()) {
            warnings.add(DB_EVIDENCE_EXECUTION_DISABLED);
            return response(
                    replayCase,
                    dataSourceKey,
                    templates,
                    List.of(),
                    findings(templates, false),
                    warnings
            );
        }
        if (!config.configured()) {
            return response(
                    replayCase,
                    dataSourceKey,
                    templates,
                    List.of(),
                    findings(templates, false),
                    warnings
            );
        }

        List<ApplicationDbEvidenceQueryResult> executed = executeQueries(
                templates,
                config,
                Math.max(1, maxRows),
                warnings
        );
        return response(
                replayCase,
                dataSourceKey,
                templates,
                executed,
                findings(templates, !executed.isEmpty()),
                warnings
        );
    }

    private SourceSuspectChangeAnalysisResponse sourceAnalysis(
            UUID caseId,
            List<String> warnings
    ) {
        try {
            return sourceAnalysisService.analyze(
                    caseId,
                    45,
                    20,
                    10,
                    false,
                    false
            );
        } catch (Exception exception) {
            log.warn(
                    "Application DB evidence source analysis failed for caseId={}",
                    caseId,
                    exception
            );
            warnings.add(SourceSuspectChangeAnalysisService
                    .SOURCE_CHANGE_ANALYSIS_FAILED);
            return null;
        }
    }

    private List<ApplicationDbEvidenceQueryResult> executeQueries(
            List<ApplicationDbEvidenceQueryTemplate> templates,
            DataSourceConfig config,
            int maxRows,
            List<String> warnings
    ) {
        List<ApplicationDbEvidenceQueryResult> results = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                config.url(),
                config.username(),
                config.password()
        )) {
            connection.setReadOnly(true);
            if (!config.schema().isBlank()) {
                connection.setSchema(config.schema());
            }
            for (ApplicationDbEvidenceQueryTemplate template : templates) {
                if (!template.requiredInputs().isEmpty()) {
                    warnings.add(DB_EVIDENCE_INPUTS_REQUIRED
                            + ":" + template.templateId());
                    continue;
                }
                sqlReadOnlyGuard.validateSelectOnly(template.sqlPreview());
                try (Statement statement = connection.createStatement()) {
                    statement.setMaxRows(maxRows);
                    try (ResultSet resultSet = statement.executeQuery(
                            template.sqlPreview()
                    )) {
                        List<Map<String, Object>> rows = rows(
                                resultSet,
                                template,
                                maxRows
                        );
                        results.add(new ApplicationDbEvidenceQueryResult(
                                template.templateId(),
                                template.sqlPreview(),
                                rows.size(),
                                rows,
                                true,
                                List.of()
                        ));
                    }
                }
            }
        } catch (Exception exception) {
            log.warn(
                    "Application DB evidence query execution failed",
                    exception
            );
            warnings.add(DB_EVIDENCE_QUERY_FAILED);
        }
        return results;
    }

    private List<Map<String, Object>> rows(
            ResultSet resultSet,
            ApplicationDbEvidenceQueryTemplate template,
            int maxRows
    ) throws Exception {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<Map<String, Object>> values = new ArrayList<>();
        while (resultSet.next() && values.size() < maxRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= metaData.getColumnCount(); index++) {
                String column = metaData.getColumnLabel(index);
                Object value = resultSet.getObject(index);
                row.put(column, maskedValue(column, value, template));
            }
            values.add(row);
        }
        return values;
    }

    private Object maskedValue(
            String column,
            Object value,
            ApplicationDbEvidenceQueryTemplate template
    ) {
        if (value == null) {
            return null;
        }
        String normalizedColumn = safeString(column).toLowerCase(Locale.ROOT);
        boolean masked = template.maskedFields()
                .stream()
                .map(field -> field.toLowerCase(Locale.ROOT))
                .anyMatch(normalizedColumn::equals);
        return masked ? "[MASKED]" : value;
    }

    private ApplicationDbEvidenceResponse response(
            ReplayCaseEntity replayCase,
            String dataSourceKey,
            List<ApplicationDbEvidenceQueryTemplate> templates,
            List<ApplicationDbEvidenceQueryResult> executed,
            List<ApplicationDbEvidenceFinding> findings,
            List<String> warnings
    ) {
        return new ApplicationDbEvidenceResponse(
                replayCase.getId(),
                safeString(replayCase.getJiraKey()),
                "HYPOTHESIS",
                normalizedDataSourceKey(dataSourceKey),
                true,
                templates,
                executed,
                findings,
                true,
                unique(warnings)
        );
    }

    private List<ApplicationDbEvidenceFinding> findings(
            List<ApplicationDbEvidenceQueryTemplate> templates,
            boolean executed
    ) {
        return templates.stream()
                .filter(template -> !ApplicationDbEvidenceQueryRegistry.UNKNOWN
                        .equals(template.templateId()))
                .map(template -> new ApplicationDbEvidenceFinding(
                        "QUERY_TEMPLATE",
                        template.templateId(),
                        executed
                                ? "Read-only DB evidence query executed."
                                : "Read-only DB evidence query template selected.",
                        "HYPOTHESIS",
                        executed ? 0.5 : 0.25,
                        template.requiredInputs()
                ))
                .toList();
    }

    private String combinedSignals(
            FixPlanResponse fixPlan,
            SourceSuspectChangeAnalysisResponse sourceAnalysis
    ) {
        StringBuilder builder = new StringBuilder();
        if (fixPlan != null) {
            builder.append(' ').append(fixPlan.missingEvidence());
            fixPlan.fixCandidates().forEach(candidate -> {
                builder.append(' ').append(candidate.fixType());
                builder.append(' ').append(candidate.targetClass());
                builder.append(' ').append(candidate.targetMethod());
                builder.append(' ').append(candidate.relatedFlow());
                candidate.relatedSignals()
                        .forEach(signal -> builder.append(' ').append(signal));
            });
        }
        if (sourceAnalysis != null) {
            sourceAnalysis.flowAnchors()
                    .forEach(anchor -> builder.append(' ').append(anchor.value()));
            sourceAnalysis.matchedEndpointAnchors()
                    .forEach(value -> builder.append(' ').append(value));
            sourceAnalysis.candidateFlowChain().forEach(item -> {
                builder.append(' ').append(item.className());
                builder.append(' ').append(item.methodName());
                builder.append(' ').append(item.relatedSignals());
            });
            sourceAnalysis.candidateMethods().forEach(method -> {
                builder.append(' ').append(method.className());
                builder.append(' ').append(method.methodName());
                builder.append(' ').append(method.snippet());
            });
        }
        return builder.toString();
    }

    private DataSourceConfig dataSourceConfig(String dataSourceKey) {
        if (!"backend".equalsIgnoreCase(normalizedDataSourceKey(dataSourceKey))) {
            return new DataSourceConfig("", "", "", "");
        }
        ReplayLabProperties.DbEvidenceDataSource backend =
                properties.getDbEvidence().getBackend();
        return new DataSourceConfig(
                firstNonBlank(
                        backend.getUrl(),
                        System.getenv("REPLAYLAB_DB_BACKEND_URL")
                ),
                firstNonBlank(
                        backend.getUsername(),
                        System.getenv("REPLAYLAB_DB_BACKEND_USERNAME")
                ),
                firstNonBlank(
                        backend.getPassword(),
                        System.getenv("REPLAYLAB_DB_BACKEND_PASSWORD")
                ),
                firstNonBlank(
                        backend.getSchema(),
                        System.getenv("REPLAYLAB_DB_BACKEND_SCHEMA")
                )
        );
    }

    private ReplayCaseEntity defaultCase(UUID caseId) {
        ReplayCaseEntity replayCase = new ReplayCaseEntity();
        replayCase.setId(caseId);
        replayCase.setJiraKey("");
        return replayCase;
    }

    private String normalizedDataSourceKey(String value) {
        return value == null || value.isBlank() ? "backend" : value;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private List<String> unique(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private record DataSourceConfig(
            String url,
            String username,
            String password,
            String schema
    ) {
        private boolean configured() {
            return url != null && !url.isBlank();
        }
    }
}
