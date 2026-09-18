package org.gepnic.doors.masterapi.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportCsvSecurityTest {
    @Test void neutralizesFormulaPrefixesIncludingWhitespace() {
        for (String value : new String[]{"=1+1", "+1+1", "-1+1", "@SUM(A1)", "  =1", "\tplain", "\rplain", "\nplain"}) {
            assertEquals("'" + value, ReportExportService.spreadsheetSafeCell(value));
        }
    }
    @Test void preservesOrdinaryTextAndEmptyCells() {
        assertEquals("", ReportExportService.spreadsheetSafeCell(null));
        assertEquals("", ReportExportService.spreadsheetSafeCell(""));
        assertEquals("123", ReportExportService.spreadsheetSafeCell("123"));
        assertEquals("ordinary, \"text\"", ReportExportService.spreadsheetSafeCell("ordinary, \"text\""));
    }
}
