package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * REPOSITORY: Agent Registry
 * Manages the distributed infrastructure nodes (Dev-01, NDCSP, GEMS, etc.)
 */
@Repository
public interface AgentRepository extends JpaRepository<Agent, String> {
    
    /**
     * ASPECT 1: Filtering by Sandbox Status (Dynamic Query)
     * Used by the Query Submission Form to restrict Dry-Runs to safe nodes.
     * Logic: If isSandbox is null, returns all active. If true/false, filters accordingly.
     */
    @Query("SELECT a FROM Agent a WHERE a.isActive = true " +
           "AND (:isSandbox IS NULL OR a.isSandbox = :isSandbox)")
    List<Agent> findAllActive(@Param("isSandbox") Boolean isSandbox);

    /**
     * ASPECT 1b: Explicit Sandbox Filter (Method Name Query)
     * Specifically added to resolve the 'cannot find symbol' error in AgentService.
     */
    List<Agent> findByIsActiveTrueAndIsSandbox(Boolean isSandbox);

    /**
     * ASPECT 2: Standard Active List
     * Used by the Admin Governance dashboard to show all available execution nodes.
     */
    List<Agent> findByIsActiveTrue();
    
    /**
     * ASPECT 3: Instance Identification
     * Finds specific agent by the instance code (e.g., Regional Campus Code).
     */
    Optional<Agent> findByAgentInstanceCode(String code);

    /**
     * ASPECT 4: Dashboard Metrics
     * Quick check for how many nodes are currently online/active in the registry.
     */
    long countByIsActiveTrue();

    /**
     * OPTIMIZATION (Optional/Debug):
     * If you only need minimal info for the dropdown to reduce JSON payload size.
     */
    @Query("SELECT a.agentId, a.displayName, a.baseUrl FROM Agent a WHERE a.isActive = true")
    List<Object[]> findActiveAgentMinimalInfo();
}