package br.com.branlycrm.api.identity;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class IdentityService implements UserDetailsService {
    private final UserAccountRepository accounts;
    public IdentityService(UserAccountRepository accounts) { this.accounts = accounts; }
    @Override
    public UserDetails loadUserByUsername(String email) {
        return new CrmPrincipal(accounts.findByEmail(email.strip().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials")));
    }
    public boolean isCurrent(CrmPrincipal principal) {
        return accounts.findById(principal.subjectId()).filter(account -> account.active()
            && account.emailVerified() && account.authVersion() == principal.authVersion()).isPresent();
    }
}