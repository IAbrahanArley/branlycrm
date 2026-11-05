package br.com.branlycrm.api.identity;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class IdentityServiceTests {
    @Test void normalizesEmailBeforeLookup() {
        var repository = mock(UserAccountRepository.class);
        var account = account(true, true, 0);
        when(repository.findByEmail("user@example.com")).thenReturn(Optional.of(account));
        var principal = new IdentityService(repository).loadUserByUsername(" USER@EXAMPLE.COM ");
        assertThat(principal.isEnabled()).isTrue();
        verify(repository).findByEmail("user@example.com");
    }
    @Test void rejectsDisabledAndVersionRevokedSessions() {
        var repository = mock(UserAccountRepository.class);
        var account = account(true, true, 0);
        var principal = new CrmPrincipal(account);
        var service = new IdentityService(repository);
        when(repository.findById(account.subjectId())).thenReturn(Optional.of(
            new UserAccount(account.subjectId(), account.email(), "User", "{bcrypt}hash", false, true, 1)));
        assertThat(service.isCurrent(principal)).isFalse();
        when(repository.findById(account.subjectId())).thenReturn(Optional.of(
            new UserAccount(account.subjectId(), account.email(), "User", "{bcrypt}hash", true, true, 1)));
        assertThat(service.isCurrent(principal)).isFalse();
    }
    @Test void unverifiedEmailCannotAuthenticate() {
        assertThat(new CrmPrincipal(account(true, false, 0)).isEnabled()).isFalse();
    }
    private UserAccount account(boolean active, boolean verified, long version) {
        return new UserAccount(UUID.randomUUID(), "user@example.com", "User", "{bcrypt}hash", active, verified, version);
    }
}