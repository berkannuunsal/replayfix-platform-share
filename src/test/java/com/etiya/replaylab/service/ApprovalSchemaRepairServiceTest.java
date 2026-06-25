package com.etiya.replaylab.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalSchemaRepairServiceTest {

    @Test
    void shouldAllowFailingRegressionTestDraftAfterRepairingH2Constraint() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:"
                        + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        );

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute(
                """
                        create table rf_approval_request (
                            id uuid primary key,
                            target_type varchar(80) not null,
                            constraint old_target_type_check check (
                                target_type in (
                                    'DEPLOYMENT',
                                    'GENERATED_PATCH',
                                    'GENERATED_TEST_EXECUTION',
                                    'GENERATED_TEST_WRITE',
                                    'PATTERN_INFORMED_TEST_SOURCE',
                                    'PULL_REQUEST',
                                    'REGRESSION_TEST_PLAN'
                                )
                            )
                        )
                        """
        );

        ApprovalSchemaRepairService service =
                new ApprovalSchemaRepairService(jdbcTemplate, dataSource);

        service.run(null);

        assertDoesNotThrow(() -> jdbcTemplate.update(
                "insert into rf_approval_request (id, target_type) values (?, ?)",
                UUID.randomUUID(),
                "FAILING_REGRESSION_TEST_DRAFT"
        ));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "insert into rf_approval_request (id, target_type) values (?, ?)",
                        UUID.randomUUID(),
                        "NOT_A_VALID_APPROVAL_TYPE"
                )
        );
    }
}
