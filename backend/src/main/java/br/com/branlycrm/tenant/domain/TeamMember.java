package br.com.branlycrm.tenant.domain;

import java.util.UUID;

/** Membership of a person in one clinic, as seen by team management. */
public record TeamMember(
    UUID subjectId, String displayName, String email, ClinicRole role, boolean active) {

  @Override
  public String toString() {
    // Keep names and e-mail addresses out of logs.
    return "TeamMember[subjectId=" + subjectId + ", role=" + role + ", active=" + active + "]";
  }
}
