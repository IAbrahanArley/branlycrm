package br.com.branlycrm.onboarding.services;

import br.com.branlycrm.agency.repositories.AgencyRepository;
import br.com.branlycrm.agency.services.AgencyService;
import br.com.branlycrm.identity.mail.MailMessage;
import br.com.branlycrm.identity.mail.MailSender;
import br.com.branlycrm.identity.mail.PublicOrigin;
import br.com.branlycrm.onboarding.domain.ClinicName;
import br.com.branlycrm.onboarding.domain.Invitation;
import br.com.branlycrm.onboarding.domain.InvitationEmail;
import br.com.branlycrm.onboarding.domain.InvitationToken;
import br.com.branlycrm.onboarding.dto.response.AgencyClinicView;
import br.com.branlycrm.onboarding.dto.response.IssuedInvitationView;
import br.com.branlycrm.onboarding.exceptions.AgencyClinicException;
import br.com.branlycrm.onboarding.repositories.AgencyClinicRepository;
import br.com.branlycrm.onboarding.repositories.InvitationRepository;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.repositories.TeamRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An agency creates clinics in its portfolio and invites their first owner (ADR-026, ADR-028). The
 * clinic row, its provisioning task and the owner invitation are written in one transaction; the
 * provisioner builds the database and leaves the clinic AWAITING_OWNER. The agency never gets a
 * membership. It may reissue the owner invitation only while the clinic never had an owner.
 */
// Only the HTTP process: the provisioner has no mail.
@ConditionalOnWebApplication
@Service
public class AgencyClinicService {
  /** Where the owner invitation can be accepted (InvitationAcceptanceService). */
  private static final Set<String> AWAITING_STATUSES = Set.of("PROVISIONING", "AWAITING_OWNER");

  /** Owner invitation e-mails per clinic per day, whatever the address (security review, M1). */
  static final int MAX_OWNER_SENDS_PER_CLINIC_PER_DAY = 5;

  static final int MAX_OWNER_SENDS_PER_AGENCY_PER_DAY = 200;

  private final AgencyService agencyAccess;
  private final AgencyRepository agencies;
  private final AgencyClinicRepository portfolio;
  private final InvitationRepository invitations;
  private final TeamRepository team;
  private final MailSender mail;
  private final PublicOrigin origin;
  private final TransactionTemplate transactions;
  private final int maxUnfinishedClinics;

  public AgencyClinicService(
      AgencyService agencyAccess,
      AgencyRepository agencies,
      AgencyClinicRepository portfolio,
      InvitationRepository invitations,
      TeamRepository team,
      MailSender mail,
      PublicOrigin origin,
      PlatformTransactionManager transactionManager,
      @Value("${app.agency.max-unfinished-clinics:20}") int maxUnfinishedClinics) {
    this.agencyAccess = agencyAccess;
    this.agencies = agencies;
    this.portfolio = portfolio;
    this.invitations = invitations;
    this.team = team;
    this.mail = mail;
    this.origin = origin;
    this.transactions = new TransactionTemplate(transactionManager);
    this.maxUnfinishedClinics = maxUnfinishedClinics;
  }

  private record Created(
      UUID tenantId,
      String status,
      UUID invitationId,
      String clinicName,
      String ownerEmail,
      Instant expiresAt,
      InvitationToken token,
      String agencyName,
      boolean created) {}

  public AgencyClinicView create(
      UUID agencyId,
      UUID actor,
      UUID requestId,
      ClinicName clinicName,
      InvitationEmail ownerEmail) {
    Created result =
        transactions.execute(
            status -> {
              agencyAccess.requireAdmin(agencyId, actor);
              Optional<AgencyClinicRepository.ExistingRequest> existing =
                  portfolio.findByRequest(agencyId, requestId);
              if (existing.isPresent()) return replay(existing.get(), clinicName, ownerEmail);
              if (agencies.unfinishedClinicsLocked(agencyId) >= maxUnfinishedClinics) {
                throw new AgencyClinicException(
                    AgencyClinicException.Reason.PENDING_LIMIT,
                    "Há clínicas demais em preparação nesta agência. Conclua as pendentes.");
              }
              TenantDatabase database = TenantDatabase.forTenant(UUID.randomUUID());
              if (!portfolio.insertClinic(database, clinicName.value(), agencyId, requestId)) {
                // A concurrent request with the same key committed first: answer like a replay.
                return replay(
                    portfolio.findByRequest(agencyId, requestId).orElseThrow(),
                    clinicName,
                    ownerEmail);
              }
              portfolio.insertProvisioningTask(database.tenantId());
              InvitationToken token = InvitationToken.generate();
              Instant expiresAt = invitations.now().plus(TeamInvitationService.VALIDITY);
              UUID invitationId =
                  invitations.insertClinicInvitation(
                      token.hash(),
                      ownerEmail.value(),
                      database.tenantId(),
                      "OWNER",
                      actor,
                      agencyId,
                      expiresAt);
              return new Created(
                  database.tenantId(),
                  "PROVISIONING",
                  invitationId,
                  clinicName.value(),
                  ownerEmail.value(),
                  expiresAt,
                  token,
                  agencyName(agencyId),
                  true);
            });
    if (!result.created()) {
      return new AgencyClinicView(
          result.tenantId(),
          result.clinicName(),
          result.status(),
          result.ownerEmail(),
          null,
          "NOT_SENT",
          false);
    }
    boolean sent =
        send(
            result.ownerEmail(),
            result.clinicName(),
            result.agencyName(),
            result.token(),
            result.expiresAt());
    return new AgencyClinicView(
        result.tenantId(),
        result.clinicName(),
        "PROVISIONING",
        result.ownerEmail(),
        result.expiresAt(),
        sent ? "SENT" : "FAILED",
        true);
  }

