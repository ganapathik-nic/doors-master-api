package org.gepnic.doors.masterapi.service;
public final class GovernanceReviewRules {
 private GovernanceReviewRules(){}
 public static void requirePendingReviewer(String status,String proposer,String reviewer){
  if(!"PENDING".equals(status))throw new IllegalStateException("Only pending templates can be approved");
  if(reviewer.equalsIgnoreCase(proposer))throw new SecurityException("A different reviewer must approve the proposal");
 }
}
