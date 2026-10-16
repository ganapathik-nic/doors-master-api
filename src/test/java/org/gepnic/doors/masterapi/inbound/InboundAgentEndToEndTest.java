package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.gepnic.doors.inbound.*;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.model.Agent;
import org.gepnic.doors.masterapi.repository.AgentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.security.*;
import javax.net.ssl.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Optional real Agent + PostgreSQL test; requires an isolated TLS test Agent. */
@EnabledIfEnvironmentVariable(named="DOORS_INBOUND_E2E_AGENT",matches="https://.+")
class InboundAgentEndToEndTest {
    @Test void realHttpsAgentCommitsReplaysAndRejectsConflicts() throws Exception {
        SSLContext old=SSLContext.getDefault();
        try {
            KeyStore trust=KeyStore.getInstance("PKCS12");
            try(var in=Files.newInputStream(Path.of(System.getenv("DOORS_INBOUND_E2E_TRUST")))){trust.load(in,"test-password".toCharArray());}
            var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
            var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);SSLContext.setDefault(ssl);
            var properties=new InboundProperties();properties.setEnabled(true);
            properties.getAgentSecrets().put("test-agent","integration-test-secret-32-characters-long");
            var registry=mock(InboundRegistry.class);
            when(registry.require("agreements","integration",1)).thenReturn(new InboundRegistry.Route(1,"agreements","integration","test-agent",1,true));
            var agents=mock(AgentRepository.class);var agent=new Agent();agent.setIsActive(true);agent.setBaseUrl(System.getenv("DOORS_INBOUND_E2E_AGENT"));
            when(agents.findById("test-agent")).thenReturn(Optional.of(agent));
            var db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("DOORS_INBOUND_TEST_JDBC"),"doors_test",""));
            var service=new InboundGatewayService(properties,registry,agents,db);
            var pair=KeyPairGenerator.getInstance("RSA").generateKeyPair();var client=new ApiClient();client.setClientId(1L);
            client.setClientPublicKey(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            String agreement="E2E-"+UUID.randomUUID();
            String body="{\"tender_id\":\"1001\",\"agreement_no\":\""+agreement+"\",\"agreement_date\":\"2026-10-12\"}";
            var request=new InboundProtocol.Submission("agreements","integration",1,UUID.randomUUID(),1,0,Instant.now().getEpochSecond(),body);
            var first=service.execute(client,request,sign(pair,request));assertThat(first.getStatusCode().value()).isEqualTo(201);
            var repeat=service.execute(client,request,sign(pair,request));assertThat(repeat.getStatusCode().value()).isEqualTo(200);
            var conflict=new InboundProtocol.Submission("agreements","integration",1,request.requestId(),1,0,request.timestamp(),body+" ");
            assertThat(service.execute(client,conflict,sign(pair,conflict)).getStatusCode().value()).isEqualTo(409);
            var update=new InboundProtocol.Submission("agreements","integration",1,UUID.randomUUID(),1,1,request.timestamp(),body.replace("2026-10-12","2026-10-13"));
            assertThat(service.execute(client,update,sign(pair,update)).getStatusCode().value()).isEqualTo(201);
            assertThatThrownBy(()->service.execute(client,update,sign(pair,request))).isInstanceOf(SecurityException.class);
            assertThat(db.queryForObject("SELECT count(*) FROM doors_inbound.agreement_version WHERE instance_id='integration' AND agreement_no=?",Integer.class,agreement)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT record_version FROM doors_inbound.agreement_latest WHERE instance_id='integration' AND agreement_no=?",Long.class,agreement)).isEqualTo(2L);
        } finally {SSLContext.setDefault(old);}
    }
    String sign(KeyPair pair,InboundProtocol.Submission request) throws Exception {
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());
        signature.update(InboundProtocol.signingText(request).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }
}
