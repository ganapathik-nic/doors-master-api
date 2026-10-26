package org.gepnic.doors.masterapi.inbound;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class WriteServiceDetailsTest {
 @Test void acceptsHumanReadablePunctuationAndUnicode(){
  for(String title:new String[]{"Agreement – Automatic Versioning","Agreement: UAT (2026) / Dept. A & B","Supplier’s Agreement, phase #2!","ஒப்பந்தம் – புதுப்பிப்பு"})
   assertDoesNotThrow(()->WriteGovernanceService.validateServiceDetails(title,"Receive agreement JSON."));
 }
 @Test void identifiesMissingAndOverlongFields(){
  check(null,"Valid purpose","Service Title is required.");
  check("   ","Valid purpose","Service Title is required.");
  assertTrue(assertThrows(IllegalArgumentException.class,()->WriteGovernanceService.validateServiceDetails("ab","purpose")).getMessage().startsWith("Service Title must contain"));
  assertTrue(assertThrows(IllegalArgumentException.class,()->WriteGovernanceService.validateServiceDetails("x".repeat(101),"purpose")).getMessage().startsWith("Service Title must contain"));
  check("Agreement"," ","Purpose is required.");
  check("Agreement","x".repeat(4001),"Purpose must not exceed 4,000 characters.");
  assertDoesNotThrow(()->WriteGovernanceService.validateServiceDetails("x".repeat(100),"x".repeat(4000)));
 }
 @Test void rejectsControlCharactersWithTitleSpecificMessage(){
  for(String title:new String[]{"Agreement\nnew","Agreement\u0000","Agreement\u202E","Agreement\u2028new"})
   assertTrue(assertThrows(IllegalArgumentException.class,()->WriteGovernanceService.validateServiceDetails(title,"purpose")).getMessage().startsWith("Service Title must be a single line"));
 }
 private void check(String title,String purpose,String expected){assertEquals(expected,assertThrows(IllegalArgumentException.class,()->WriteGovernanceService.validateServiceDetails(title,purpose)).getMessage());}
}
