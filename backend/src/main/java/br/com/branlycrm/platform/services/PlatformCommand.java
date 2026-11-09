package br.com.branlycrm.platform.services;

import br.com.branlycrm.identity.mail.PublicOrigin;
import br.com.branlycrm.onboarding.domain.InvitationEmail;
import br.com.branlycrm.onboarding.domain.InvitationToken;
import br.com.branlycrm.platform.repositories.PlatformRepository;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.CommandLineRunner;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Platform operations without an HTTP surface (ADR-028), run on demand with the platform
 * credential:
 *
 * <pre>
 * create-agency --request-id UUID --name NAME --admin-email EMAIL
 * invite-agency-admin --agency-id UUID --email EMAIL
 * reset-mfa --email EMAIL
 * </pre>
 *
 * The invitation link is printed once to standard output, never through the logger and never by
 * e-mail (the Resend key exists only in the API); the address is not printed. Running again with
 * the same arguments reuses the agency and rotates the pending invitation, so the previous link
 * stops working: that is also how a leaked link is revoked.
 *
 * <p>{@code reset-mfa} (CRM-016) is the recovery of someone who lost both the authenticator and the
 * recovery codes, after the identity was checked outside the system: it removes the factor and the
 * codes and ends every session; an owner or agency admin enrolls again at the next login. It prints
 * only whether a factor existed, never the address. This process has no mail credential, so the
 * account is warned by the enrollment notice that follows.
 */
public class PlatformCommand implements CommandLineRunner {
  static final Duration VALIDITY = Duration.ofHours(72);

  private static final Map<String, Set<String>> COMMANDS =
      Map.of(
          "create-agency", Set.of("--request-id", "--name", "--admin-email"),
          "invite-agency-admin", Set.of("--agency-id", "--email"),
          "reset-mfa", Set.of("--email"));

  private final PlatformRepository platform;
  private final TransactionTemplate transactions;
  private final PublicOrigin origin;
  private final PrintStream out;

  public PlatformCommand(
      PlatformRepository platform,
      TransactionTemplate transactions,
      PublicOrigin origin,
      PrintStream out) {
    this.platform = platform;
    this.transactions = transactions;
    this.origin = origin;
    this.out = out;
  }

  @Override
  public void run(String... args) {
    if (args.length == 0 || !COMMANDS.containsKey(args[0])) throw usage();
    Map<String, String> options = options(args, COMMANDS.get(args[0]));
    if ("reset-mfa".equals(args[0])) {
      InvitationEmail email = InvitationEmail.of(options.get("--email"));
      boolean removed =
          Boolean.TRUE.equals(transactions.execute(status -> platform.resetMfa(email.value())));
      out.println("mfaFactorRemoved: " + removed);
      return;
    }
    Issued issued =
        switch (args[0]) {
          case "create-agency" -> {
            UUID requestId = uuid(options, "--request-id");
            String name = options.get("--name").strip();
            if (name.isEmpty()
                || name.length() > 160
                || name.codePoints().anyMatch(Character::isISOControl)) {
              throw new IllegalArgumentException("--name");
            }
            InvitationEmail email = InvitationEmail.of(options.get("--admin-email"));
            yield transactions.execute(
                status -> {
                  PlatformRepository.CreatedAgency agency = platform.createAgency(requestId, name);
                  if (!agency.name().equals(name)) {
                    throw new IllegalArgumentException(
                        "--request-id was already used for an agency with another name");
                  }
                  return issue(agency.agencyId(), email);
                });
          }
          default -> {
            UUID agencyId = uuid(options, "--agency-id");
            InvitationEmail email = InvitationEmail.of(options.get("--email"));
            yield transactions.execute(
                status -> {
                  if (!"ACTIVE".equals(platform.agencyStatus(agencyId).orElse(null))) {
                    throw new IllegalArgumentException("Agency not found or suspended");
                  }
                  return issue(agencyId, email);
                });
          }
        };
    out.println("agencyId: " + issued.agencyId());
    out.println("expiresAt: " + issued.expiresAt());
    out.println("invitationLink: " + origin.invitationLink(issued.token().raw()));
  }

  private record Issued(UUID agencyId, InvitationToken token, Instant expiresAt) {}

  private Issued issue(UUID agencyId, InvitationEmail email) {
    InvitationToken token = InvitationToken.generate();
    Instant expiresAt = platform.now().plus(VALIDITY);
    platform.issueAdminInvitation(agencyId, email.value(), token.hash(), expiresAt);
    return new Issued(agencyId, token, expiresAt);
  }

  /** Exactly the flags of the command, each once, each with a non-blank value. */
  private static Map<String, String> options(String[] args, Set<String> allowed) {
    Map<String, String> options = new HashMap<>();
    for (int i = 1; i < args.length; i += 2) {
      if (i + 1 >= args.length
          || !allowed.contains(args[i])
          || args[i + 1].isBlank()
          || options.put(args[i], args[i + 1]) != null) {
        throw usage();
      }
    }
    if (!options.keySet().equals(allowed)) throw usage();
    return options;
  }

  private static UUID uuid(Map<String, String> options, String name) {
    try {
      return UUID.fromString(options.get(name));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(name);
    }
  }

  private static IllegalArgumentException usage() {
    return new IllegalArgumentException(
        "Usage: create-agency --request-id UUID --name NAME --admin-email EMAIL"
            + " | invite-agency-admin --agency-id UUID --email EMAIL"
            + " | reset-mfa --email EMAIL");
  }
}
