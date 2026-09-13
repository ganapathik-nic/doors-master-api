package org.gepnic.doors.masterapi.repository;

import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReportMappingRepository extends JpaRepository<SqlTemplate, Long> {
    java.util.Optional<SqlTemplate> findByUniqueName(String uniqueName);

    /**
     * 1. TEMPLATE LIST: Finds templates where at least one authorized agent 
     * matches an agent authorized for the user.
     */
    @Query(value = "SELECT DISTINCT t.query_id, t.unique_name, t.parameters " +
                   "FROM sql_templates t " +
                   "JOIN sql_template_authorized_agents staa ON t.query_id = staa.query_id " +
                   "JOIN user_authorized_agents uaa ON staa.agent_id = uaa.agent_id " +
                   "WHERE uaa.user_name = :username " +
                   "AND t.is_active = true " +
                   "AND UPPER(t.status) = 'APPROVED'", 
           nativeQuery = true)
    List<Object[]> findTemplatesByAgentIntersection(@Param("username") String username);

    /**
     * 2. AGENT LIST: Finds agents that are authorized for BOTH the template 
     * AND the specific user.
     */
    @Query(value = "SELECT DISTINCT a.agent_id, a.display_name " +
                   "FROM agents a " +
                   "JOIN user_authorized_agents uaa ON a.agent_id = uaa.agent_id " +
                   "JOIN sql_template_authorized_agents staa ON a.agent_id = staa.agent_id " +
                   "JOIN sql_templates t ON t.query_id = staa.query_id " +
                   "WHERE uaa.user_name = :username " +
                   "AND t.is_active = true AND t.status = 'APPROVED' " +
                   "AND staa.query_id = :queryId " +
                   "AND a.is_active = true", 
           nativeQuery = true)
    List<Object[]> findIntersectionAgents(@Param("username") String username, @Param("queryId") Long queryId);

    /**
     * 3. SQL FETCH: Retrieves the SQL text by ID.
     */
    @Query(value = "SELECT sql_text FROM sql_templates WHERE query_id = :queryId", nativeQuery = true)
    String findSqlByQueryId(@Param("queryId") Long queryId);
    @Query(value = "SELECT sql_text FROM sql_templates WHERE unique_name = :uniqueName", nativeQuery = true)
    String findSqlByUniqueName(@Param("uniqueName") String uniqueName);
}
