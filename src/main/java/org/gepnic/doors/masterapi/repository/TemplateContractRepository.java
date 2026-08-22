package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.TemplateContract;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TemplateContractRepository extends JpaRepository<TemplateContract, Long> {
    Optional<TemplateContract> findByTemplateQueryIdAndIsCurrentTrue(Long templateId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select contract from TemplateContract contract " +
            "where contract.template.queryId = :templateId and contract.isCurrent = true")
    Optional<TemplateContract> findCurrentForUpdate(@Param("templateId") Long templateId);
    Optional<TemplateContract> findTopByTemplateQueryIdOrderByContractVersionDesc(Long templateId);
    Optional<TemplateContract> findTopByTemplateQueryIdAndContractStatusInOrderByContractVersionDesc(
            Long templateId, List<String> statuses);
    List<TemplateContract> findByTemplateQueryIdOrderByContractVersionDesc(Long templateId);
}
