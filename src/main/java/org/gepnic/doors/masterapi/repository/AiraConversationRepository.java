package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.AiraConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiraConversationRepository extends JpaRepository<AiraConversation, Long> {
    List<AiraConversation> findTop30ByUsernameIgnoreCaseOrderByUpdatedAtDesc(String username);
    Optional<AiraConversation> findByIdAndUsernameIgnoreCase(Long id, String username);
    long countByUsernameIgnoreCase(String username);
}
