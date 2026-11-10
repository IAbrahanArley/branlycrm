package br.com.branlycrm.contact.services;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.contact.domain.ConsentChannel;
import br.com.branlycrm.contact.domain.ConsentPolicy;
import br.com.branlycrm.contact.domain.ConsentRecord;
import br.com.branlycrm.contact.domain.ConsentSource;
import br.com.branlycrm.contact.domain.ConsentState;
import br.com.branlycrm.contact.domain.ConsentStatus;
import br.com.branlycrm.contact.domain.Contact;
import br.com.branlycrm.contact.domain.ContactCreatedVia;
import br.com.branlycrm.contact.domain.ContactCreation;
import br.com.branlycrm.contact.domain.ContactDetail;
import br.com.branlycrm.contact.domain.ContactFields;
import br.com.branlycrm.contact.domain.ContactSearch;
import br.com.branlycrm.contact.domain.ContactSummary;
import br.com.branlycrm.contact.domain.PhoneNumber;
import br.com.branlycrm.contact.dto.request.ArchiveRequest;
import br.com.branlycrm.contact.dto.request.ConsentRequest;
import br.com.branlycrm.contact.dto.request.ContactRequest;
import br.com.branlycrm.contact.dto.request.PhoneCheckRequest;
import br.com.branlycrm.contact.dto.response.ConsentRecordView;
import br.com.branlycrm.contact.dto.response.ContactConsentsView;
import br.com.branlycrm.contact.dto.response.ContactSummaryView;
import br.com.branlycrm.contact.dto.response.ContactView;
import br.com.branlycrm.contact.dto.response.PhoneCheckView;
import br.com.branlycrm.contact.dto.response.TagView;
import br.com.branlycrm.contact.exceptions.ContactException;
import br.com.branlycrm.contact.repositories.ConsentRepository;
import br.com.branlycrm.contact.repositories.ContactRepository;
import br.com.branlycrm.contact.repositories.TagRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Contacts of the selected clinic (CRM-021, PAC-01/03/06/08). Every call validates the input, then
 * requires the permission of docs/permissoes.md, then reads by id: a role without the permission is
 * refused before learning whether an id exists, and another clinic's id answers like a missing one.
 *
 * <p>No central transaction is opened here: the access check runs its own, and each repository call
 * runs one clinic transaction, so no request holds a control connection while it waits on a clinic
 * database.
 */
@Service
public class ContactService {
  private final TenantAccessService access;
  private final ContactRepository contacts;
  private final TagRepository tags;
  private final ConsentRepository consents;

  public ContactService(
      TenantAccessService access,
      ContactRepository contacts,
      TagRepository tags,
      ConsentRepository consents) {
    this.access = access;
    this.contacts = contacts;
    this.tags = tags;
    this.consents = consents;
  }

