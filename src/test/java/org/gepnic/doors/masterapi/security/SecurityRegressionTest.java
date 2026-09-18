package org.gepnic.doors.masterapi.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.controller.*;
import org.gepnic.doors.masterapi.dto.*;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.*;
import org.gepnic.doors.masterapi.repository.*;
import org.gepnic.doors.masterapi.service.*;
import org.gepnic.doors.masterapi.util.SqlSecurityValidator;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SecurityRegressionTest {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void proposalNeverMapsGovernanceFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().configure(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        TemplateProposalRequest request = mapper.readValue("""
                {"uniqueName":"safe_query","sqlText":"SELECT 1","approverId":"admin",
                 "authorizedAgents":["production"],"status":"APPROVED","isActive":false}
                """, TemplateProposalRequest.class);
        var entity = request.toNewEntity();
        assertThat(entity.getQueryId()).isNull();
        assertThat(entity.getApproverId()).isNull();
        assertThat(entity.getAuthorizedAgents()).isEmpty();
        assertThat(entity.getStatus()).isEqualTo("PENDING");
    }

    @Test void suppliedEntityIdCannotBeConverted() {
        var request = new TemplateProposalRequest(); request.setQueryId(42L);
        assertThatThrownBy(request::toNewEntity).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void managerCannotDemoteSecurityAdmin() {
        var users = mock(UserRepository.class);
        var target = new User(); target.setRole("SecurityAdmin");
        when(users.findById(42)).thenReturn(Optional.of(target));
        var controller = new AdminUserController(users, mock(PasswordEncoder.class),
                mock(SecurityAuditService.class), mock(ApiClientRepository.class));
        var result = controller.updateRole(42, Map.of("role", "External"), auth("manager", "DATAMANAGER"));
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(target.getRole()).isEqualTo("SecurityAdmin");
        verify(users, never()).save(any());
    }

    @Test void securityAdminCanPromoteStandardAccountWithoutActivatingIt() {
        var users = mock(UserRepository.class);
        var target = new User(); target.setRole("External");
        when(users.findById(42)).thenReturn(Optional.of(target));
        var controller = new AdminUserController(users, mock(PasswordEncoder.class),
                mock(SecurityAuditService.class), mock(ApiClientRepository.class));
        assertThat(controller.updateRole(42, Map.of("role", "DataManager"), auth("security", "SECURITYADMIN"))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(target.getIsActive()).isFalse();
        assertThat(target.getStatus()).isEqualTo("PENDING_SECURITY_APPROVAL");
        verify(users).save(target);
    }

    @Test void managerCanChangeStandardRole() {
        var users = mock(UserRepository.class);
        var target = new User(); target.setRole("External");
        when(users.findById(42)).thenReturn(Optional.of(target));
        var controller = new AdminUserController(users, mock(PasswordEncoder.class),
                mock(SecurityAuditService.class), mock(ApiClientRepository.class));
        assertThat(controller.updateRole(42, Map.of("role", "Developer"), auth("manager", "DATAMANAGER"))
                .getStatusCode().value()).isEqualTo(200);
        verify(users).save(target);
    }

    @ParameterizedTest @ValueSource(strings={"PENDING", "REJECTED", "DISABLED"})
    void nonApprovedReportsNeverDispatch(String status) {
        var mappings = mock(ReportMappingRepository.class);
        var template = new SqlTemplate(); template.setStatus(status); template.setQueryId(7L); template.setSqlText("SELECT 1");
        when(mappings.findById(7L)).thenReturn(Optional.of(template));
        var agents = mock(AgentRepository.class); var http = mock(RestTemplate.class);
        var reports = new ReportViewerService(new ObjectMapper(), mappings, agents, mock(JdbcTemplate.class), http);
        assertThatThrownBy(() -> reports.executeReport(ReportExecutionRequest.builder().queryId(7L)
                .performedBy("viewer").agentId("agent-1").build())).isInstanceOf(SecurityException.class);
        verifyNoInteractions(agents, http);
    }

    @Test void inactiveApprovedReportNeverDispatches() {
        var mappings = mock(ReportMappingRepository.class);
        var template = new SqlTemplate(); template.setStatus("APPROVED"); template.setIsActive(false);
        when(mappings.findById(7L)).thenReturn(Optional.of(template));
        var http = mock(RestTemplate.class);
        var reports = new ReportViewerService(new ObjectMapper(), mappings, mock(AgentRepository.class), mock(JdbcTemplate.class), http);
        assertThatThrownBy(() -> reports.executeReport(ReportExecutionRequest.builder().queryId(7L).build()))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(http);
    }

    @Test void bothLegacyAliasesDelegateToGovernedGateway() throws Exception {
        var gateway = mock(ExternalGatewayController.class); var templates = mock(SqlTemplateRepository.class);
        var template = new SqlTemplate(); template.setUniqueName("approved_query");
        when(templates.findById(7L)).thenReturn(Optional.of(template));
        var servlet = new MockHttpServletRequest(); Map<String,Object> body = Map.of("agentIds", List.of("agent-1"));
        new ExternalConsumerController(templates, gateway).executeAuthorizedQuery(7L, "key", body, servlet);
        new PublicReportApiController(gateway).orchestrateApiCall("approved_query", "key", body, servlet);
        verify(gateway, times(2)).proxyOrchestration("approved_query", "key", body, servlet);
    }

    @Test void policyDeniesUnlistedAndEmptyClients() {
        var policy = new DocumentDownloadPolicy(); policy.setStatus("ACTIVE");
        policy.setAuthorizedClientIds(new ObjectMapper().valueToTree(List.of(1L)));
        var client = new ApiClient(); client.setClientId(2L);
        assertThatThrownBy(() -> DocumentPolicyAccess.requireClient(policy, client)).isInstanceOf(SecurityException.class);
        client.setClientId(1L); DocumentPolicyAccess.requireClient(policy, client);
        policy.setAuthorizedClientIds(new ObjectMapper().createArrayNode());
        assertThatThrownBy(() -> DocumentPolicyAccess.requireClient(policy, client)).isInstanceOf(SecurityException.class);
    }

    @Test void telemetryCannotUpdateAnotherClientsTrace() {
        var clients = mock(ApiClientRepository.class);
        var client = new ApiClient(); client.setClientName("client-a"); client.setIsActive(true);
        when(clients.findByApiKey("key")).thenReturn(Optional.of(client));
        var gateway = new ExternalGatewayController(mock(ReportViewerService.class), clients,
                mock(CertificateStorageService.class), mock(DoorsSigningCertificateRepository.class),
                mock(TemplateContractService.class), mock(ClientSpecificDataSegregationService.class),
                mock(ApiClientExecutionPolicy.class));
        var jdbc = mock(JdbcTemplate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(gateway, "jdbcTemplate", jdbc);
        assertThatThrownBy(() -> gateway.captureClientTelemetryFault("key", Map.of("traceId", "other-trace")))
                .isInstanceOf(SecurityException.class);
        verify(jdbc).update(contains("WHERE trace_id = ? AND username = ?"),
                anyString(), anyString(), eq("other-trace"), eq("client-a"));
        client.setIsActive(false);
        clearInvocations(jdbc);
        assertThatThrownBy(() -> gateway.captureClientTelemetryFault("key", Map.of("traceId", "other-trace")))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void legacyServiceRejectsAnonymousAndIgnoresSuppliedActor() {
        var reports = mock(ReportViewerService.class);
        var adapter = new QueryExecutionService(reports);
        assertThatThrownBy(() -> adapter.executeRemote("a", "q", "forged", Map.of()))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(reports);
        SecurityContextHolder.getContext().setAuthentication(auth("actual", "DATAMANAGER"));
        when(reports.executeReport(any())).thenThrow(new SecurityException("stop before dispatch"));
        assertThatThrownBy(() -> adapter.executeRemote("a", "q", "forged", Map.of()))
                .isInstanceOf(SecurityException.class);
        verify(reports).executeReport(argThat(r -> "actual".equals(r.getPerformedBy())));
    }

    @Test void documentGrantBindsAllCoordinatesAndClientScope() {
        var jdbc = mock(JdbcTemplate.class);
        var grants = new DocumentGrantService(jdbc, mock(SqlTemplateRepository.class), mock(AgentRepository.class),
                mock(DocumentDownloadPolicyRepository.class));
        var doc = Map.<String,Object>of("downloadId", "1", "serviceDocCode", "AOC", "fileName", "a.pdf", "packetType", "AOC");
        grants.issue("client-one-scope", List.of(doc), Map.of("tenderId", "authorized-tender"));
        var key = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("INSERT INTO"), key.capture(), anyString());
        when(jdbc.queryForList(contains("SELECT CAST"), eq(key.getValue())))
                .thenReturn(List.of(Map.of("params_json", "{\"tenderId\":\"authorized-tender\"}")));
        var request = new HashMap<String,Object>(Map.of("downloadId", "1", "docCode", "AOC", "fileName", "a.pdf", "packetType", "AOC"));
        grants.require("client-one-scope", request);
        assertThatThrownBy(() -> grants.require("client-two-scope", request)).isInstanceOf(SecurityException.class);
        request.put("downloadId", "2");
        assertThatThrownBy(() -> grants.require("client-one-scope", request)).isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest @ValueSource(strings={"SELECT 1; SELECT 2", "CALL audit_probe()", "SELECT 1",
            "UPDATE t SET id = 1", "DELETE FROM t", "ALTER TABLE t ADD COLUMN extra integer",
            "SELECT id FROM t; UPDATE t SET id = 1",
            "SELECT 1 -- ordinary comment\n", "SELECT * FROM (SELECT 1) x",
            "WITH x AS (SELECT 1) SELECT * FROM x",
            "SELECT '=1+1' AS equals_prefix, '+1+1' AS plus_prefix, '-1+1' AS minus_prefix, '@SUM(1)' AS at_prefix, '  =1+1' AS spaced_equals_prefix",
            "SELECT '=1+1' AS equals_prefix, '+1+1' AS plus_prefix, '-1+1' AS minus_prefix, '@SUM(1)' AS at_prefix, '  =1+1' AS spaced_equals_prefix FROM gep_properties",
            "SELECT '=1+1' AS equals_prefix, '+1+1' AS plus_prefix, '-1+1' AS minus_prefix, '@SUM(1)' AS at_prefix, '  =1+1' AS spaced_equals_prefix, filepath FROM gep_properties",
            "SELECT filepath, '=1+1' AS equals_prefix FROM gep_properties",
            "SELECT filepath, 1 AS static_label FROM gep_properties",
            "SELECT filepath, 'Status2' AS label FROM gep_properties",
            "SELECT filepath, 'Status-Label' AS label FROM gep_properties",
            "SELECT filepath, 'Status@Label' AS label FROM gep_properties",
            "SELECT 'Normal Label' FROM gep_properties",
            "SELECT * FROM (SELECT filepath, '=1+1' AS value FROM gep_properties) x",
            "SELECT '=1+1' AS value FROM t", "SELECT * FROM (SELECT '=1+1' AS value FROM t) x",
            "WITH x AS (SELECT '=1+1' AS value FROM t) SELECT * FROM x",
            "SELECT id FROM t UNION SELECT '=1+1' FROM t",
            "SELECT 1 -- comment\nDELETE FROM audit_probe", "", "SELECT pg_sleep(10)",
            "WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "SELECT * INTO backup FROM t"})
    void unsafeSqlIsRejected(String sql) { assertThat(SqlSecurityValidator.isSafeSelectOnly(sql)).isFalse(); }

    @ParameterizedTest @ValueSource(strings={"SELECT id FROM t WHERE id = :id",
            "SELECT tender_id FROM tenders WHERE tender_id = :p_tender_id",
            "SELECT tender_id FROM tenders WHERE created_at >= CAST(:p_from_date AS DATE) AND status ILIKE :p_status",
            "SELECT tender_id FROM tenders WHERE created_at >= :p_from_date::date",
            "SELECT amount * 1.18 AS gross_amount FROM tenders WHERE amount >= :p_min_amount",
            "SELECT filepath, 'Update' AS status FROM gep_properties",
            "SELECT filepath, 'Drop and Create' AS status FROM gep_properties",
            "SELECT filepath FROM gep_properties WHERE filepath = 'DELETE FROM harmless text'",
            "SELECT filepath FROM gep_properties -- DROP appears only in this comment\n",
            "SELECT \"update\" FROM t",
            "WITH x AS (SELECT id FROM t) SELECT * FROM x", "SELECT id FROM t -- ordinary comment\n",
            "SELECT id, filepath FROM t", "SELECT 'Normal Label' AS label, filepath FROM t",
            "SELECT 'स्वीकृत स्थिति' AS label, filepath FROM t",
            "SELECT COUNT(*) FROM t", "SELECT COUNT(1) FROM t",
            "SELECT * FROM t"})
    void ordinaryQueriesRemainSupported(String sql) { assertThat(SqlSecurityValidator.isSafeSelectOnly(sql)).isTrue(); }

    @Test void rejectingLegacySourceFreeQueryDoesNotRequireSqlApproval() {
        var templates = mock(SqlTemplateRepository.class);
        var legacy = new SqlTemplate();
        legacy.setQueryId(69L);
        legacy.setStatus("APPROVED");
        legacy.setSqlText("SELECT 1");
        when(templates.findById(69L)).thenReturn(Optional.of(legacy));
        when(templates.save(legacy)).thenReturn(legacy);
        var controller = new TemplateController(mock(JdbcTemplate.class), mock(TemplateService.class), templates,
                mock(MappingService.class), mock(AgentExecutionService.class), mock(DataPullRequestRepository.class),
                mock(ClientQueryMapRepository.class), mock(AgentRepository.class), mock(DataRequestAccess.class));

        var response = controller.approveAndMap(69L, Map.of("status", "REJECTED"), auth("reviewer", "DATAMANAGER"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(legacy.getStatus()).isEqualTo("REJECTED");
        assertThat(legacy.getAuthorizedAgents()).isEmpty();
        verify(templates).save(legacy);
    }

    @Test void sourceFreeQueryCannotBeApprovedEvenWithStatusOnlyPayload() {
        var templates = mock(SqlTemplateRepository.class);
        var legacy = new SqlTemplate();
        legacy.setQueryId(69L);
        legacy.setStatus("PENDING");
        legacy.setProposerId("proposer");
        legacy.setSqlText("SELECT 1");
        when(templates.findById(69L)).thenReturn(Optional.of(legacy));
        var controller = new TemplateController(mock(JdbcTemplate.class), mock(TemplateService.class), templates,
                mock(MappingService.class), mock(AgentExecutionService.class), mock(DataPullRequestRepository.class),
                mock(ClientQueryMapRepository.class), mock(AgentRepository.class), mock(DataRequestAccess.class));

        var response = controller.approveAndMap(69L, Map.of("status", "APPROVED"), auth("reviewer", "DATAMANAGER"));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(legacy.getStatus()).isEqualTo("PENDING");
        verify(templates, never()).save(any());
    }

    @Test void developerCannotReadUnapprovedEvidence() {
        var repository = mock(ExternalRequestRepository.class);
        var value = new ExternalRequest(); value.setStatus("SUBMITTED"); value.setRequestedBy("external");
        when(repository.findById(4L)).thenReturn(Optional.of(value));
        SecurityContextHolder.getContext().setAuthentication(auth("developer", "DEVELOPER"));
        var policy = new DataRequestAccess(repository);
        assertThatThrownBy(() -> policy.requireRead(4L)).isInstanceOf(SecurityException.class);
        value.setStatus("APPROVED"); policy.requireRead(4L);
    }

    @Test void streamRejectsUnregisteredDestinationBeforeExecuting() {
        var agents = mock(AgentRepository.class); var reports = mock(ReportViewerService.class);
        var controller = new MasterController(agents, mock(SqlTemplateRepository.class), reports);
        assertThatThrownBy(() -> controller.handleStream("http://unregistered.invalid", "SELECT 1", () -> "manager"))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(reports);
    }

    private static UsernamePasswordAuthenticationToken auth(String user, String role) {
        return new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority(role)));
    }
}
