package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.gepnic.doors.masterapi.model.AiraChatExchange;
import org.gepnic.doors.masterapi.model.AiraConversation;
import org.gepnic.doors.masterapi.repository.AiraChatExchangeRepository;
import org.gepnic.doors.masterapi.repository.AiraConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiraChatHistoryService {
    private final AiraConversationRepository conversationRepository;
    private final AiraChatExchangeRepository exchangeRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public Map<String, Long> record(String username, Long conversationId, String prompt, Map<String, Object> response) {
        AiraConversation conversation = conversationId == null
                ? newConversation(username, prompt)
                : conversationRepository.findByIdAndUsernameIgnoreCase(conversationId, username)
                    .orElseThrow(() -> new SecurityException("Conversation not found"));
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        AiraChatExchange exchange = new AiraChatExchange();
        exchange.setConversation(conversation);
        exchange.setPrompt(prompt);
        exchange.setAnswer(String.valueOf(response.getOrDefault("answer", "")));
        exchange.setAnswerType(stringValue(response.get("answerType")));
        exchange.setKnowledgeSnippetsMatched(numberValue(response.get("knowledgeSnippetsMatched")));
        exchange.setRefined(Boolean.TRUE.equals(response.get("refined")));
        exchange.setCapturedAt(stringValue(response.get("capturedAt")));
        Object executionData = response.get("executionData");
        if (executionData != null) exchange.setExecutionData(objectMapper.valueToTree(executionData));
        exchangeRepository.save(exchange);
        return Map.of("conversationId", conversation.getId(), "exchangeId", exchange.getId());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> conversations(String username) {
        return conversationRepository.findTop30ByUsernameIgnoreCaseOrderByUpdatedAtDesc(username).stream()
                .map(value -> Map.<String, Object>of("id", value.getId(), "title", value.getTitle(),
                        "updatedAt", value.getUpdatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> conversation(String username, Long conversationId) {
        AiraConversation conversation = conversationRepository.findByIdAndUsernameIgnoreCase(conversationId, username)
                .orElseThrow(() -> new SecurityException("Conversation not found"));
        List<Map<String, Object>> exchanges = exchangeRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream().map(this::exchangeView).toList();
        return Map.of("id", conversation.getId(), "title", conversation.getTitle(),
                "updatedAt", conversation.getUpdatedAt(), "exchanges", exchanges);
    }

    @Transactional
    public Map<String, Object> rate(String username, Long exchangeId, int rating, String comment) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Rating must be between 1 and 5");
        AiraChatExchange exchange = exchangeRepository.findByIdAndConversationUsernameIgnoreCase(exchangeId, username)
                .orElseThrow(() -> new SecurityException("Response not found"));
        exchange.setRating((short) rating);
        exchange.setRatingComment(comment == null || comment.isBlank() ? null : comment.trim());
        exchange.setRatedAt(LocalDateTime.now());
        exchangeRepository.save(exchange);
        return Map.of("exchangeId", exchangeId, "rating", rating);
    }

    @Transactional
    public void updateAnswer(String username, Long exchangeId, Map<String, Object> response) {
        AiraChatExchange exchange = exchangeRepository.findByIdAndConversationUsernameIgnoreCase(exchangeId, username)
                .orElseThrow(() -> new SecurityException("Response not found"));
        exchange.setAnswer(String.valueOf(response.getOrDefault("answer", exchange.getAnswer())));
        exchange.setKnowledgeSnippetsMatched(numberValue(response.get("knowledgeSnippetsMatched")));
        exchange.setRefined(Boolean.TRUE.equals(response.get("refined")));
        exchangeRepository.save(exchange);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> analytics() {
        Object[] summary = exchangeRepository.ratingSummary();
        Object[] values = summary.length == 1 && summary[0] instanceof Object[] nested ? nested : summary;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalConversations", conversationRepository.count());
        result.put("totalExchanges", exchangeRepository.totalExchanges());
        result.put("ratedResponses", longValue(values, 0));
        result.put("averageRating", doubleValue(values, 1));
        result.put("positiveRatings", longValue(values, 2));
        result.put("negativeRatings", longValue(values, 3));
        return result;
    }

    private AiraConversation newConversation(String username, String prompt) {
        AiraConversation value = new AiraConversation();
        value.setUsername(username);
        String title = prompt == null ? "New conversation" : prompt.trim().replaceAll("\\s+", " ");
        value.setTitle(title.substring(0, Math.min(title.length(), 80)));
        return conversationRepository.save(value);
    }

    private Map<String, Object> exchangeView(AiraChatExchange value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.getId());
        result.put("prompt", value.getPrompt());
        result.put("answer", value.getAnswer());
        result.put("answerType", value.getAnswerType());
        result.put("knowledgeSnippetsMatched", value.getKnowledgeSnippetsMatched());
        result.put("refined", value.getRefined());
        result.put("capturedAt", value.getCapturedAt());
        result.put("executionData", value.getExecutionData() == null ? objectMapper.createArrayNode() : value.getExecutionData());
        result.put("rating", value.getRating());
        result.put("createdAt", value.getCreatedAt());
        return result;
    }

    private static String stringValue(Object value) { return value == null ? null : String.valueOf(value); }
    private static int numberValue(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static long longValue(Object[] values, int index) { return values.length > index && values[index] instanceof Number n ? n.longValue() : 0L; }
    private static double doubleValue(Object[] values, int index) { return values.length > index && values[index] instanceof Number n ? n.doubleValue() : 0D; }
}
