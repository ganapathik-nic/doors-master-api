package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MappingService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * ASPECT: Governance Mapping Synchronization
     * Matches the method signature called by QueryGovernanceController.
     */
     @Transactional
public void updateAgentMappings(Long queryId, List<String> agentInstanceCodes) {
    log.info("GEMS-SERVICE: Syncing alphanumeric IDs for QueryID: {}", queryId);

    // 1. Clear existing rows
    String deleteSql = "DELETE FROM sql_template_authorized_agents WHERE query_id = ?";
    jdbcTemplate.update(deleteSql, queryId);

    // 2. Batch Insert for efficiency
    if (agentInstanceCodes != null && !agentInstanceCodes.isEmpty()) {
        String insertSql = "INSERT INTO sql_template_authorized_agents (query_id, agent_id) VALUES (?, ?)";
        
        jdbcTemplate.batchUpdate(insertSql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setLong(1, queryId);
                ps.setString(2, agentInstanceCodes.get(i));
            }

            @Override
            public int getBatchSize() {
                return agentInstanceCodes.size();
            }
        });
    }
    
    log.info("GEMS-SERVICE: Successfully mapped {} agents to query {}", 
             agentInstanceCodes != null ? agentInstanceCodes.size() : 0, queryId);
}
}