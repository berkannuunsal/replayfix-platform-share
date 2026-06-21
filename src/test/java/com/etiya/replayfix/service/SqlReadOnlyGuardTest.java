package com.etiya.replayfix.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlReadOnlyGuardTest {

    private final SqlReadOnlyGuard guard = new SqlReadOnlyGuard();

    @Test
    void acceptsSelect() {
        assertThatCode(() -> guard.validateSelectOnly(
                "select user_id from apl_user where user_id = :userId"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsMutatingStatements() {
        assertThatThrownBy(() -> guard.validateSelectOnly("insert into t values (1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.validateSelectOnly("update t set a = 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.validateSelectOnly("delete from t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.validateSelectOnly("drop table t"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guard.validateSelectOnly("alter table t add c int"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMultipleStatements() {
        assertThatThrownBy(() -> guard.validateSelectOnly(
                "select * from t; select * from u"
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
