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
  private final boolean usable;

  /**
   * Always "enabled" for Spring Security: an inactive or unverified account is refused only after
   * the password check (SecurityConfiguration), so every refusal costs one hash comparison and
   * response time does not reveal which e-mails exist.
   */
  public CrmPrincipal(UserAccount account) {
    super(account.email(), account.passwordHash(), true, true, true, true, List.of());
    this.subjectId = account.subjectId();
    this.authVersion = account.authVersion();
    this.usable = account.canAuthenticate();
  }

  /** Active and verified at load time; checked after the password, then on every request. */
  public boolean usable() {
    return usable;
  }

  public UUID subjectId() {
    return subjectId;
  }

  public long authVersion() {
    return authVersion;
  }
}
