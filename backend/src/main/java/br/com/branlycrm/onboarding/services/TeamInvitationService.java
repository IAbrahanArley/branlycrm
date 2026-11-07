package br.com.branlycrm.onboarding.services;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.identity.mail.MailMessage;
import br.com.branlycrm.identity.mail.MailSender;
import br.com.branlycrm.identity.mail.PublicOrigin;
import br.com.branlycrm.onboarding.domain.Invitation;
import br.com.branlycrm.onboarding.domain.InvitationEmail;
import br.com.branlycrm.onboarding.domain.InvitationToken;
import br.com.branlycrm.onboarding.dto.response.IssuedInvitationView;
import br.com.branlycrm.onboarding.dto.response.TeamInvitationView;
import br.com.branlycrm.onboarding.exceptions.InvitationException;
import br.com.branlycrm.onboarding.repositories.InvitationRepository;
import br.com.branlycrm.tenant.domain.AccessScope;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TeamPolicy;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TeamRepository;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Team invitations of the selected clinic (CRM-013, ADR-028), with the reach of {@link
 * TeamPolicy#mayInvite}: a MANAGER invites, resends and revokes only ATTENDANT and VIEWER. Writes
 * lock the clinic, then re-read the actor's role, like team changes. The e-mail goes out after the
 * commit: a rollback never leaves a live link without an invitation, and a failed delivery leaves
 * the invitation pending.
 */
// Only the HTTP process: the provisioner has no password encoder nor mail.
@ConditionalOnWebApplication
@Service
public class TeamInvitationService {
  static final Duration VALIDITY = Duration.ofDays(7);

  /** Sending caps, so a clinic cannot use the platform's domain to flood an address. */
  static final int MAX_SENDS_PER_INVITATION = 5;

  static final int MIN_SECONDS_BETWEEN_SENDS = 60;
  static final int MAX_SENDS_PER_CLINIC_PER_DAY = 100;

  private final TenantAccessService access;
  private final TeamRepository team;
  private final InvitationRepository invitations;
  private final MailSender mail;
  private final PublicOrigin origin;
  private final TransactionTemplate transactions;

  public TeamInvitationService(
      TenantAccessService access,
      TeamRepository team,
      InvitationRepository invitations,
      MailSender mail,
      PublicOrigin origin,
      PlatformTransactionManager transactionManager) {
    this.access = access;
    this.team = team;
    this.invitations = invitations;
    this.mail = mail;
    this.origin = origin;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /** The token exists only here, between the commit and the e-mail. */
  private record Issued(
      UUID invitationId,
      String email,
      ClinicRole role,
      Instant expiresAt,
      InvitationToken token,
      String clinicName) {}

  @Transactional(readOnly = true)
  public PageView<TeamInvitationView> list(UUID tenantId, UUID actor, PageQuery page) {
    access.require(tenantId, actor, ClinicPermission.TEAM_MANAGE);
    PageResult<Invitation> result = invitations.listPendingForClinic(tenantId, page);
    Instant now = invitations.now();
    return new PageView<>(
        result.items().stream()
            .map(
                invitation ->
                    new TeamInvitationView(
                        invitation.invitationId(),
                        invitation.email(),
                        invitation.role(),
                        invitation.expiresAt(),
                        !now.isBefore(invitation.expiresAt())))
            .toList(),
        page.page(),
        page.pageSize(),
        result.total());
  }

  /**
   * Invites an address, or reissues its pending invitation with the new role and a new token. The
   * answer is the same whether or not an account exists for the address; only an address that
   * already belongs to an active member is refused, and the team list already shows those.
   */
  public IssuedInvitationView invite(
      UUID tenantId, UUID actor, InvitationEmail email, ClinicRole role) {
    Issued issued =
        transactions.execute(
            status -> {
              ClinicRole actorRole = authorize(tenantId, actor);
              if (!TeamPolicy.mayInvite(actorRole, role)) throw forbidden();
              if (team.isActiveMemberEmail(tenantId, email.value())) {
                throw new InvitationException(
                    InvitationException.Reason.MEMBER_ALREADY_ACTIVE,
                    "Este e-mail já é de um membro ativo da clínica.");
              }
              requireClinicSendBudget(tenantId);
              InvitationToken token = InvitationToken.generate();
              Instant expiresAt = invitations.now().plus(VALIDITY);
              UUID id =
                  invitations
                      .lockPendingForClinicEmail(tenantId, email.value())
                      .map(pending -> reissue(pending, actorRole, actor, token, role, expiresAt))
                      .orElse(null);
              if (id == null) {
                id =
                    invitations.insertClinicInvitation(
                        token.hash(), email.value(), tenantId, role.name(), actor, null, expiresAt);
              }
              return new Issued(id, email.value(), role, expiresAt, token, clinicName(tenantId));
            });
    return deliver(issued);
  }

  /** New token and expiry for a pending invitation; the previous link stops working. */
  public IssuedInvitationView resend(UUID tenantId, UUID actor, UUID invitationId) {
    Issued issued =
        transactions.execute(
            status -> {
              ClinicRole actorRole = authorize(tenantId, actor);
              Invitation pending = lockPending(tenantId, invitationId);
              ClinicRole role = ClinicRole.valueOf(pending.role());
              // An agency's owner invitation belongs to the agency, not to the team.
              if (pending.issuedByAgency() || !TeamPolicy.mayInvite(actorRole, role)) {
                throw forbidden();
              }
              requireClinicSendBudget(tenantId);
              requireInvitationSendBudget(pending.invitationId());
              InvitationToken token = InvitationToken.generate();
              Instant expiresAt = invitations.now().plus(VALIDITY);
              invitations.rotate(
                  pending.invitationId(), token.hash(), role.name(), actor, expiresAt);
              return new Issued(
                  pending.invitationId(),
                  pending.email(),
                  role,
                  expiresAt,
                  token,
                  clinicName(tenantId));
            });
    return deliver(issued);
  }

  @Transactional
  public void revoke(UUID tenantId, UUID actor, UUID invitationId) {
    ClinicRole actorRole = authorize(tenantId, actor);
    Invitation pending = lockPending(tenantId, invitationId);
    if (!TeamPolicy.mayInvite(actorRole, ClinicRole.valueOf(pending.role()))) throw forbidden();
    invitations.revoke(pending.invitationId(), actor);
  }

  /** A pending agency invitation for the same address is replaced, never rewritten by the team. */
  private UUID reissue(
      Invitation pending,
      ClinicRole actorRole,
      UUID actor,
      InvitationToken token,
      ClinicRole role,
      Instant expiresAt) {
    if (!TeamPolicy.mayInvite(actorRole, ClinicRole.valueOf(pending.role()))) throw forbidden();
    if (pending.issuedByAgency()) {
      invitations.revoke(pending.invitationId(), actor);
      return null;
    }
    requireInvitationSendBudget(pending.invitationId());
    invitations.rotate(pending.invitationId(), token.hash(), role.name(), actor, expiresAt);
    return pending.invitationId();
  }

  /** Same sequence as team changes: session check, clinic lock, then the actor's role locked. */
  private ClinicRole authorize(UUID tenantId, UUID actor) {
    access.require(tenantId, actor, ClinicPermission.TEAM_MANAGE);
    if (!team.lockActiveClinic(tenantId)) throw new TenantAccessException();
    ClinicRole role = team.lockActorRole(tenantId, actor).orElseThrow(TenantAccessException::new);
    if (role.scope(ClinicPermission.TEAM_MANAGE) == AccessScope.NONE) {
      throw new TenantAccessException();
    }
    return role;
  }

  private void requireClinicSendBudget(UUID tenantId) {
    if (invitations.sentInLastDay(tenantId) >= MAX_SENDS_PER_CLINIC_PER_DAY) throw sendLimit();
  }

  private void requireInvitationSendBudget(UUID invitationId) {
    InvitationRepository.SendHistory history = invitations.sendHistory(invitationId);
    if (history.sendCount() >= MAX_SENDS_PER_INVITATION
        || history.secondsSinceLast() < MIN_SECONDS_BETWEEN_SENDS) {
      throw sendLimit();
    }
  }

  private static InvitationException sendLimit() {
    return new InvitationException(
        InvitationException.Reason.SEND_LIMIT,
        "Limite de envios de convite atingido. Tente mais tarde.");
  }

  private Invitation lockPending(UUID tenantId, UUID invitationId) {
    return invitations
        .lockPendingForClinic(tenantId, invitationId)
        .orElseThrow(
            () ->
                new InvitationException(
                    InvitationException.Reason.NOT_FOUND, "Convite não encontrado."));
  }

  private String clinicName(UUID tenantId) {
    return invitations.clinicName(tenantId).orElseThrow(TenantAccessException::new);
  }

  private IssuedInvitationView deliver(Issued issued) {
    String text =
        "Você foi convidado(a) para a clínica "
            + issued.clinicName()
            + " no Branly CRM.\n\nPara aceitar, abra o link abaixo até "
            + issued.expiresAt()
            + ":\n"
            + origin.invitationLink(issued.token().raw())
            + "\n\nSe você não esperava este convite, ignore este e-mail.";
    boolean sent =
        mail.send(new MailMessage(issued.email(), "Convite para " + issued.clinicName(), text));
    return new IssuedInvitationView(
        issued.invitationId(),
        issued.email(),
        issued.role().name(),
        issued.expiresAt(),
        sent ? "SENT" : "FAILED");
  }

  private static InvitationException forbidden() {
    return new InvitationException(
        InvitationException.Reason.FORBIDDEN, "Seu papel não permite convidar para este papel.");
  }
}
