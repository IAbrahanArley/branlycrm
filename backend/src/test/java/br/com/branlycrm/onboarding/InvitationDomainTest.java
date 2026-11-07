package br.com.branlycrm.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.identity.mail.MailMessage;
import br.com.branlycrm.identity.mail.PublicOrigin;
import br.com.branlycrm.onboarding.domain.InvitationEmail;
import br.com.branlycrm.onboarding.domain.InvitationToken;
import br.com.branlycrm.onboarding.domain.NewAccount;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TeamPolicy;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Rules of the invitation domain that need no database (ADR-028). */
class InvitationDomainTest {
  @Test
  void tokensAreRandomBase64UrlAndOnlyTheirHashIsComparable() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      InvitationToken token = InvitationToken.generate();
      assertThat(token.raw()).matches("^[A-Za-z0-9_-]{43}$");
      assertThat(seen.add(token.raw())).isTrue();
      assertThat(token.hash()).hasSize(32);
      assertThat(InvitationToken.parse(token.raw()).orElseThrow().hash()).isEqualTo(token.hash());
      assertThat(token.toString()).doesNotContain(token.raw());
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"short", "has spaces in it aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "../../etc/passwd"})
  void malformedTokensAreSimplyInvalid(String raw) {
    assertThat(InvitationToken.parse(raw)).isEmpty();
  }

  @Test
  void emailsAreNormalizedLikeAccounts() {
    assertThat(InvitationEmail.of("  Ana.Silva@Example.TEST ").value())
        .isEqualTo("ana.silva@example.test");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"no-at-sign", "two@@example.test", "a@b", "spaces in@example.test"})
  void invalidEmailsReportTheField(String raw) {
    assertThatThrownBy(() -> InvitationEmail.of(raw))
        .isInstanceOfSatisfying(
            InvalidInputException.class, error -> assertThat(error.fields()).contains("email"));
  }

  @Test
  void passwordsNeedTwelveCharactersAndAtMostSeventyTwoBytes() {
    assertThat(new NewAccount(" Synthetic ", "twelve-chars").displayName()).isEqualTo("Synthetic");
    assertInvalid(() -> new NewAccount("Synthetic", "eleven-char"), "password");
    // 36 two-byte characters are 72 bytes: accepted; 25 three-byte ones (75) are refused.
    assertThat(new NewAccount("Synthetic", "ã".repeat(36)).password()).hasSize(36);
    assertInvalid(() -> new NewAccount("Synthetic", "€".repeat(25)), "password");
    assertInvalid(() -> new NewAccount("  ", "long-enough-password"), "displayName");
    assertInvalid(() -> new NewAccount(null, "long-enough-password"), "displayName");
    assertThat(new NewAccount("Synthetic", "long-enough-password").toString())
        .doesNotContain("long-enough-password");
  }

  @Test
  void publicOriginOnlyAcceptsHttpsOrLocalhostOrigins() {
    assertThat(PublicOrigin.of("http://localhost:8088").invitationLink("abc"))
        .isEqualTo("http://localhost:8088/convite#token=abc");
    assertThat(PublicOrigin.of("https://app.example.test").secure()).isTrue();
    for (String bad :
        new String[] {
          "http://app.example.test", "https://app.example.test/path", "ftp://localhost"
        }) {
      assertThatThrownBy(() -> PublicOrigin.of(bad))
          .as(bad)
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void mailMessagesNeverPrintTheirBody() {
    MailMessage message = new MailMessage("a@example.test", "Convite", "link secreto");
    assertThat(message.toString()).doesNotContain("link secreto").doesNotContain("a@example.test");
  }

  @Test
  void invitationReachMatchesTeamChanges() {
    for (ClinicRole role : ClinicRole.values()) {
      assertThat(TeamPolicy.mayInvite(ClinicRole.OWNER, role)).as("OWNER -> %s", role).isTrue();
      assertThat(TeamPolicy.mayInvite(ClinicRole.MANAGER, role))
          .as("MANAGER -> %s", role)
          .isEqualTo(role == ClinicRole.ATTENDANT || role == ClinicRole.VIEWER);
      assertThat(TeamPolicy.mayInvite(ClinicRole.ATTENDANT, role)).isFalse();
      assertThat(TeamPolicy.mayInvite(ClinicRole.VIEWER, role)).isFalse();
    }
    assertThat(TeamPolicy.mayInvite(ClinicRole.OWNER, null)).isFalse();
    assertThat(TeamPolicy.mayInvite(null, ClinicRole.VIEWER)).isFalse();
  }

  private static void assertInvalid(Runnable call, String field) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            InvalidInputException.class, error -> assertThat(error.fields()).contains(field));
  }
}
