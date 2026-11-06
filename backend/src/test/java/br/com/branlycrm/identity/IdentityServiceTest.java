package br.com.branlycrm.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.branlycrm.identity.domain.UserAccount;
import br.com.branlycrm.identity.repositories.UserAccountRepository;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.services.IdentityService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class IdentityServiceTest {
  private final UserAccountRepository accounts = mock(UserAccountRepository.class);
  private final IdentityService service = new IdentityService(accounts);

  @Test
  void normalizesEmailBeforeLookup() {
    when(accounts.findByEmail("user@example.test"))
        .thenReturn(Optional.of(account(UUID.randomUUID(), true, true, 0)));
    assertThat(((CrmPrincipal) service.loadUserByUsername(" USER@Example.TEST ")).usable())
        .isTrue();
    verify(accounts).findByEmail("user@example.test");
  }

  @Test
  void unknownAndMissingEmailsFailWithTheSameGenericError() {
    when(accounts.findByEmail("absent@example.test")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.loadUserByUsername("absent@example.test"))
        .isInstanceOf(UsernameNotFoundException.class)
        .hasMessage("Invalid credentials");
    assertThatThrownBy(() -> service.loadUserByUsername(null))
        .isInstanceOf(UsernameNotFoundException.class)
        .hasMessage("Invalid credentials");
  }

  @Test
  void inactiveOrUnverifiedAccountsCannotAuthenticate() {
    UUID id = UUID.randomUUID();
    // Refused after the password check, not by Spring's pre-check: see SecurityConfiguration.
    assertThat(new CrmPrincipal(account(id, true, false, 0)).usable()).isFalse();
    assertThat(new CrmPrincipal(account(id, false, true, 0)).usable()).isFalse();
    assertThat(new CrmPrincipal(account(id, false, false, 0)).isEnabled()).isTrue();
    verifyNoInteractions(accounts);
  }

  @Test
  void sessionIsCurrentOnlyWhileAccountIsActiveVerifiedAndOnTheSameVersion() {
    UUID id = UUID.randomUUID();
    CrmPrincipal principal = new CrmPrincipal(account(id, true, true, 3));
    when(accounts.findById(id)).thenReturn(Optional.of(account(id, true, true, 3)));
    assertThat(service.isCurrent(principal)).isTrue();
    when(accounts.findById(id)).thenReturn(Optional.of(account(id, true, true, 4)));
    assertThat(service.isCurrent(principal)).isFalse();
    when(accounts.findById(id)).thenReturn(Optional.of(account(id, false, true, 3)));
    assertThat(service.isCurrent(principal)).isFalse();
    when(accounts.findById(id)).thenReturn(Optional.of(account(id, true, false, 3)));
    assertThat(service.isCurrent(principal)).isFalse();
    when(accounts.findById(id)).thenReturn(Optional.empty());
    assertThat(service.isCurrent(principal)).isFalse();
  }

  @Test
  void accountTextNeverCarriesThePasswordHash() {
    assertThat(account(UUID.randomUUID(), true, true, 0).toString())
        .doesNotContain("{bcrypt}", "user@example.test");
  }

  private static UserAccount account(UUID id, boolean active, boolean verified, long version) {
    return new UserAccount(
        id, "user@example.test", "User", "{bcrypt}synthetic", active, verified, version);
  }
}
