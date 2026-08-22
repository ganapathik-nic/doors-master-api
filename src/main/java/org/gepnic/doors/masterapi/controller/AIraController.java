package org.gepnic.doors.masterapi.controller;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;

import org.gepnic.doors.masterapi.dto.ApiResponse;
import org.gepnic.doors.masterapi.service.AiraAgent;
import org.gepnic.doors.masterapi.service.DatabaseMetadataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;
import java.util.*;
import java.net.Proxy;
import java.net.ProxySelector;

//import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import okhttp3.OkHttpClient;
 
import dev.langchain4j.model.ollama.OllamaChatModel; 

import java.net.http.HttpClient;

@Slf4j
@RestController
@CrossOrigin(origins = "http://127.0.0.1:5173", allowCredentials = "true") // 🛡️ Fixes the 'AI Core Unreachable' CORS issue
@RequestMapping("/api/v1/master/aira")

public class AIraController {

    private final AiraAgent airaAgent;
    private final DatabaseMetadataService metadataService;
    private final RestTemplate restTemplate = new RestTemplate();
 public AIraController(DatabaseMetadataService metadataService) {
    this.metadataService = metadataService;

    // Direct initialization - System properties in main() will handle the bypass
    OllamaChatModel model = OllamaChatModel.builder()
            .baseUrl("http://127.0.0.1:11434")
            .modelName("llama3:latest")
            .timeout(Duration.ofSeconds(300)) // High timeout for NIC environments
            .logRequests(true) 
            .logResponses(true)
            .build();

    this.airaAgent = AiServices.builder(AiraAgent.class)
            .chatLanguageModel(model)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(10))
            .build();
}
   
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<Map<String, Object>>> processAiQuery(@RequestBody Map<String, String> payload) {
        String userPrompt = payload.get("prompt");
        
        if (userPrompt == null || userPrompt.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Prompt cannot be empty", 400));
        }

        try {
            // 3. Get Live DB Schema Context
            String liveSchema = metadataService.getAarveeContext(); 
            //String liveSchema = "Table: audit_logs [id, username, action]";
            log.info("AIra-CORE: Schema context loaded successfully.");
            //log.info("AIra-CORE: Processing Query...");

            // 4. Invoke the AI Agent
            //String aiResponseText = airaAgent.chat("CONTEXT SCHEMA:\n" + liveSchema + "\n\nUSER QUESTION: " + userPrompt);
            String aiResponseText = airaAgent.chat("CONTEXT SCHEMA:\n" + liveSchema + "\n\nUSER QUESTION: " + userPrompt);
            Map<String, Object> compositeResult = new HashMap<>();
            compositeResult.put("answer", aiResponseText);
            compositeResult.put("executionData", new ArrayList<>());

            // Model output is advisory text only. Never execute SQL emitted by the model.
            compositeResult.put("executionDisabled", true);

            return ResponseEntity.ok(ApiResponse.success(compositeResult, "AIra Analysis Complete"));

        } catch (Exception e) {
            log.error("AIra-CORE: System failure", e);
            return ResponseEntity.status(503).body(ApiResponse.error("AIra Intelligence Core Offline", 503));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> checkAiStatus() {
        Map<String, Object> status = new HashMap<>();
        try {
            // Real check: Can we reach Ollama?
            restTemplate.getForObject("http://127.0.0.1:11434/api/tags", Map.class);
            status.put("online", true);
            status.put("agent", "AIra (Enterprise)");
        } catch (Exception e) {
            status.put("online", false);
        }
        return ResponseEntity.ok(status);
    }
}
