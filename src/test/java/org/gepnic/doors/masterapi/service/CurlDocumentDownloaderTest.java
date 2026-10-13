package org.gepnic.doors.masterapi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.gepnic.doors.masterapi.model.DocumentServiceRegistration;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class CurlDocumentDownloaderTest {

    @Test
    void createsPhpCompatibleAuthorizationHeaderFromDecodedQueryValues() throws Exception {
        CurlDocumentDownloader downloader = new CurlDocumentDownloader(
                "curl", "SecretKeyForReports", "101", true);
        URI uri = URI.create("https://demoetenders.tn.nic.in/nicgep_docs_webservice/Documents/downloadDocuments"
                + "?downloadId=33975&docCode=BIDPCK&fileName=boq%2033975.xls&packetType=Finance");
        String signedValue = "33975|BIDPCK|boq 33975.xls|Finance|SecretKeyForReports";
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512")
                .digest(signedValue.getBytes(StandardCharsets.UTF_8)));

        assertThat(downloader.buildAuthorization(uri)).isEqualTo(expectedHash + "#101");
    }

    @Test
    void preservesEmptyPacketTypeInPhpCompatibleSignature() throws Exception {
        CurlDocumentDownloader downloader = new CurlDocumentDownloader(
                "curl", "SecretKeyForReports", "101", true);
        URI uri = URI.create("https://example.test/Documents/downloadDocuments"
                + "?downloadId=15194&docCode=TENDER&fileName=20.pdf&packetType=");
        String signedValue = "15194|TENDER|20.pdf||SecretKeyForReports";
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512")
                .digest(signedValue.getBytes(StandardCharsets.UTF_8)));

        assertThat(downloader.buildAuthorization(uri)).isEqualTo(expectedHash + "#101");
    }
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void bypassesRevocationWithoutDisablingCertificateValidation() {
        var downloader = new CurlDocumentDownloader("curl", "secret", "101", true);
        var registration = new DocumentServiceRegistration();
        registration.setVerifyTls(true);
        var command = new ArrayList<String>();

        downloader.addTlsOptions(command, registration);

        assertThat(command).containsExactly("--ssl-no-revoke");
    }

    @Test
    void allowsRevocationChecksToBeRestored() {
        var downloader = new CurlDocumentDownloader("curl", "secret", "101", false);
        var registration = new DocumentServiceRegistration();
        registration.setVerifyTls(true);
        var command = new ArrayList<String>();

        downloader.addTlsOptions(command, registration);

        assertThat(command).isEmpty();
    }

    @Test
    void preservesExplicitServiceTlsSetting() {
        var downloader = new CurlDocumentDownloader("curl", "secret", "101", false);
        var registration = new DocumentServiceRegistration();
        registration.setVerifyTls(false);
        var command = new ArrayList<String>();

        downloader.addTlsOptions(command, registration);

        assertThat(command).containsExactly("--insecure");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void omitsSchannelOptionOnOtherPlatforms() {
        var downloader = new CurlDocumentDownloader("curl", "secret", "101", true);
        var registration = new DocumentServiceRegistration();
        registration.setVerifyTls(true);
        var command = new ArrayList<String>();

        downloader.addTlsOptions(command, registration);

        assertThat(command).isEmpty();
    }
}
