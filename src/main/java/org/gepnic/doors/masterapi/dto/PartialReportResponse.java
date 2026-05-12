import java.util.List;
import java.util.Map;

public record PartialReportResponse(
    List<Map<String, Object>> data,
    List<String> offlineAgents,
    String summaryMessage
) {}