package org.gepnic.doors.masterapi.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlInputParameterExtractorTest {
    @Test
    void extractsOnlyDoorsInputsInFirstSeenOrder() {
        String sql = """
                SELECT to_char(opening_date, 'HH:MI') AS opening_time,
                       ':p_fake' AS literal,
                       tender_id::text
                FROM tenders
                WHERE tender_id = :p_tenderid
                  AND organisation = :p_org
                  AND tender_id = :p_tenderid
                -- :p_comment
                /* :p_block */
                """;

        assertThat(SqlInputParameterExtractor.extract(sql))
                .containsExactly("p_tenderid", "p_org");
    }
}
