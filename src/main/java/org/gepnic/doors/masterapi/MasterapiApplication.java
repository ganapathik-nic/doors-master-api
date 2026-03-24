package org.gepnic.doors.masterapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
//import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

 @SpringBootApplication // Removed the exclusion to enable JPA and Hibernate
 @ComponentScan(basePackages = {"org.gepnic.doors"}) // Force scan the entire root
public class MasterapiApplication {
    public static void main(String[] args) {
        // 🛡️ Force JVM to ignore proxies for local Ollama and Master API communication
    System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
    System.setProperty("java.net.useSystemProxies", "false");
         
        SpringApplication.run(MasterapiApplication.class, args);
    }
} 
