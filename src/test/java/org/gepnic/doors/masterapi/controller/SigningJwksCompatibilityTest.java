package org.gepnic.doors.masterapi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.gepnic.doors.masterapi.DoorsExternalApiClient;
import org.gepnic.doors.masterapi.entity.DoorsSigningCertificate;
import org.gepnic.doors.masterapi.repository.DoorsSigningCertificateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SigningJwksCompatibilityTest {
    @TempDir Path directory;
    ExternalGatewayController controller;
    DoorsSigningCertificateRepository repository;

    @BeforeEach void setup() {
        repository = mock(DoorsSigningCertificateRepository.class);
        var certificate = new DoorsSigningCertificate();
        certificate.setKeyId("test-key");
        when(repository.findByIsActiveTrue()).thenReturn(Optional.of(certificate));
        controller = new ExternalGatewayController(null, null, null, repository, null, null, null);
        ReflectionTestUtils.setField(controller, "keysDirectoryPath", directory.toString());
    }

    @Test void existingPublicKeyWorksWithUnmodifiedSdk() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(new java.security.spec.RSAKeyGenParameterSpec(2048, java.math.BigInteger.valueOf(3)));
        var pair = generator.generateKeyPair();
        writePem("PUBLIC KEY", pair.getPublic().getEncoded());
        verifyWithExistingSdk(pair.getPrivate(), pair.getPublic());
    }

    @Test void uploadedCertificateWorksWithUnmodifiedSdk() throws Exception {
        Path store = directory.resolve("test.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=SDK Compatibility Test", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "test-password", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "test-password".toCharArray()); }
        var certificate = keys.getCertificate("test");
        writePem("CERTIFICATE", certificate.getEncoded());
        verifyWithExistingSdk((PrivateKey) keys.getKey("test", "test-password".toCharArray()), certificate.getPublicKey());
    }

    @Test void malformedOrMissingPublicAssetFailsClosed() throws Exception {
        assertThatThrownBy(() -> controller.getPublicJwks()).isInstanceOf(java.io.FileNotFoundException.class);
        Files.writeString(directory.resolve("test-key.pem"), "invalid certificate");
        assertThatThrownBy(() -> controller.getPublicJwks()).isInstanceOf(Exception.class);
    }

    private void writePem(String type, byte[] bytes) throws Exception {
        Files.writeString(directory.resolve("test-key.pem"), "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n");
    }

    private void verifyWithExistingSdk(PrivateKey privateKey, PublicKey expected) throws Exception {
        var mapper = new ObjectMapper();
        byte[] body = mapper.writeValueAsBytes(controller.getPublicJwks().getBody());
        var jwk = mapper.readTree(body).path("keys").get(0);
        assertThat(jwk.path("kid").asText()).isEqualTo("test-key");
        assertThat(jwk.path("alg").asText()).isEqualTo("RS256");
        assertThat(Base64.getUrlDecoder().decode(jwk.path("n").asText())).hasSize(256);
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("PRIVATE", "\"d\"");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var sdk = new DoorsExternalApiClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-only");
            PublicKey resolved = ReflectionTestUtils.invokeMethod(sdk, "resolveDoorsPublicKey", "test-key");
            assertThat(resolved.getEncoded()).isEqualTo(expected.getEncoded());
            var internalSdk = new org.gepnic.doors.masterapi.GePNICInternalDoorsClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-only");
            PublicKey internalResolved = ReflectionTestUtils.invokeMethod(internalSdk, "resolveDoorsPublicKey", "test-key");
            assertThat(internalResolved.getEncoded()).isEqualTo(expected.getEncoded());
            byte[] payload = "{\"downloadId\":123}".getBytes(StandardCharsets.UTF_8);
            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey); signer.update(payload);
            byte[] signature = signer.sign();
            var verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(resolved); verifier.update(payload);
            assertThat(verifier.verify(signature)).isTrue();
            payload[0] ^= 1;
            verifier.initVerify(resolved); verifier.update(payload);
            assertThat(verifier.verify(signature)).isFalse();
        } finally { server.stop(0); }
    }
}