  public PageView<ContactSummaryView> list(
      UUID tenantId,
      UUID actor,
      String term,
      String tagId,
      String includeArchived,
      String page,
      String pageSize) {
    var search = new ContactSearch(term, uuid(tagId, "tagId"), flag(includeArchived));
    PageQuery paging = PageQuery.of(page, pageSize);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_READ);
    PageResult<ContactSummary> result = contacts.search(clinic, search, paging);
    return new PageView<>(
        result.items().stream().map(ContactService::summary).toList(),
        paging.page(),
        paging.pageSize(),
        result.total());
  }

  /** Duplicate warning before creating; only who may create asks. */
  public PhoneCheckView checkPhone(UUID tenantId, UUID actor, PhoneCheckRequest request) {
    PhoneNumber phone = PhoneNumber.parse(request == null ? null : request.phone());
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_WRITE);
    ContactSummaryView holder =
        readable(clinic)
            ? contacts.findByPhone(clinic, phone).map(ContactService::summary).orElse(null)
            : null;
    return new PhoneCheckView(phone.e164(), holder);
  }

  /**
   * @throws ContactException DUPLICATE_PHONE with the existing contact's id, exposed only to a role
   *     that may read contacts
   */
  public ContactView create(UUID tenantId, UUID actor, ContactRequest request) {
    ContactFields fields = fields(request);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_WRITE);
    ContactCreation created = contacts.create(clinic, fields, ContactCreatedVia.MANUAL);
    if (created instanceof ContactCreation.Existing existing) {
      throw new ContactException(
          ContactException.Reason.DUPLICATE_PHONE, readable(clinic) ? existing.id() : null);
    }
    return detail(clinic, created.id());
  }

  public ContactView get(UUID tenantId, UUID actor, UUID contactId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_READ);
    return detail(clinic, contactId);
  }

  /** Full replacement at the version read; an absent optional field is cleared. */
  public ContactView update(UUID tenantId, UUID actor, UUID contactId, ContactRequest request) {
    ContactFields fields = fields(request);
    if (request.version() == null || request.version() < 0) {
      throw new InvalidInputException("version");
    }
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_WRITE);
    try {
      contacts.update(clinic, contactId, request.version(), fields);
    } catch (ContactException duplicate) {
      if (duplicate.reason() == ContactException.Reason.DUPLICATE_PHONE && !readable(clinic)) {
        throw new ContactException(ContactException.Reason.DUPLICATE_PHONE);
      }
      throw duplicate;
    }
    return detail(clinic, contactId);
  }

  public ContactView setArchived(
      UUID tenantId, UUID actor, UUID contactId, ArchiveRequest request) {
    if (request == null || request.archived() == null) throw new InvalidInputException("archived");
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_ARCHIVE);
    contacts.setArchived(clinic, contactId, request.archived());
    return detail(clinic, contactId);
  }

  public ContactConsentsView consents(UUID tenantId, UUID actor, UUID contactId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_READ);
    return consentsView(consents.history(clinic, contactId));
  }

  /** Always recorded as MANUAL: the team writes down what the person asked. */
  public ContactConsentsView recordConsent(
      UUID tenantId, UUID actor, UUID contactId, ConsentRequest request) {
    if (request == null) throw new InvalidInputException("body");
    ConsentChannel channel = parse(ConsentChannel.class, request.channel(), "channel");
    ConsentStatus status = parse(ConsentStatus.class, request.status(), "status");
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_WRITE);
    consents.record(clinic, contactId, channel, status, ConsentSource.MANUAL);
    return consentsView(consents.history(clinic, contactId));
  }

  public List<TagView> tag(UUID tenantId, UUID actor, UUID contactId, UUID tagId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_WRITE);
    return tags.tag(clinic, contactId, tagId).stream().map(TagService::view).toList();
  }

  public List<TagView> untag(UUID tenantId, UUID actor, UUID contactId, UUID tagId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CONTACT_WRITE);
    return tags.untag(clinic, contactId, tagId).stream().map(TagService::view).toList();
  }

  /**
   * The id of an existing contact is shown only to roles that read contacts. Today every role that
   * writes also reads; this keeps the rule explicit if the matrix changes.
   */
  private static boolean readable(ClinicAccess clinic) {
    return clinic.allows(ClinicPermission.CONTACT_READ);
  }

  private ContactView detail(ClinicAccess clinic, UUID contactId) {
    ContactDetail detail =
        contacts
            .detail(clinic, contactId)
            .orElseThrow(() -> new ContactException(ContactException.Reason.NOT_FOUND));
    Contact contact = detail.contact();
    ContactFields fields = contact.fields();
    return new ContactView(
        contact.id(),
        fields.name(),
        fields.phone().e164(),
        fields.email(),
        fields.birthDate(),
        fields.notes(),
        contact.createdVia().name(),
        contact.createdAt(),
        contact.archivedAt(),
        contact.version(),
        detail.tags().stream().map(TagService::view).toList(),
        consentMap(detail.consent()),
        contact.patient());
  }

  private static ContactSummaryView summary(ContactSummary contact) {
    return new ContactSummaryView(
        contact.id(),
        contact.name(),
        contact.phone().e164(),
        contact.email(),
        contact.createdVia().name(),
        contact.archivedAt(),
        contact.patientSince() != null);
  }

  private static ContactConsentsView consentsView(List<ConsentRecord> history) {
    Map<ConsentChannel, ConsentState> current = ConsentPolicy.current(history);
    return new ContactConsentsView(
        consentMap(current),
        history.stream()
            .map(
                record ->
                    new ConsentRecordView(
                        record.id(),
                        record.channel().name(),
                        record.status().name(),
                        record.source().name(),
                        record.recordedAt(),
                        record.recordedBy()))
            .toList());
  }

  private static Map<String, String> consentMap(Map<ConsentChannel, ConsentState> state) {
    Map<String, String> view = new LinkedHashMap<>();
    new EnumMap<>(state).forEach((channel, value) -> view.put(channel.name(), value.name()));
    return view;
  }

  /** Every field is validated before any database access; invalid ones are named, never echoed. */
  private static ContactFields fields(ContactRequest request) {
    if (request == null) throw new InvalidInputException("body");
    return new ContactFields(
        request.name(),
        PhoneNumber.parse(request.phone()),
        request.email(),
        date(request.birthDate()),
        request.notes());
  }

  private static LocalDate date(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return LocalDate.parse(value.strip());
    } catch (DateTimeParseException invalid) {
      throw new InvalidInputException("birthDate");
    }
  }

  private static UUID uuid(String value, String field) {
    if (value == null || value.isEmpty()) return null;
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }

  private static boolean flag(String value) {
    if (value == null || value.isEmpty()) return false;
    if ("true".equals(value)) return true;
    if ("false".equals(value)) return false;
    throw new InvalidInputException("includeArchived");
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
    if (value == null) throw new InvalidInputException(field);
    try {
      return Enum.valueOf(type, value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }
}
