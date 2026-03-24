package org.gepnic.doors.masterapi.dto;
import java.util.Map;
//public record QueryRequest(String sql, String outputType, Map<String, Object> parameters )// Added for :param support) {}

public record QueryRequest(String sql, String outputType, Map<String, Object> parameters )  {}  
