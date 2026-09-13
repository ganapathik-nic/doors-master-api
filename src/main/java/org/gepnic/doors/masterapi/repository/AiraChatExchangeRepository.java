package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.AiraChatExchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AiraChatExchangeRepository extends JpaRepository<AiraChatExchange, Long> {
    List<AiraChatExchange> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
    Optional<AiraChatExchange> findByIdAndConversationUsernameIgnoreCase(Long id, String username);

    @Query("select count(e), avg(e.rating), sum(case when e.rating >= 4 then 1 else 0 end), "
            + "sum(case when e.rating <= 2 then 1 else 0 end) from AiraChatExchange e where e.rating is not null")
    Object[] ratingSummary();

    @Query("select count(e) from AiraChatExchange e")
    long totalExchanges();
}
