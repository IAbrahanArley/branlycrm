package br.com.branlycrm.identity.security;

import br.com.branlycrm.identity.domain.UserAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.userdetails.User;

/**
 * Authenticated subject. Carries no clinic role: roles are per membership and never global
 * authorities in the session (ADR-015).
 */
public class CrmPrincipal extends User {
  private static final long serialVersionUID = 1L;
  private final UUID subjectId;
  private final long authVersion;

  public CrmPrincipal(UserAccount account) {
    super(
        account.email(),
        account.passwordHash(),
        account.canAuthenticate(),
        true,
        true,
        true,
        List.of());
    this.subjectId = account.subjectId();
    this.authVersion = account.authVersion();
  }

  public UUID subjectId() {
    return subjectId;
  }

  public long authVersion() {
    return authVersion;
  }
}
