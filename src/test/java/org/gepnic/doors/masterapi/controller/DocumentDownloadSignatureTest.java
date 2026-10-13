package org.gepnic.doors.masterapi.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.gepnic.doors.masterapi.service.*;
import org.gepnic.doors.masterapi.repository.*;
import org.gepnic.doors.masterapi.entity.*;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import java.nio.file.*;
import java.security.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentDownloadSignatureTest {
 @Test void downloadedBytesMatchSignedMetadata() throws Exception {
  var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
  var certs=mock(DoorsSigningCertificateRepository.class); var storage=mock(CertificateStorageService.class);
  var cert=new DoorsSigningCertificate(); cert.setKeyId("test-key");
  when(certs.findByIsActiveTrue()).thenReturn(Optional.of(cert));
  when(storage.getSigningPrivateKey("test-key")).thenReturn(pair.getPrivate());
  var clients=mock(ApiClientRepository.class); var client=new ApiClient(); client.setClientName("client");
  when(clients.findByApiKey("test-api-key")).thenReturn(Optional.of(client));
  var registrations=mock(DocumentServiceRegistrationRepository.class); var registration=new DocumentServiceRegistration(); registration.setIsActive(true);
  when(registrations.findByServiceNameIgnoreCase("Tripura")).thenReturn(Optional.of(registration));
  var manifest=mock(DocumentManifestService.class);
  var mapper=new ObjectMapper();
  var encryption=new ApiClientResponseEncryptionService(clients,storage,certs,mapper);
  var controller=new DocumentDownloadGatewayController(mock(DocumentDownloadOrchestrationService.class),manifest,registrations,encryption,
    mock(SwaggerSessionService.class),clients,mock(DocumentDownloadAuditService.class),mock(RegisteredDocumentServiceClient.class));
  byte[] content="document fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  Path file=Files.createTempFile("signed-document-", ".bin"); Files.write(file,content);
  var body=Map.<String,Object>of("downloadId","123","fileName","file.pdf");
  when(manifest.downloadDocument("Tripura","test-api-key",body)).thenReturn(
    new RegisteredDocumentServiceClient.DocumentPayload(file,content.length,hash,MediaType.APPLICATION_OCTET_STREAM,200,"https://upstream.test"));
  var request=new MockHttpServletRequest(); request.setRequestURI("/api/v1/master/gateway/documents/services/Tripura/download");
  var response=controller.downloadRegisteredDocument("Tripura","test-api-key",null,request,body);
  byte[] payload=Base64.getDecoder().decode(response.getHeaders().getFirst("X-DOORS-Document-Metadata"));
  Signature verifier=Signature.getInstance("SHA256withRSA"); verifier.initVerify(pair.getPublic()); verifier.update(payload);
  assertThat(verifier.verify(Base64.getDecoder().decode(response.getHeaders().getFirst("X-DOORS-Signature")))).isTrue();
  var metadata=mapper.readTree(payload);
  assertThat(metadata.path("service").asText()).isEqualTo("Tripura");
  assertThat(metadata.path("downloadId").asText()).isEqualTo("123");
  assertThat(metadata.path("fileName").asText()).isEqualTo("file.pdf");
  assertThat(metadata.path("size").asLong()).isEqualTo(content.length);
  assertThat(metadata.path("correlationId").asText()).isEqualTo(response.getHeaders().getFirst("X-Correlation-ID"));
  var output=new ByteArrayOutputStream(); response.getBody().writeTo(output);
  assertThat(output.toByteArray()).isEqualTo(content);
  assertThat(metadata.path("sha256").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray())));
  assertThat(Files.exists(file)).isFalse();
 }
}
