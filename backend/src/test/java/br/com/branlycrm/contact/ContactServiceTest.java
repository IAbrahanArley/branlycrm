package br.com.branlycrm.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.contact.domain.ConsentChannel;
import br.com.branlycrm.contact.domain.ConsentSource;
import br.com.branlycrm.contact.domain.ConsentStatus;
import br.com.branlycrm.contact.domain.ContactCreatedVia;
import br.com.branlycrm.contact.domain.ContactCreation;
import br.com.branlycrm.contact.dto.request.ArchiveRequest;
import br.com.branlycrm.contact.dto.request.ConsentRequest;
import br.com.branlycrm.contact.dto.request.ContactRequest;
import br.com.branlycrm.contact.dto.request.TagRequest;
import br.com.branlycrm.contact.exceptions.ContactException;
import br.com.branlycrm.contact.repositories.ConsentRepository;
import br.com.branlycrm.contact.repositories.ContactRepository;
import br.com.branlycrm.contact.repositories.TagRepository;
import br.com.branlycrm.contact.services.ContactService;
import br.com.branlycrm.contact.services.TagService;
import br.com.branlycrm.tenant.domain.AccessScope;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class ContactServiceTest {
  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final ContactRequest VALID =
      new ContactRequest("Ana Sintética", "(11) 98765-4321", null, null, null, 0L);

  private final TenantAccessService access = mock(TenantAccessService.class);
  private final ContactRepository contacts = mock(ContactRepository.class);
  private final TagRepository tags = mock(TagRepository.class);
  private final ConsentRepository consents = mock(ConsentRepository.class);
  private final ContactService service = new ContactService(access, contacts, tags, consents);
  private final TagService tagService = new TagService(access, tags);

  /** Invalid input is reported before the access check and before any clinic database read. */
  @Test
  void invalidInputIsRefusedBeforeAuthorizationAndDatabase() {
    for (ThrowingCallable call :
        List.<ThrowingCallable>of(
            () -> service.create(TENANT, ACTOR, null),
            () ->
                service.create(
                    TENANT, ACTOR, new ContactRequest("Ana", "abc", null, null, null, null)),
            () ->
                service.create(
                    TENANT,
                    ACTOR,
                    new ContactRequest("Ana", "11987654321", null, "17/05/1990", null, null)),
            () ->
                service.update(
                    TENANT,
                    ACTOR,
                    UUID.randomUUID(),
                    new ContactRequest("Ana", "11987654321", null, null, null, null)),
            () -> service.setArchived(TENANT, ACTOR, UUID.randomUUID(), new ArchiveRequest(null)),
            () ->
                service.recordConsent(
                    TENANT, ACTOR, UUID.randomUUID(), new ConsentRequest("SMS", "OPT_IN")),
            () ->
                service.recordConsent(
                    TENANT, ACTOR, UUID.randomUUID(), new ConsentRequest("EMAIL", "MAYBE")),
            () -> service.list(TENANT, ACTOR, "a", null, null, null, null),
            () -> service.list(TENANT, ACTOR, null, "not-a-uuid", null, null, null),
            () -> service.list(TENANT, ACTOR, null, null, "yes", null, null),
            () -> tagService.create(TENANT, ACTOR, new TagRequest(" ")))) {
      assertThatThrownBy(call).isInstanceOf(InvalidInputException.class);
    }
    verifyNoInteractions(access, contacts, tags, consents);
  }

  @Test
  void refusedRoleNeverReachesTheClinicDatabase() {
    when(access.require(any(), any(), any())).thenThrow(new TenantAccessException());
    assertThatThrownBy(() -> service.create(TENANT, ACTOR, VALID))
        .isInstanceOf(TenantAccessException.class);
    assertThatThrownBy(() -> service.get(TENANT, ACTOR, UUID.randomUUID()))
        .isInstanceOf(TenantAccessException.class);
    verifyNoInteractions(contacts, tags, consents);
  }

  @Test
  void consentFromTheApiIsAlwaysManual() {
    ClinicAccess clinic = access(ClinicRole.ATTENDANT);
    UUID contact = UUID.randomUUID();
    when(access.require(TENANT, ACTOR, ClinicPermission.CONTACT_WRITE)).thenReturn(clinic);
    when(consents.history(clinic, contact)).thenReturn(List.of());

    service.recordConsent(TENANT, ACTOR, contact, new ConsentRequest("WHATSAPP", "OPT_OUT"));

    verify(consents)
        .record(
            clinic, contact, ConsentChannel.WHATSAPP, ConsentStatus.OPT_OUT, ConsentSource.MANUAL);
  }

  @Test
  void duplicateExposesTheExistingContactToWritersWhoRead() {
    UUID existing = UUID.randomUUID();
    when(access.require(TENANT, ACTOR, ClinicPermission.CONTACT_WRITE))
        .thenReturn(access(ClinicRole.ATTENDANT));
    when(contacts.create(any(), any(), eq(ContactCreatedVia.MANUAL)))
        .thenReturn(new ContactCreation.Existing(existing));

    assertThatThrownBy(() -> service.create(TENANT, ACTOR, VALID))
        .isInstanceOf(ContactException.class)
        .extracting(error -> ((ContactException) error).existingContactId())
        .isEqualTo(existing);
  }

  @Test
  void renamingAndDeletingTagsNeedTagManage() {
    when(access.require(TENANT, ACTOR, ClinicPermission.TAG_MANAGE))
        .thenThrow(new TenantAccessException());
    UUID tag = UUID.randomUUID();
    assertThatThrownBy(() -> tagService.rename(TENANT, ACTOR, tag, new TagRequest("VIP")))
        .isInstanceOf(TenantAccessException.class);
    assertThatThrownBy(() -> tagService.delete(TENANT, ACTOR, tag))
        .isInstanceOf(TenantAccessException.class);
    verifyNoInteractions(tags);
    assertThat(ClinicRole.ATTENDANT.scope(ClinicPermission.TAG_MANAGE)).isEqualTo(AccessScope.NONE);
  }

  private static ClinicAccess access(ClinicRole role) {
    String suffix = TENANT.toString().replace("-", "");
    return new ClinicAccess(
        new TenantDatabase(
            TENANT,
            "clinic_" + suffix,
            "tenant_" + suffix,
            "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT)),
        ACTOR,
        role);
  }
}
