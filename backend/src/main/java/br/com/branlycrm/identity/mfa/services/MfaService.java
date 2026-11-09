package br.com.branlycrm.identity.mfa.services;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.identity.mail.MailMessage;
import br.com.branlycrm.identity.mail.MailSender;
import br.com.branlycrm.identity.mfa.crypto.Base32;
import br.com.branlycrm.identity.mfa.crypto.MfaCrypto;
import br.com.branlycrm.identity.mfa.crypto.Totp;
import br.com.branlycrm.identity.mfa.domain.MfaAttempt;
import br.com.branlycrm.identity.mfa.domain.MfaEnrollment;
import br.com.branlycrm.identity.mfa.domain.MfaFactor;
import br.com.branlycrm.identity.mfa.domain.MfaProof;
import br.com.branlycrm.identity.mfa.domain.MfaRequirement;
import br.com.branlycrm.identity.mfa.domain.MfaSetup;
import br.com.branlycrm.identity.mfa.domain.MfaState;
import br.com.branlycrm.identity.mfa.domain.SealedSecret;
import br.com.branlycrm.identity.mfa.domain.TotpCode;
import br.com.branlycrm.identity.mfa.dto.response.MfaStatusView;
import br.com.branlycrm.identity.mfa.exceptions.MfaException;
import br.com.branlycrm.identity.mfa.exceptions.MfaException.Reason;
import br.com.branlycrm.identity.mfa.repositories.MfaRepository;
import br.com.branlycrm.identity.security.CrmPrincipal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * TOTP second factor (CRM-016). Every database write is a single SECURITY DEFINER call (its own
 * transaction), so the notice e-mail always goes out after the commit; a failed e-mail never fails
 * the operation. Secrets, URIs and codes exist only in memory and in the one response that shows
 * them; nothing here logs them.
 */
// Only the HTTP process: the key and the mail sender exist only there.
@ConditionalOnWebApplication
@Service
public class MfaService {
  static final String ISSUER = "Branly CRM";

  private static final Logger log = LoggerFactory.getLogger(MfaService.class);

  private final MfaRepository mfa;
  private final MfaCrypto crypto;
  private final MailSender mail;
  private final Clock clock;

  public MfaService(MfaRepository mfa, MfaCrypto crypto, MailSender mail, Clock clock) {
    this.mfa = mfa;
    this.crypto = crypto;
    this.mail = mail;
    this.clock = clock;
  }

  /** A valid proof answers without the database; otherwise the state is derived from it. */
  public MfaState state(CrmPrincipal principal, MfaProof proof) {
    if (proof != null && proof.provesFor(principal.subjectId(), principal.authVersion())) {
      return MfaState.COMPLETE;
    }
    return mfa.requirement(principal.subjectId()).stateWithoutProof();
  }

  public MfaStatusView status(CrmPrincipal principal, MfaProof proof) {
    MfaRequirement requirement = mfa.requirement(principal.subjectId());
    MfaState state =
        proof != null && proof.provesFor(principal.subjectId(), principal.authVersion())
            ? MfaState.COMPLETE
            : requirement.stateWithoutProof();
    // Counts only for a session that proved the factor: nothing about the account before that.
    long remaining =
        state == MfaState.COMPLETE && requirement.enrolled()
            ? mfa.remainingRecoveryCodes(principal.subjectId())
            : 0;
    return new MfaStatusView(
        state.name(), requirement.enrolled(), requirement.required(), remaining);
  }

  /** Exactly one of a TOTP code or a recovery code. */
  public MfaProof verify(CrmPrincipal principal, String code, String recoveryCode) {
    if ((code == null) == (recoveryCode == null)) throw new InvalidInputException("code");
    UUID subject = principal.subjectId();
    if (code != null) {
      TotpCode totp = TotpCode.of(code);
      MfaFactor factor = confirmedFactor(subject);
      requireCurrentCode(principal, factor, totp);
    } else {
      String normalized = MfaCrypto.normalizeRecoveryCode(recoveryCode);
      if (normalized == null) throw new InvalidInputException("recoveryCode");
      MfaFactor factor = confirmedFactor(subject);
      reserveAttempt(principal);
      if (!mfa.consumeRecovery(subject, crypto.hashRecoveryCode(subject, normalized))) {
        throw failure(principal);
      }
      mfa.resetFailures(subject);
      notify(
          principal,
          "Código de recuperação usado",
          "Um código de recuperação foi usado para entrar na sua conta. Cada código vale uma vez;"
              + " gere novos códigos se estiver acabando.");
    }
    return proof(principal);
  }

