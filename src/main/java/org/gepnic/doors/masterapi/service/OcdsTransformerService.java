package org.gepnic.doors.masterapi.service;

import org.gepnic.doors.masterapi.dto.ocds.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OcdsTransformerService {

    public OcdsReleasePackage transformToOcds(List<Map<String, Object>> rawData) {
        List<OcdsRelease> releases = rawData.stream()
                .map(this::mapToRelease)
                .collect(Collectors.toList());

        return OcdsReleasePackage.builder()
                .uri("https://gepnic.gov.in/ocds/release/" + UUID.randomUUID())
                .version("1.1")
                .releases(releases)
                .build();
    }

    private OcdsRelease mapToRelease(Map<String, Object> raw) {
        Map<String, Object> tenderMap = (Map<String, Object>) raw.get("TenderDetails");
        List<Map<String, Object>> boqList = (List<Map<String, Object>>) raw.get("BoQDetails");
        List<Map<String, Object>> awardList = (List<Map<String, Object>>) raw.get("AwardedBidderDetails");

        String tenderId = String.valueOf(tenderMap.get("TenderID"));

        // Map BoQ to Items
        List<OcdsItem> items = (boqList == null) ? Collections.emptyList() : boqList.stream()
                .map(b -> OcdsItem.builder()
                        .id(String.valueOf(b.get("SlNo")))
                        .description(String.valueOf(b.get("ItemDescription")))
                        .quantity(Double.valueOf(String.valueOf(b.getOrDefault("Quantity", 0))))
                        .unit(String.valueOf(b.get("Units")))
                        .build())
                .collect(Collectors.toList());

        // Map Awards
        List<OcdsAward> awards = (awardList == null) ? Collections.emptyList() : awardList.stream()
                .map(a -> OcdsAward.builder()
                        .id(String.valueOf(a.get("GeBID")))
                        .suppliersName(String.valueOf(a.get("BidderCompanyName")))
                        .value(Double.valueOf(String.valueOf(a.getOrDefault("AwardedValue", 0))))
                        .build())
                .collect(Collectors.toList());

        OcdsTender tender = OcdsTender.builder()
                .id(tenderId)
                .title(String.valueOf(tenderMap.get("TenderTitle")))
                .mainProcurementCategory(String.valueOf(tenderMap.get("TenderCategory")))
                .items(items)
                .build();
String rawDate = String.valueOf(tenderMap.get("AllotmentDate"));
     return OcdsRelease.builder()
    .ocid("ocds-gepnic-" + tenderId)
    .id("release-" + UUID.randomUUID().toString().substring(0, 8))
    .date(rawDate.contains("T") ? rawDate : Instant.now().toString()) // Fallback to current time
    .tag(Collections.singletonList("award"))
    .initiationType("tender")
    .tender(tender)
    .awards(awards)
    .build();
    }
}