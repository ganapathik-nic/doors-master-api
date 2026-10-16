package org.gepnic.doors.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.*;

/** Version one is an explicit allowlist, not arbitrary SQL or inferred JSON routing. */
public final class AgreementContract {
    private AgreementContract() {}
    private static final Set<String> FIELDS=Set.of("tender_id","agreement_no","agreement_date",
        "party_1","party_2","tender_name","tender_date","tender_nit_ref_no","tender_nit_ref_date",
        "loa_ref_no","loa_sign_date","epg_part_1_ref_no","epg_part_1_amount","epg_part_1_expiry_date",
        "epg_part_1_claim_expiry_date","epg_part_2_ref_no","epg_part_2_amount","epg_part_2_expiry_date",
        "epg_part_2_claim_expiry_date","date_of_completion");
    public static JsonNode validate(String raw) {
        try {
            JsonNode n=InboundProtocol.JSON.readTree(raw);
            object(n,FIELDS);
            text(n,"tender_id",100,true);text(n,"agreement_no",200,true);
            text(n,"agreement_date",10,true);
            if(n.has("party_1")) { object(n.get("party_1"),Set.of("name","type","vendor_id"));
                for(String k:List.of("name","type","vendor_id")) text(n.get("party_1"),k,300,false); }
            if(n.has("party_2")) { object(n.get("party_2"),Set.of("name","type","user_name"));
                for(String k:List.of("name","type","user_name")) text(n.get("party_2"),k,300,false); }
            for(String field:FIELDS) {
                if(field.startsWith("party_")) continue;
                String value=text(n,field,1000,false);
                if(value==null) continue;
                if(field.endsWith("date") || field.equals("date_of_completion")) {
                    if(!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid date: "+field);
                    LocalDate.parse(value);
                }
                if(field.endsWith("amount") && !value.matches("\\d{1,16}(\\.\\d{1,2})?"))
                    throw new IllegalArgumentException("Invalid decimal string: "+field);
            }
            return n;
        } catch(IllegalArgumentException e) {throw e;}
        catch(Exception e) {throw new IllegalArgumentException("Invalid agreement JSON or field value");}
    }
    private static void object(JsonNode n,Set<String> fields) {
        if(n==null || !n.isObject()) throw new IllegalArgumentException("Expected JSON object");
        n.fieldNames().forEachRemaining(k->{if(!fields.contains(k))throw new IllegalArgumentException("Unknown field: "+k);});
    }
    private static String text(JsonNode n,String field,int max,boolean required) {
        JsonNode v=n.get(field);
        if(v==null) {if(required)throw new IllegalArgumentException("Missing field: "+field);return null;}
        if(!v.isTextual() || v.textValue().isBlank() || v.textValue().length()>max
            || !v.textValue().equals(v.textValue().strip()) || v.textValue().chars().anyMatch(c->c<32))
            throw new IllegalArgumentException("Invalid text: "+field);
        return v.textValue();
    }
}