  /**
   * Reissues the first-owner invitation, possibly to another address, while the clinic never had an
   * owner. Same address: new token (the old link stops working), within the sending caps. Another
   * address: the old invitation is revoked.
   */
  public IssuedInvitationView reissueOwnerInvitation(
      UUID agencyId, UUID actor, UUID tenantId, InvitationEmail ownerEmail) {
    Created result =
        transactions.execute(
            status -> {
              agencyAccess.requireAdmin(agencyId, actor);
              String clinicStatus =
                  portfolio
                      .lockPortfolioClinic(agencyId, tenantId)
                      .orElseThrow(
                          () ->
                              new AgencyClinicException(
                                  AgencyClinicException.Reason.CLINIC_NOT_FOUND,
                                  "Clínica não encontrada."));
              if (!AWAITING_STATUSES.contains(clinicStatus)
                  || team.hasAnyOwnerMembership(tenantId)) {
                throw new AgencyClinicException(
                    AgencyClinicException.Reason.CLINIC_HAS_OWNER,
                    "A clínica já tem gestor; novos convites são feitos pela própria clínica.");
              }
              InvitationRepository.SendHistory sends = invitations.agencyOwnerSends(tenantId);
              if (sends.sendCount() >= MAX_OWNER_SENDS_PER_CLINIC_PER_DAY
                  || sends.secondsSinceLast() < TeamInvitationService.MIN_SECONDS_BETWEEN_SENDS
                  || invitations.agencySendsInLastDay(agencyId)
                      >= MAX_OWNER_SENDS_PER_AGENCY_PER_DAY) {
                throw sendLimit();
              }
              InvitationToken token = InvitationToken.generate();
              Instant expiresAt = invitations.now().plus(TeamInvitationService.VALIDITY);
              Optional<Invitation> pending = invitations.lockPendingAgencyOwner(tenantId);
              if (pending.isPresent() && pending.get().email().equals(ownerEmail.value())) {
                invitations.rotate(
                    pending.get().invitationId(), token.hash(), "OWNER", actor, expiresAt);
                return new Created(
                    tenantId,
                    clinicStatus,
                    pending.get().invitationId(),
                    invitations.clinicName(tenantId).orElse(""),
                    ownerEmail.value(),
                    expiresAt,
                    token,
                    agencyName(agencyId),
                    false);
              }
              pending.ifPresent(old -> invitations.revoke(old.invitationId(), actor));
              UUID invitationId =
                  invitations.insertClinicInvitation(
                      token.hash(),
                      ownerEmail.value(),
                      tenantId,
                      "OWNER",
                      actor,
                      agencyId,
                      expiresAt);
              return new Created(
                  tenantId,
                  clinicStatus,
                  invitationId,
                  invitations.clinicName(tenantId).orElse(""),
                  ownerEmail.value(),
                  expiresAt,
                  token,
                  agencyName(agencyId),
                  true);
            });
    boolean sent =
        send(
            result.ownerEmail(),
            result.clinicName(),
            result.agencyName(),
            result.token(),
            result.expiresAt());
    return new IssuedInvitationView(
        result.invitationId(),
        result.ownerEmail(),
        "OWNER",
        result.expiresAt(),
        sent ? "SENT" : "FAILED");
  }

  private Created replay(
      AgencyClinicRepository.ExistingRequest existing,
      ClinicName clinicName,
      InvitationEmail email) {
    if (!existing.clinicName().equals(clinicName.value())
        || !email.value().equals(existing.ownerEmail())) {
      throw new AgencyClinicException(
          AgencyClinicException.Reason.IDEMPOTENCY_KEY_REUSED,
          "Este requestId já foi usado com outros dados.");
    }
    return new Created(
        existing.tenantId(),
        existing.status(),
        null,
        existing.clinicName(),
        existing.ownerEmail(),
        null,
        null,
        null,
        false);
  }

  private String agencyName(UUID agencyId) {
    return portfolio.agencyName(agencyId).orElse("");
  }

  private boolean send(
      String to, String clinicName, String agencyName, InvitationToken token, Instant expiresAt) {
    String text =
        "Você foi indicado(a) pela agência "
            + agencyName
            + " como gestor(a) da clínica "
            + clinicName
            + " no Branly CRM.\n\nPara aceitar e criar seu acesso, abra o link abaixo até "
            + expiresAt
            + ":\n"
            + origin.invitationLink(token.raw())
            + "\n\nSe você não esperava este convite, ignore este e-mail.";
    return mail.send(new MailMessage(to, "Gestão da clínica " + clinicName, text));
  }

  private static AgencyClinicException sendLimit() {
    return new AgencyClinicException(
        AgencyClinicException.Reason.SEND_LIMIT,
        "Limite de envios de convite atingido. Tente mais tarde.");
  }
}
