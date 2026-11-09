package br.com.branlycrm.identity.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.identity.mfa.domain.MfaState;
import br.com.branlycrm.identity.security.MfaGateFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The allow lists of the gate: exact method and servlet path, deny by default. */
class MfaGateFilterTest {
  @Test
  void pendingSessionsReachOnlyTheSessionBasicsAndVerify() {
    for (String route :
        new String[] {
          "GET /v1/auth/csrf",
          "POST /v1/auth/logout",
          "GET /v1/auth/me",
          "GET /v1/auth/mfa",
          "GET /actuator/health",
          "POST /v1/auth/mfa/verify"
        }) {
      assertThat(allows(MfaState.MFA_PENDING, route)).as(route).isTrue();
    }
    for (String route :
        new String[] {
          "GET /v1/auth/clinics",
          "POST /v1/auth/mfa/totp/setup",
          "POST /v1/auth/mfa/totp/confirm",
          "POST /v1/auth/mfa/recovery-codes",
          "DELETE /v1/auth/mfa/totp",
          "POST /v1/invitations/accept",
          "GET /v1/auth/me/",
          "HEAD /v1/auth/me",
          "POST /v1/auth/me",
          "GET /v1/auth/MFA",
          "GET /error"
        }) {
      assertThat(allows(MfaState.MFA_PENDING, route)).as(route).isFalse();
    }
  }

  @Test
  void enrollmentReachesSetupAndConfirmOnly() {
    assertThat(allows(MfaState.ENROLLMENT_REQUIRED, "POST /v1/auth/mfa/totp/setup")).isTrue();
    assertThat(allows(MfaState.ENROLLMENT_REQUIRED, "POST /v1/auth/mfa/totp/confirm")).isTrue();
    assertThat(allows(MfaState.ENROLLMENT_REQUIRED, "GET /v1/auth/me")).isTrue();
    for (String route :
        new String[] {
          "POST /v1/auth/mfa/verify",
          "POST /v1/auth/mfa/recovery-codes",
          "DELETE /v1/auth/mfa/totp",
          "GET /v1/agencies",
          "PUT /v1/auth/clinic/" + UUID.randomUUID(),
          "POST /v1/invitations/preview"
        }) {
      assertThat(allows(MfaState.ENROLLMENT_REQUIRED, route)).as(route).isFalse();
    }
  }

  @Test
  void completeSessionsReachEverythingButVerify() {
    assertThat(allows(MfaState.COMPLETE, "GET /v1/clinic/team")).isTrue();
    assertThat(allows(MfaState.COMPLETE, "DELETE /v1/auth/mfa/totp")).isTrue();
    assertThat(allows(MfaState.COMPLETE, "POST /v1/auth/mfa/verify")).isFalse();
  }

  private static boolean allows(MfaState state, String route) {
    int space = route.indexOf(' ');
    return MfaGateFilter.allows(state, route.substring(0, space), route.substring(space + 1));
  }
}
