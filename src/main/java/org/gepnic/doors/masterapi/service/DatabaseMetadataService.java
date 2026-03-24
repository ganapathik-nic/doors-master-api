package org.gepnic.doors.masterapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseMetadataService {

    private final DataSource dataSource;

    /**
     * Scans the PostgreSQL information schema to build a dynamic context 
     * string for the AI Agent.
     */
    public String getAarveeContext() {
        StringBuilder context = new StringBuilder("LIVE DOORS REGISTRY SCHEMA:\n");
        
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            
            // 1. Fetch tables only from the 'public' schema
            ResultSet tables = metaData.getTables(null, "public", "%", new String[]{"TABLE"});

            while (tables.next()) {
                String tableName = tables.getString("TABLE_NAME");
                
                // 2. Skip internal/system-specific tables to save tokens
                if (isInternalTable(tableName)) continue;

                context.append("- Table '").append(tableName).append("': [");
                
                // 3. Fetch columns for the current table
                ResultSet columns = metaData.getColumns(null, null, tableName, "%");
                List<String> columnNames = new ArrayList<>();
                while (columns.next()) {
                    columnNames.add(columns.getString("COLUMN_NAME"));
                }
                context.append(String.join(", ", columnNames)).append("]\n");
            }
        } catch (SQLException e) {
            log.error("AIra-METADATA: Failed to read DB dictionary", e);
            return "Note: Dynamic schema retrieval unavailable. Use internal knowledge.";
        }
        
        return context.toString();
    }

    private boolean isInternalTable(String name) {
        String lower = name.toLowerCase();
        return lower.startsWith("flyway") || 
               lower.contains("hibernate") || 
               lower.contains("shedlock") ||
               lower.contains("undo_");
    }
}