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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate = new RestTemplate();
 public AIraController(DatabaseMetadataService metadataService, JdbcTemplate jdbcTemplate) {
    this.metadataService = metadataService;
    this.jdbcTemplate = jdbcTemplate;

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

            // 5. Autonomous SQL Detection and Execution
            String extractedSql = extractSqlFromMarkdown(aiResponseText);
            
            if (extractedSql != null) {
                String cleanSql = extractedSql.replaceAll(";$", "").trim();
                
                // Security Check: Only SELECT allowed
                if (cleanSql.toUpperCase().startsWith("SELECT") && !isDangerous(cleanSql)) {
                    log.info("AIra-CORE: Executing Audit Query: [{}]", cleanSql);
                    try {
                        // Safety limit if not present
                        String safeSql = cleanSql.toUpperCase().contains("LIMIT") ? cleanSql : cleanSql + " LIMIT 15";
                        List<Map<String, Object>> rows = jdbcTemplate.queryForList(safeSql);
                        compositeResult.put("executionData", rows);
                    } catch (Exception sqlEx) {
                        log.error("AIra-CORE: Database error", sqlEx);
                        compositeResult.put("sqlError", "Data retrieval error: " + sqlEx.getMessage());
                    }
                } else {
                    log.warn("AIra-SECURITY: Blocked attempt: [{}]", cleanSql);
                    compositeResult.put("sqlError", "Security Policy: Unauthorized operation blocked.");
                }
            }

            return ResponseEntity.ok(ApiResponse.success(compositeResult, "AIra Analysis Complete"));

        } catch (Exception e) {
            log.error("AIra-CORE: System failure", e);
            return ResponseEntity.status(503).body(ApiResponse.error("AIra Intelligence Core Offline", 503));
        }
    }

    private String extractSqlFromMarkdown(String text) {
        Pattern pattern = Pattern.compile("```sql\\s+(.*?)\\s+```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group(1).trim();
        
        Pattern selectPattern = Pattern.compile("(SELECT\\s+.*?FROM\\s+.*?)(?=\\n|;|$)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
        Matcher selectMatcher = selectPattern.matcher(text);
        if (selectMatcher.find()) return selectMatcher.group(1).trim();
        
        return null;
    }

    private boolean isDangerous(String sql) {
        String upper = sql.toUpperCase();
        return upper.contains("DELETE") || upper.contains("DROP") || 
               upper.contains("UPDATE") || upper.contains("TRUNCATE") || 
               upper.contains("ALTER")  || upper.contains("GRANT");
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