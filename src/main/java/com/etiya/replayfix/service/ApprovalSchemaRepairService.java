package com.etiya.replayfix.service;

import com.etiya.replayfix.domain.ApprovalTargetType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class ApprovalSchemaRepairService implements ApplicationRunner {

    private static final Logger log =
            LoggerFactory.getLogger(ApprovalSchemaRepairService.class);

    private static final String TABLE_NAME = "rf_approval_request";
    private static final String TARGET_TYPE_COLUMN = "target_type";
    private static final String TARGET_TYPE_CONSTRAINT =
            "chk_rf_approval_request_target_type";

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public ApprovalSchemaRepairService(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!isH2()) {
            return;
        }

        if (!tableExists()) {
            return;
        }

        repairTargetTypeConstraint();
    }

    private boolean isH2() {
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection
                    .getMetaData()
                    .getDatabaseProductName();
            return productName != null
                    && productName.toLowerCase(Locale.ROOT).contains("h2");
        } catch (Exception exception) {
            log.debug(
                    "Cannot inspect database product for approval schema repair: {}",
                    exception.getMessage()
            );
            return false;
        }
    }

    private boolean tableExists() {
        Integer count = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.tables
                        where lower(table_name) = ?
                        """,
                Integer.class,
                TABLE_NAME
        );
        return count != null && count > 0;
    }

    private void repairTargetTypeConstraint() {
        List<String> existingTargetTypeConstraints =
                findTargetTypeCheckConstraints();

        boolean alreadyCurrent = existingTargetTypeConstraints.stream()
                .anyMatch(this::constraintContainsAllTargetTypes);

        if (alreadyCurrent) {
            return;
        }

        for (String constraintName : existingTargetTypeConstraints) {
            jdbcTemplate.execute(
                    "alter table "
                            + TABLE_NAME
                            + " drop constraint "
                            + constraintName
            );
        }

        if (!constraintExists(TARGET_TYPE_CONSTRAINT)) {
            jdbcTemplate.execute(
                    "alter table "
                            + TABLE_NAME
                            + " add constraint "
                            + TARGET_TYPE_CONSTRAINT
                            + " check ("
                            + TARGET_TYPE_COLUMN
                            + " in ("
                            + allowedTargetTypeSql()
                            + "))"
            );
        }
    }

    private List<String> findTargetTypeCheckConstraints() {
        return jdbcTemplate.query(
                """
                        select tc.constraint_name
                        from information_schema.table_constraints tc
                        join information_schema.check_constraints cc
                          on tc.constraint_catalog = cc.constraint_catalog
                         and tc.constraint_schema = cc.constraint_schema
                         and tc.constraint_name = cc.constraint_name
                        where lower(tc.table_name) = ?
                          and lower(cc.check_clause) like ?
                        """,
                (rs, rowNum) -> rs.getString("constraint_name"),
                TABLE_NAME,
                "%" + TARGET_TYPE_COLUMN + "%"
        );
    }

    private boolean constraintContainsAllTargetTypes(String constraintName) {
        String checkClause = jdbcTemplate.queryForObject(
                """
                        select cc.check_clause
                        from information_schema.table_constraints tc
                        join information_schema.check_constraints cc
                          on tc.constraint_catalog = cc.constraint_catalog
                         and tc.constraint_schema = cc.constraint_schema
                         and tc.constraint_name = cc.constraint_name
                        where lower(tc.table_name) = ?
                          and tc.constraint_name = ?
                        """,
                String.class,
                TABLE_NAME,
                constraintName
        );

        if (checkClause == null) {
            return false;
        }

        return Arrays.stream(ApprovalTargetType.values())
                .map(Enum::name)
                .allMatch(checkClause::contains);
    }

    private boolean constraintExists(String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        select count(*)
                        from information_schema.table_constraints
                        where lower(table_name) = ?
                          and constraint_name = ?
                        """,
                Integer.class,
                TABLE_NAME,
                constraintName
        );
        return count != null && count > 0;
    }

    private String allowedTargetTypeSql() {
        return Arrays.stream(ApprovalTargetType.values())
                .map(Enum::name)
                .map(value -> "'" + value + "'")
                .collect(Collectors.joining(", "));
    }
}
