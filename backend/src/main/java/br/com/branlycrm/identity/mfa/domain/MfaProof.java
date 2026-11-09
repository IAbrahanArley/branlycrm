package br.com.branlycrm.identity.mfa.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Kept in the session after a successful code. It only counts for the same subject and the same
 * auth_version: a new login, a factor replacement or a reset makes it worthless.
 */
public record MfaProof(UUID subjectId, long authVersion, Instant verifiedAt)
    implements Serializable {
  public MfaProof {
    if (subjectId == null || verifiedAt == null) {
      throw new IllegalArgumentException("Subject and instant are required");
    }
  }

  public boolean provesFor(UUID subject, long version) {
    return subjectId.equals(subject) && authVersion == version;
  }
}
