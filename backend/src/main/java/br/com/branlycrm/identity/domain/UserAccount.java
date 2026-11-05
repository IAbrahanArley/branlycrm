package br.com.branlycrm.identity.domain;

import java.util.UUID;

/** Global identity stored in the control database; roles belong to clinic memberships. */
public record UserAccount(
    UUID subjectId,
    String email,
    String displayName,
    String passwordHash,
    boolean active,
    boolean emailVerified,
    long authVersion) {

  public boolean canAuthenticate() {
    return active && emailVerified;
  }

  @Override
  public String toString() {
    return "UserAccount[subjectId=" + subjectId + "]";
  }
}
