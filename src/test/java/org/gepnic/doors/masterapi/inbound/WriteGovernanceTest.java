package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.Test;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.service.GovernanceReviewRules;
import org.gepnic.doors.masterapi.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class WriteGovernanceTest {
 @Test void approvalRequiresPendingAndIndependentReviewer(){
  assertThatThrownBy(()->GovernanceReviewRules.requirePendingReviewer("DRAFT","alice","bob")).isInstanceOf(IllegalStateException.class);
  assertThatThrownBy(()->GovernanceReviewRules.requirePendingReviewer("PENDING","Alice","alice")).isInstanceOf(SecurityException.class);
  GovernanceReviewRules.requirePendingReviewer("PENDING","alice","bob");
 }
 @Test void legacyRegistrationCannotBypassGovernance(){
  var db=mock(JdbcTemplate.class);var a=mock(AgentRepository.class);var c=mock(ApiClientRepository.class);
  var registry=new InboundRegistry(db,a,c);
  assertThatThrownBy(()->registry.register(new InboundRegistry.Registration("agreements","tripura","agent",1L,null),"alice")).isInstanceOf(SecurityException.class);
  verifyNoInteractions(db,a,c);
 }
 @Test void unapprovedDestinationCannotBeActivated(){
  var db=mock(JdbcTemplate.class);
  when(db.queryForObject(anyString(),eq(Integer.class),eq(7L),eq(AgreementDefinition.hash()))).thenReturn(0);
  var registry=new InboundRegistry(db,mock(AgentRepository.class),mock(ApiClientRepository.class));
  assertThatThrownBy(()->registry.enable(7,true)).isInstanceOf(SecurityException.class);
  verify(db,never()).update(anyString(),any(Object[].class));
 }
 @Test void definitionHasFixedTableParameterizedWriterAndStableHash(){
  assertThat(AgreementDefinition.describe()).containsEntry("table","doors_inbound.agreement_version").containsEntry("payloadType","json");
  assertThat(AgreementDefinition.hash()).matches("[0-9a-f]{64}");
  assertThat(AgreementDefinition.ddl()).contains("SECURITY DEFINER","REVOKE ALL");
 }
 @Test void dryRunOptionsContainOnlySandboxAgents(){
  var registry=mock(InboundRegistry.class);
  when(registry.options()).thenReturn(java.util.Map.of("agents",java.util.List.of(java.util.Map.of("id","Dev-01","isSandbox",true),java.util.Map.of("id","Dev-02","isSandbox",false))));
  var result=(java.util.Map<?,?>)new WriteGovernanceController(mock(WriteGovernanceService.class),registry).options();
  assertThat(result.get("sandboxAgents").toString()).contains("Dev-01").doesNotContain("Dev-02");
  assertThat(JsonWriteDefinition.types()).contains("date","json","jsonb","timestamp with time zone","integer[]","daterange");
 }
}
