package org.gepnic.doors.masterapi.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "aira_chat_exchanges")
@Getter
@Setter
@NoArgsConstructor
public class AiraChatExchange {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "exchange_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private AiraConversation conversation;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "answer_type", length = 40)
    private String answerType;

    @Column(name = "knowledge_snippets_matched", nullable = false)
    private Integer knowledgeSnippetsMatched = 0;

    @Column(nullable = false)
    private Boolean refined = false;

    @Column(name = "captured_at", length = 80)
    private String capturedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "execution_data", columnDefinition = "jsonb")
    private JsonNode executionData;

    private Short rating;

    @Column(name = "rating_comment", length = 500)
    private String ratingComment;

    @Column(name = "rated_at")
    private LocalDateTime ratedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void createTimestamp() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
