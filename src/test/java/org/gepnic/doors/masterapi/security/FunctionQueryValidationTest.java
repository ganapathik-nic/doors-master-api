package org.gepnic.doors.masterapi.security;

import org.gepnic.doors.masterapi.util.SqlSecurityValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class FunctionQueryValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT fn_get_tender_sasci_aoc_summary_json(cast(:p_AOC_Summary_Date as DATE))",
            "SELECT public.fn_summary(CAST(:date AS DATE)) AS result",
            "SELECT FN_SUMMARY()",
            "SELECT fn_summary('2026-09-01', 1, NULL)"
    })
    void acceptsFunctionTemplates(String sql) {
        assertThat(SqlSecurityValidator.isSafeSelectOnly(sql)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT other_summary(:date)",
            "SELECT fn_summary(); DELETE FROM tenders",
            "SELECT fn_summary(pg_sleep(1))",
            "SELECT fn_summary(other_function())",
            "SELECT fn_summary((SELECT secret FROM credentials))",
            "SELECT fn_summary(), 'extra'",
            "SELECT fn_summary() UNION SELECT 1",
            "SELECT fn_summary() WHERE other_function() = 1",
            "SELECT fn_summary() ORDER BY other_function()",
            "SELECT fn_summary() INTO exported",
            "DELETE FROM fn_summary"
    })
    void rejectsQueriesOutsideFunctionAllowance(String sql) {
        assertThat(SqlSecurityValidator.isSafeSelectOnly(sql)).isFalse();
    }
}
