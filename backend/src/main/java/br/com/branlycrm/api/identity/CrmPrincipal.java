package br.com.branlycrm.api.identity;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.userdetails.User;

public class CrmPrincipal extends User {
    private static final long serialVersionUID = 1L;
    private final UUID subjectId;
    private final long authVersion;
    public CrmPrincipal(UserAccount account) {
        super(account.email(), account.passwordHash(), account.active() && account.emailVerified(),
            true, true, true, List.of());
        this.subjectId = account.subjectId();
        this.authVersion = account.authVersion();
    }
    public UUID subjectId() { return subjectId; }
    public long authVersion() { return authVersion; }
}