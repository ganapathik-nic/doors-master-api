package org.gepnic.doors.masterapi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.gepnic.doors.masterapi.entity.ApiClient;
import org.gepnic.doors.masterapi.entity.ClientQueryMap;
import org.gepnic.doors.masterapi.model.SqlTemplate;
import org.gepnic.doors.masterapi.repository.ClientQueryMapRepository;
import org.gepnic.doors.masterapi.repository.SqlTemplateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClientSpecificDataSegregationService {

    private static final String SAFE_JSON_FIELD_PATTERN = "^[A-Za-z_][A-Za-z0-9_]*$";

    private final ClientQueryMapRepository mappingRepository;
    private final SqlTemplateRepository templateRepository;
    private final ObjectMapper objectMapper;

    public List<Map<String, Object>> apply(ApiClient client, String uniqueName,
                                            List<Map<String, Object>> responseData) {
        SqlTemplate template = templateRepository.findByUniqueName(uniqueName)
                .orElseThrow(() -> new SecurityException("Data-sharing function is not registered."));
        ClientQueryMap mapping = mappingRepository
                .findByClientIdAndQueryId(client.getClientId(), template.getQueryId())
                .orElseThrow(() -> new SecurityException(
                        "Client is not authorized for the requested data-sharing function."));

        String column = trimToNull(mapping.getResponseFilterColumn());
        String expectedValue = trimToNull(mapping.getResponseFilterValue());
        if (column == null && expectedValue == null) {
            return responseData;
        }
        if (column == null || expectedValue == null || !isSafeJsonField(column)) {
            throw new SecurityException("Invalid client-specific data segregation policy.");
        }

        Object normalized = objectMapper.convertValue(responseData, Object.class);
        FilterResult filtered = filter(normalized, column, expectedValue);
        List<Map<String, Object>> result = filtered.foundField() && filtered.included()
                ? objectMapper.convertValue(filtered.value(), new TypeReference<>() {})
                : List.of();

        log.info("DOORS-SEGREGATION: clientId={}, function={}, field={}, returnedEnvelopes={}",
                client.getClientId(), uniqueName, column, result.size());
        return result;
    }

    private FilterResult filter(Object value, String column, String expectedValue) {
        if (value instanceof Map<?, ?> source) {
            Optional<? extends Map.Entry<?, ?>> directField = source.entrySet().stream()
                    .filter(entry -> column.equalsIgnoreCase(String.valueOf(entry.getKey())))
                    .findFirst();
            if (directField.isPresent()) {
                boolean matches = valuesEqual(directField.get().getValue(), expectedValue);
                return new FilterResult(true, matches, matches ? value : null);
            }

            Map<String, Object> filteredMap = new LinkedHashMap<>();
            boolean found = false;
            boolean includedDescendant = false;
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                Object child = entry.getValue();
                if (child instanceof Map<?, ?> || child instanceof Collection<?>) {
                    FilterResult childResult = filter(child, column, expectedValue);
                    found |= childResult.foundField();
                    if (childResult.foundField()) {
                        if (childResult.included()) {
                            filteredMap.put(String.valueOf(entry.getKey()), childResult.value());
                            includedDescendant = true;
                        } else if (child instanceof Collection<?>) {
                            filteredMap.put(String.valueOf(entry.getKey()), List.of());
                        }
                    } else {
                        filteredMap.put(String.valueOf(entry.getKey()), child);
                    }
                } else {
                    filteredMap.put(String.valueOf(entry.getKey()), child);
                }
            }
            return new FilterResult(found, !found || includedDescendant, filteredMap);
        }

        if (value instanceof Collection<?> source) {
            List<FilterResult> childResults = new ArrayList<>();
            boolean found = false;
            for (Object child : source) {
                FilterResult childResult = filter(child, column, expectedValue);
                childResults.add(childResult);
                found |= childResult.foundField();
            }

            List<Object> filteredList = new ArrayList<>();
            for (FilterResult childResult : childResults) {
                // Once this collection is identified as a record collection for
                // the field, records with a missing field are excluded as well.
                if (childResult.included()
                        && (!found || childResult.foundField())
                        && childResult.value() != null) {
                    filteredList.add(childResult.value());
                }
            }
            return new FilterResult(found, !found || !filteredList.isEmpty(), filteredList);
        }

        return new FilterResult(false, true, value);
    }

    private boolean valuesEqual(Object actual, String expected) {
        if (actual == null) return false;
        try {
            return new BigDecimal(String.valueOf(actual).trim())
                    .compareTo(new BigDecimal(expected.trim())) == 0;
        } catch (NumberFormatException ignored) {
            return String.valueOf(actual).trim().equals(expected.trim());
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static boolean isSafeJsonField(String fieldName) {
        return fieldName != null && fieldName.matches(SAFE_JSON_FIELD_PATTERN);
    }

    private record FilterResult(boolean foundField, boolean included, Object value) {}
}
