package br.com.branlycrm.onboarding.services;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.identity.domain.UserAccount;
import br.com.branlycrm.identity.repositories.UserAccountRepository;
import br.com.branlycrm.onboarding.domain.Invitation;
import br.com.branlycrm.onboarding.domain.InvitationKind;
import br.com.branlycrm.onboarding.domain.InvitationToken;
import br.com.branlycrm.onboarding.domain.NewAccount;
import br.com.branlycrm.onboarding.dto.response.InvitationPreviewView;
import br.com.branlycrm.onboarding.exceptions.InvitationException;
import br.com.branlycrm.onboarding.repositories.InvitationRepository;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TeamPolicy;
import br.com.branlycrm.tenant.repositories.TeamRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts clinic invitations (ADR-028). Lock order: clinic row, then invitation, then membership,
 * the same order as team changes and as the provisioner's activation, so an owner accepted while
 * the clinic is still provisioning is seen by the provisioner, and two acceptances of one token
 * serialize. Account rules:
 *
 * <ul>
 *   <li>no account: a verified one is created with the chosen password;
 *   <li>active, verified account: the session must be that account; its password never changes;
 *   <li>active, unverified account: the token proves the address and takes it over;
 *   <li>inactive account: refused, never reactivated.
 * </ul>
 *
 * A session of another account is refused instead of absorbing an invitation sent to someone else.
 * Agency invitations are accepted with the agency module.
 */
// Only the HTTP process: the provisioner has no password encoder nor mail.
@ConditionalOnWebApplication
@Service
public class InvitationAcceptanceService {
  /** An agency only brings the first owner, before the clinic has one (ADR-028). */
  private static final Set<String> AGENCY_RECEIVING = Set.of("PROVISIONING", "AWAITING_OWNER");

  /** Team invitations are issued by active clinics and accepted only while they stay active. */
  private static final Set<String> TEAM_RECEIVING = Set.of("ACTIVE");

  private final InvitationRepository invitations;
  private final UserAccountRepository accounts;
  private final TeamRepository team;
  private final PasswordEncoder passwords;

  public InvitationAcceptanceService(
      InvitationRepository invitations,
      UserAccountRepository accounts,
      TeamRepository team,
      PasswordEncoder passwords) {
    this.invitations = invitations;
    this.accounts = accounts;
    this.team = team;
    this.passwords = passwords;
  }

  /** What the token holder may see before accepting; holding the token proves the address. */
  @Transactional(readOnly = true)
  public InvitationPreviewView preview(String rawToken) {
    Invitation invitation = usable(rawToken);
    String name =
        invitations.targetName(invitation).orElseThrow(InvitationAcceptanceService::invalid);
    boolean requiresLogin =
        accounts
            .findByEmail(invitation.email())
            .filter(account -> account.active() && account.emailVerified())
            .isPresent();
    return new InvitationPreviewView(
        invitation.kind().name(), name, invitation.role(), requiresLogin, invitation.expiresAt());
  }

  /**
   * @param sessionSubject the authenticated subject, if a session exists
   * @param newAccount name and password, required only when no verified account exists
   */
  @Transactional
  public void accept(String rawToken, Optional<UUID> sessionSubject, NewAccount newAccount) {
    Invitation found = usable(rawToken);
    if (found.kind() != InvitationKind.CLINIC) throw invalid();
    String status =
        team.lockClinicForInvitation(found.tenantId())
            .orElseThrow(InvitationAcceptanceService::invalid);
    Invitation invitation =
        invitations
            .lock(found.invitationId())
            .filter(locked -> locked.usableAt(invitations.clock()))
            .orElseThrow(InvitationAcceptanceService::invalid);
    Set<String> receiving = invitation.issuedByAgency() ? AGENCY_RECEIVING : TEAM_RECEIVING;
    if (!receiving.contains(status)) throw invalid();
    ClinicRole role = ClinicRole.valueOf(invitation.role());
    requireIssuerAuthority(invitation, role);

    UUID subject = resolveAccount(invitation.email(), sessionSubject, newAccount);
    // Cheap guard on the accepted risk: an agency admin never becomes owner through its own
    // agency's invitation (another address is still possible, ADR-028).
    if (invitation.issuedByAgency()
        && invitations.isAgencyMember(invitation.issuerAgencyId(), subject)) {
      throw invalid();
    }
    Optional<Boolean> membership = team.lockMembershipActive(invitation.tenantId(), subject);
    if (membership.orElse(false)) {
      throw new InvitationException(
          InvitationException.Reason.MEMBER_ALREADY_ACTIVE, "Você já participa desta clínica.");
    }
    if (membership.isPresent()) {
      team.reactivate(invitation.tenantId(), subject, role);
    } else {
      team.insertMembership(invitation.tenantId(), subject, role);
    }
    invitations.markAccepted(invitation.invitationId(), subject);
    if (role == ClinicRole.OWNER
        && "AWAITING_OWNER".equals(status)
        && !team.activateAwaitingClinic(invitation.tenantId())) {
      // Under the clinic lock this cannot happen; never confirm an owner left without access.
      throw new IllegalStateException("Awaiting clinic could not be activated");
    }
  }

