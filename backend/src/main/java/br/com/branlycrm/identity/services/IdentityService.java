package br.com.branlycrm.identity.services;

import br.com.branlycrm.identity.repositories.UserAccountRepository;
import br.com.branlycrm.identity.security.CrmPrincipal;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService implements UserDetailsService {
  private final UserAccountRepository accounts;

  public IdentityService(UserAccountRepository accounts) {
    this.accounts = accounts;
  }

  @Override
  @Transactional(readOnly = true)
  public UserDetails loadUserByUsername(String email) {
    if (email == null) throw new UsernameNotFoundException("Invalid credentials");
    return accounts
        .findByEmail(normalize(email))
        .map(CrmPrincipal::new)
        .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }

  /** Disabling, unverifying or changing credentials bumps auth_version and revokes the session. */
  @Transactional(readOnly = true)
  public boolean isCurrent(CrmPrincipal principal) {
    return accounts
        .findById(principal.subjectId())
        .filter(account -> account.canAuthenticate())
        .filter(account -> account.authVersion() == principal.authVersion())
        .isPresent();
  }

  static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
