package org.gepnic.doors;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class SecurityTest {

    @Test
    public void generateHash() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        // The SHA-256 string from your Login.vue
        String sha256Input = "71092e0789725f7786806509653a985a113ec4c1f9621375d3113101c594c9f1";

        String encodedHash = encoder.encode(sha256Input);

        System.out.println("\n--------------------------------");
        System.out.println("DOORS SECURITY HASH:");
        System.out.println(encodedHash);
        System.out.println("--------------------------------\n");
    }
}