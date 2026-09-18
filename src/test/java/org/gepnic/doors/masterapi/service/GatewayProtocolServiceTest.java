package org.gepnic.doors.masterapi.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.gepnic.doors.masterapi.exception.DoorsApiException;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class GatewayProtocolServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final GatewayProtocolService service = new GatewayProtocolService(jdbc);
    final Instant now = Instant.parse("2026-09-14T12:00:00Z");
    Map<String,Object> payload() {
        return new HashMap<>(Map.of("protocolVersion",2,"clientId",26,"query","test",
                "requestId",UUID.randomUUID().toString(),"issuedAt",now.toString()));
    }
    void setup() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq(26L), eq("test"), eq(2))).thenReturn(List.of("SIGNED_FRESHNESS"));
        when(jdbc.queryForObject(anyString(), eq(java.sql.Timestamp.class))).thenReturn(java.sql.Timestamp.from(now));
    }
    @Test void acceptsOnceThenRejectsDuplicate() {
        setup();
        when(jdbc.update(startsWith("INSERT INTO gateway_request_claim"),eq(26L),any(UUID.class),any(java.sql.Timestamp.class))).thenReturn(1,0);
        var body=payload(); service.validateAndClaim(26,"test",2,true,body);
        assertThatThrownBy(() -> service.validateAndClaim(26,"test",2,true,body))
                .isInstanceOfSatisfying(DoorsApiException.class,e -> assertThat(e.getCode()).isEqualTo("DOORS-REQUEST-REPLAY"));
    }
    @Test void rejectsExpiredFutureAndWrongBindings() {
        setup();
        for (int seconds : new int[]{-301,31}) {
            var p=payload();p.put("issuedAt",now.plusSeconds(seconds).toString());
            assertThatThrownBy(() -> service.validateAndClaim(26,"test",2,true,p)).isInstanceOf(DoorsApiException.class);
        }
        assertThatThrownBy(() -> service.validateAndClaim(26,"other",2,true,payload())).isInstanceOf(DoorsApiException.class);
        var p=payload();p.put("clientId",27);
        assertThatThrownBy(() -> service.validateAndClaim(26,"test",2,true,p)).isInstanceOf(DoorsApiException.class);
        verify(jdbc,never()).update(startsWith("INSERT"),anyLong(),any(),any());
    }
    @Test void blocksLegacyAndPlaintextForMigratedClient() {
        setup();
        assertThatThrownBy(() -> service.validateAndClaim(26,"test",1,true,Map.of())).isInstanceOf(DoorsApiException.class);
        assertThatThrownBy(() -> service.validateAndClaim(26,"test",2,false,payload())).isInstanceOf(DoorsApiException.class);
    }
    @Test void legacyRemainsCompatibleUntilMigration() {
        when(jdbc.queryForList(anyString(),eq(String.class),eq(26L),eq("test"),eq(1))).thenReturn(List.of("LEGACY"));
        service.validateAndClaim(26,"test",1,false,Map.of());
        assertThatThrownBy(() -> service.validateAndClaim(26,"test",1,true,payload())).isInstanceOf(DoorsApiException.class);
    }
    @Test void enabledVersionForOneQueryDoesNotEnableAnotherOrUnknownVersion() {
        setup();
        assertThat(service.requireAllowed(26,"test",2)).isEqualTo("SIGNED_FRESHNESS");
        assertThatThrownBy(() -> service.requireAllowed(26,"other",2)).isInstanceOf(DoorsApiException.class);
        assertThatThrownBy(() -> service.requireAllowed(27,"test",2)).isInstanceOf(DoorsApiException.class);
        assertThatThrownBy(() -> service.requireAllowed(26,"test",99)).isInstanceOf(DoorsApiException.class);
    }
    @Test void policyUpdatesRequireAnExistingAssignment() {
        when(jdbc.queryForObject(contains("count(*)"),eq(Integer.class),eq(26L),eq(5L))).thenReturn(0);
        assertThatThrownBy(() -> service.updatePolicy(26,5,2,true,false,null,"manager"))
                .isInstanceOf(SecurityException.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void unknownHandlerFailsClosed() {
        when(jdbc.queryForList(anyString(),eq(String.class),eq(26L),eq("test"),eq(3))).thenReturn(List.of("UNIMPLEMENTED"));
        assertThatThrownBy(() -> service.requireAllowed(26,"test",3)).isInstanceOf(DoorsApiException.class);
    }
}
