package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.Test;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.security.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InboundGatewayTest {
    @org.junit.jupiter.api.Test
    void invalidAgentResponsesDoNotExposeResponseContent() throws Exception {
        assertThatThrownBy(()->InboundGatewayService.parseAgentResponse("<html>secret-response</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("not valid JSON")
            .hasMessageNotContaining("secret-response");
        assertThatThrownBy(()->InboundGatewayService.parseAgentResponse(new byte[0]))
            .isInstanceOf(java.io.IOException.class).hasMessageContaining("empty");
        assertThat(InboundGatewayService.parseAgentResponse("{\"code\":\"WRITE_RESULT_UNKNOWN\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)).path("code").asText())
            .isEqualTo("WRITE_RESULT_UNKNOWN");
    }
    String body="{\"tender_id\":\"1001\",\"agreement_no\":\"AGR/2026/001\",\"agreement_date\":\"2026-10-12\"}";
    ApiClient client(){var c=new ApiClient();c.setClientId(1L);c.setIsActive(true);return c;}
    InboundProtocol.Submission request(){return new InboundProtocol.Submission("agreements","tripura",1,UUID.randomUUID(),1,0,Instant.now().getEpochSecond(),body);}
    @Test void signaturesBindDestinationVersionAndPayload() throws Exception {
        var pair=KeyPairGenerator.getInstance("RSA").generateKeyPair();var c=client();
        c.setClientPublicKey("-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----");
        var s=request();var signer=Signature.getInstance("SHA256withRSA");signer.initSign(pair.getPrivate());signer.update(InboundProtocol.signingText(s).getBytes(StandardCharsets.UTF_8));
        String signature=Base64.getEncoder().encodeToString(signer.sign());
        InboundGatewayService.verifySignature(c,s,signature);
        for(var changed:List.of(
            new InboundProtocol.Submission(s.service(),"assam",1,s.requestId(),1,0,s.timestamp(),body),
            new InboundProtocol.Submission(s.service(),s.instanceId(),1,s.requestId(),1,1,s.timestamp(),body),
            new InboundProtocol.Submission(s.service(),s.instanceId(),1,s.requestId(),1,0,s.timestamp(),body+" "),
            new InboundProtocol.Submission(s.service(),s.instanceId(),1,s.requestId(),1,0,s.timestamp()-1000,body)))
            assertThatThrownBy(()->InboundGatewayService.verifySignature(c,changed,signature)).isInstanceOf(SecurityException.class);
    }
    @Test void defaultOffNeverCallsRegistryOrAgent(){
        var r=mock(InboundRegistry.class);var a=mock(AgentRepository.class);
        var service=new InboundGatewayService(new InboundProperties(),r,a,mock(JdbcTemplate.class));
        assertThatThrownBy(()->service.execute(client(),request(),null)).hasMessageContaining("WRITE_DISABLED");
        verifyNoInteractions(r,a);
    }
    @Test void readClientWithoutWriteMappingCannotSubmit(){
        var p=new InboundProperties();p.setEnabled(true);var r=mock(InboundRegistry.class);var a=mock(AgentRepository.class);
        when(r.require("agreements","tripura",1)).thenThrow(new SecurityException("Write mapping denied"));
        var service=new InboundGatewayService(p,r,a,mock(JdbcTemplate.class));
        assertThatThrownBy(()->service.execute(client(),request(),null)).isInstanceOf(SecurityException.class);
        verifyNoInteractions(a);
    }
    @Test void unregisteredDatasetNeverFallsBackToAgreement(){
        var p=new InboundProperties();p.setEnabled(true);var r=mock(InboundRegistry.class);var a=mock(AgentRepository.class);
        var service=new InboundGatewayService(p,r,a,mock(JdbcTemplate.class));var s=request();
        var unsupported=new InboundProtocol.Submission("evaluations",s.instanceId(),1,s.requestId(),1,0,s.timestamp(),body);
        // Dynamic services are permitted only through an approved, enabled registry mapping.
        when(r.require("evaluations",s.instanceId(),1)).thenThrow(new SecurityException("Write service or destination is not authorised"));
        assertThatThrownBy(()->service.execute(client(),unsupported,null)).isInstanceOf(SecurityException.class).hasMessageContaining("not authorised");
        verify(r).require("evaluations",s.instanceId(),1);
        verifyNoMoreInteractions(r);
        verifyNoInteractions(a);
    }
}
