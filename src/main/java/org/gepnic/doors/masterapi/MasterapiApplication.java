 package org.gepnic.doors.masterapi;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
@ComponentScan(basePackages = {"org.gepnic.doors"})
public class MasterapiApplication {

    public static void main(String[] args) {
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
        System.setProperty("java.net.useSystemProxies", "false");
        
        SpringApplication.run(MasterapiApplication.class, args);
    }

    // 🛡️ SECURITY BOOTSTRAP: This prints the hash to your terminal on startup
     
}