  /**
   * Stages a new secret. Replacing a confirmed factor requires its current code; the confirmed one
   * keeps working until the new one is confirmed.
   */
  public MfaSetup setup(CrmPrincipal principal, String currentCode) {
    UUID subject = principal.subjectId();
    MfaFactor existing = mfa.find(subject).orElse(null);
    if (existing != null && existing.confirmed()) {
      requireCurrentCode(principal, existing, TotpCode.of(currentCode));
    }
    byte[] secret = crypto.newSecret();
    try {
      SealedSecret sealed = crypto.seal(subject, secret);
      mfa.stage(subject, sealed);
      String base32 = Base32.encode(secret);
      return new MfaSetup(base32, otpauthUri(principal.getUsername(), base32), sealed.nonce());
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  /**
   * Confirms the secret staged by this session's setup ({@code setupNonce}) with a code of it. A
   * concurrent setup, an expired one or a setup of another session answers SETUP_EXPIRED.
   */
  public MfaEnrollment confirm(CrmPrincipal principal, String code, byte[] setupNonce) {
    TotpCode totp = TotpCode.of(code);
    UUID subject = principal.subjectId();
    MfaFactor factor = mfa.find(subject).orElse(null);
    if (factor == null
        || factor.pending() == null
        || !factor.pendingFresh()
        || setupNonce == null
        || !MessageDigest.isEqual(setupNonce, factor.pending().nonce())) {
      throw new MfaException(Reason.SETUP_EXPIRED);
    }
    reserveAttempt(principal);
    long step = matchingStep(principal, factor.pending(), totp);
    List<String> codes = crypto.newRecoveryCodes();
    String outcome = mfa.confirm(subject, factor.pending().nonce(), step, hashes(subject, codes));
    mfa.resetFailures(subject);
    boolean replaced =
        switch (outcome) {
          case "CONFIRMED" -> false;
          case "REPLACED" -> true;
          default -> throw new MfaException(Reason.SETUP_EXPIRED);
        };
    if (replaced) {
      notify(
          principal,
          "Segundo fator trocado",
          "O aplicativo autenticador da sua conta foi trocado e todas as sessões foram encerradas."
              + " Se não foi você, avise a equipe da plataforma imediatamente.");
    } else {
      notify(
          principal,
          "Segundo fator cadastrado",
          "Um aplicativo autenticador foi cadastrado como segundo fator da sua conta."
              + " Se não foi você, avise a equipe da plataforma imediatamente.");
    }
    return new MfaEnrollment(codes, proof(principal), replaced);
  }

  /** New recovery codes; the previous ones stop working. Needs the current code. */
  public List<String> regenerateRecoveryCodes(CrmPrincipal principal, String code) {
    TotpCode totp = TotpCode.of(code);
    UUID subject = principal.subjectId();
    MfaFactor factor = mfa.find(subject).filter(MfaFactor::confirmed).orElse(null);
    if (factor == null) throw new MfaException(Reason.NOT_ENROLLED);
    requireCurrentCode(principal, factor, totp);
    List<String> codes = crypto.newRecoveryCodes();
    if (!mfa.replaceRecoveryCodes(subject, hashes(subject, codes))) {
      throw new MfaException(Reason.NOT_ENROLLED);
    }
    notify(
        principal,
        "Novos códigos de recuperação",
        "Novos códigos de recuperação foram gerados para a sua conta; os anteriores deixaram de"
            + " valer. Se não foi você, avise a equipe da plataforma imediatamente.");
    return codes;
  }

  /** Optional factors only; revokes every session of the account. Needs the current code. */
  public void disable(CrmPrincipal principal, String code) {
    TotpCode totp = TotpCode.of(code);
    UUID subject = principal.subjectId();
    if (mfa.requirement(subject).required()) throw new MfaException(Reason.MANDATORY);
    MfaFactor factor = mfa.find(subject).filter(MfaFactor::confirmed).orElse(null);
    if (factor == null) throw new MfaException(Reason.NOT_ENROLLED);
    requireCurrentCode(principal, factor, totp);
    if (!mfa.disable(subject)) throw new MfaException(Reason.NOT_ENROLLED);
    notify(
        principal,
        "Segundo fator desativado",
        "O segundo fator da sua conta foi desativado e todas as sessões foram encerradas."
            + " Se não foi você, avise a equipe da plataforma imediatamente.");
  }

  private MfaFactor confirmedFactor(UUID subject) {
    // The gate only lets an enrolled session verify; a race with a reset answers like a bad code.
    return mfa.find(subject)
        .filter(MfaFactor::confirmed)
        .orElseThrow(() -> new MfaException(Reason.CODE_INVALID));
  }

  /**
   * Checks and consumes a code of the confirmed secret: once per step, never while locked, and only
   * after reserving the attempt.
   */
  private void requireCurrentCode(CrmPrincipal principal, MfaFactor factor, TotpCode code) {
    UUID subject = factor.subjectId();
    reserveAttempt(principal);
    long step = matchingStep(principal, factor.secret(), code);
    if (!mfa.consumeStep(subject, factor.secret().nonce(), step)) throw failure(principal);
    mfa.resetFailures(subject);
  }

  private long matchingStep(CrmPrincipal principal, SealedSecret sealed, TotpCode code) {
    byte[] secret = crypto.open(principal.subjectId(), sealed);
    try {
      OptionalLong step = Totp.match(secret, code.value(), Totp.step(clock.instant()));
      if (step.isEmpty()) throw failure(principal);
      return step.getAsLong();
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  /**
   * Counts the attempt before comparing (V14), so parallel requests cannot compare more than the
   * limit; refused while locked.
   */
  private void reserveAttempt(CrmPrincipal principal) {
    MfaAttempt attempt = mfa.reserveAttempt(principal.subjectId());
    if (attempt.lockStarted()) notifyLock(principal);
    if (!attempt.allowed()) throw new MfaException(Reason.LOCKED, attempt.retrySeconds());
  }

  private MfaException failure(CrmPrincipal principal) {
    MfaAttempt outcome = mfa.finishFailure(principal.subjectId());
    if (outcome.lockStarted()) notifyLock(principal);
    return outcome.allowed()
        ? new MfaException(Reason.CODE_INVALID)
        : new MfaException(Reason.LOCKED, outcome.retrySeconds());
  }

  /** Sent once per lock window: only the call that starts the lock reports it. */
  private void notifyLock(CrmPrincipal principal) {
    notify(
        principal,
        "Segundo fator bloqueado",
        "Houve muitas tentativas com códigos incorretos na sua conta e o segundo fator foi"
            + " bloqueado temporariamente. Se não foi você, troque a sua senha e avise a equipe da"
            + " plataforma.");
  }

  private MfaProof proof(CrmPrincipal principal) {
    return new MfaProof(principal.subjectId(), principal.authVersion(), clock.instant());
  }

  private List<byte[]> hashes(UUID subject, List<String> codes) {
    return codes.stream().map(code -> crypto.hashRecoveryCode(subject, code)).toList();
  }

  private static String otpauthUri(String email, String secret) {
    String issuer = encode(ISSUER);
    return "otpauth://totp/"
        + issuer
        + ":"
        + encode(email)
        + "?secret="
        + secret
        + "&issuer="
        + issuer
        + "&algorithm=SHA1&digits="
        + Totp.DIGITS
        + "&period="
        + Totp.PERIOD_SECONDS;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** After the commit; the outcome never depends on it and only a stable code is logged. */
  private void notify(CrmPrincipal principal, String subject, String text) {
    try {
      if (!mail.send(new MailMessage(principal.getUsername(), subject, text))) {
        log.warn("Security notice not delivered: MFA_NOTICE_FAILED");
      }
    } catch (RuntimeException failure) {
      log.warn("Security notice not delivered: MFA_NOTICE_FAILED");
    }
  }
}
