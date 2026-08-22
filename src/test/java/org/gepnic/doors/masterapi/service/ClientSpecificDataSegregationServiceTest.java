package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.entity.ClientQueryMap;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientSpecificDataSegregationServiceTest {

    private final ClientQueryMapRepository mappingRepository = mock(ClientQueryMapRepository.class);
    private final SqlTemplateRepository templateRepository = mock(SqlTemplateRepository.class);
    private ClientSpecificDataSegregationService service;
    private ApiClient client;

    @BeforeEach
    void setUp() {
        service = new ClientSpecificDataSegregationService(
                mappingRepository, templateRepository, new ObjectMapper());
        client = new ApiClient();
        client.setClientId(10L);

        SqlTemplate template = new SqlTemplate();
        template.setQueryId(20L);
        when(templateRepository.findByUniqueName("SASCI-FinEvalSummary"))
                .thenReturn(Optional.of(template));
    }

    @Test
    void filtersNestedJsonRecordsUsingAuthenticatedClientPolicy() {
        configurePolicy("t_ORGID", "623");
        List<Map<String, Object>> rows = List.of(envelope(List.of(
                record(623, "Tripura"),
                record(810, "Assam"),
                record(null, "Missing organization"))));

        List<Map<String, Object>> filtered = service.apply(
                client, "SASCI-FinEvalSummary", rows);

        List<?> data = (List<?>) ((Map<?, ?>) filtered.get(0).get("value")).get("DATA");
        assertEquals(1, data.size());
        assertEquals("Tripura", ((Map<?, ?>) data.get(0)).get("t_ORGNAME"));
    }

    @Test
    void treatsNumericAndConfiguredStringValuesAsEquivalent() {
        configurePolicy("t_ORGID", "623.0");

        List<Map<String, Object>> filtered = service.apply(
                client, "SASCI-FinEvalSummary", List.of(envelope(List.of(record(623, "Tripura")))));

        assertEquals(1, filtered.size());
    }

    @Test
    void supportsAnotherSafelyNamedJsonFieldEnteredByTheAdministrator() {
        configurePolicy("t_ORGNAME", "Tripura");

        List<Map<String, Object>> filtered = service.apply(
                client, "SASCI-FinEvalSummary", List.of(envelope(List.of(
                        record(623, "Tripura"), record(810, "Assam")))));

        List<?> data = (List<?>) ((Map<?, ?>) filtered.get(0).get("value")).get("DATA");
        assertEquals(1, data.size());
        assertEquals(623, ((Map<?, ?>) data.get(0)).get("t_ORGID"));
    }

    @Test
    void failsClosedWhenConfiguredFieldDoesNotExistInResponse() {
        configurePolicy("t_ORGID", "623");

        List<Map<String, Object>> filtered = service.apply(
                client, "SASCI-FinEvalSummary", List.of(envelope(List.of(Map.of("name", "row")))));

        assertTrue(filtered.isEmpty());
    }

    @Test
    void rejectsInvalidStoredPolicyInsteadOfReturningUnfilteredData() {
        configurePolicy("t_ORGID\"]", "623");

        assertThrows(SecurityException.class, () -> service.apply(
                client, "SASCI-FinEvalSummary", List.of(envelope(List.of(record(623, "Tripura"))))));
    }

    @Test
    void preservesExistingBehaviorWhenNoPolicyIsConfigured() {
        configurePolicy(null, null);
        List<Map<String, Object>> original = List.of(envelope(List.of(record(623, "Tripura"))));

        assertSame(original, service.apply(client, "SASCI-FinEvalSummary", original));
    }

    private void configurePolicy(String column, String value) {
        ClientQueryMap mapping = new ClientQueryMap();
        mapping.setClientId(10L);
        mapping.setQueryId(20L);
        mapping.setResponseFilterColumn(column);
        mapping.setResponseFilterValue(value);
        when(mappingRepository.findByClientIdAndQueryId(10L, 20L)).thenReturn(Optional.of(mapping));
    }

    private Map<String, Object> envelope(List<Map<String, Object>> data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("DATA", data);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("GePNIC_Instance_ID", "GePNIC-Tripura");
        envelope.put("type", "json");
        envelope.put("value", value);
        return envelope;
    }

    private Map<String, Object> record(Integer orgId, String orgName) {
        Map<String, Object> record = new LinkedHashMap<>();
        if (orgId != null) record.put("t_ORGID", orgId);
        record.put("t_ORGNAME", orgName);
        return record;
    }
}