  /**
   * The invitation is only as good as whoever issued it, now. A demoted manager's invitations die
   * with the demotion; an agency's owner invitation dies once the clinic has an owner, the agency
   * is suspended or its issuer left it (ADR-028).
   */
  private void requireIssuerAuthority(Invitation invitation, ClinicRole role) {
    if (invitation.issuedByAgency()) {
      if (role != ClinicRole.OWNER
          || team.hasAnyOwnerMembership(invitation.tenantId())
          || !invitations.agencyStillIssues(
              invitation.issuerAgencyId(), invitation.tenantId(), invitation.invitedBy())) {
        throw invalid();
      }
      return;
    }
    if (invitation.invitedBy() == null) throw invalid();
    ClinicRole issuerRole =
        team.lockActorRole(invitation.tenantId(), invitation.invitedBy())
            .orElseThrow(InvitationAcceptanceService::invalid);
    if (!TeamPolicy.mayInvite(issuerRole, role)) throw invalid();
  }

  private UUID resolveAccount(String email, Optional<UUID> sessionSubject, NewAccount newAccount) {
    Optional<UserAccount> existing = accounts.findByEmail(email);
    if (sessionSubject.isPresent()) {
      UserAccount account =
          existing
              .filter(found -> found.subjectId().equals(sessionSubject.get()))
              .orElseThrow(
                  () ->
                      new InvitationException(
                          InvitationException.Reason.ACCOUNT_MISMATCH,
                          "Este convite foi enviado para outro e-mail. Saia e entre com ele."));
      if (!account.active() || !account.emailVerified()) throw invalid();
      return account.subjectId();
    }
    if (existing.isEmpty()) {
      NewAccount chosen = require(newAccount);
      return accounts
          .insertVerified(email, chosen.displayName(), passwords.encode(chosen.password()))
          .orElseThrow(InvitationAcceptanceService::loginRequired);
    }
    UserAccount account = existing.get();
    if (!account.active()) throw invalid();
    if (account.emailVerified()) throw loginRequired();
    NewAccount chosen = require(newAccount);
    if (!accounts.claimUnverified(
        account.subjectId(), chosen.displayName(), passwords.encode(chosen.password()))) {
      throw loginRequired();
    }
    return account.subjectId();
  }

  private Invitation usable(String rawToken) {
    InvitationToken token =
        InvitationToken.parse(rawToken).orElseThrow(InvitationAcceptanceService::invalid);
    return invitations
        .findByTokenHash(token.hash())
        .filter(invitation -> invitation.usableAt(invitations.now()))
        .orElseThrow(InvitationAcceptanceService::invalid);
  }

  private static NewAccount require(NewAccount newAccount) {
    if (newAccount == null) throw new InvalidInputException("password");
    return newAccount;
  }

  private static InvitationException invalid() {
    return new InvitationException(
        InvitationException.Reason.INVALID, "Convite inválido ou expirado.");
  }

  private static InvitationException loginRequired() {
    return new InvitationException(
        InvitationException.Reason.LOGIN_REQUIRED,
        "Já existe uma conta com este e-mail. Entre com ela para aceitar o convite.");
  }
}
