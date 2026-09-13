package org.gepnic.doors.masterapi.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import java.util.List;

/** Author-owned input only. Legacy governance properties are never persisted. */
@Data
public class TemplateProposalRequest {
    @Null private Long queryId;
    @NotBlank @Size(min = 3, max = 100) private String uniqueName;
    @NotBlank @Size(max = 150000) private String sqlText;
    @Size(max = 500) private String description;
    @Size(max = 50) private String category;
    @Size(max = 100) private String subcategory;
    @Size(max = 100) private String defaultAgentId;
    @Positive private Long requestId;
    @Pattern(regexp = "(?i)REQUEST|INTERNAL") private String submissionSource;
    @Size(max = 100) private List<@Pattern(regexp = "[A-Za-z_][A-Za-z0-9_]{0,99}") String> parameters;

    public SqlTemplate toNewEntity() {
        if (queryId != null) throw new IllegalArgumentException("A proposal cannot contain a query ID");
        SqlTemplate value = new SqlTemplate();
        value.setUniqueName(uniqueName);
        value.setSqlText(sqlText);
        value.setDescription(description);
        value.setCategory(category);
        value.setSubcategory(subcategory);
        value.setDefaultAgentId(defaultAgentId);
        value.setRequestId(requestId);
        value.setSubmissionSource(submissionSource);
        value.setParameters(parameters);
        return value;
    }
}
