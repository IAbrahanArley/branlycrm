package br.com.branlycrm.contact.controllers;

import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.contact.dto.request.ArchiveRequest;
import br.com.branlycrm.contact.dto.request.ConsentRequest;
import br.com.branlycrm.contact.dto.request.ContactRequest;
import br.com.branlycrm.contact.dto.request.PhoneCheckRequest;
import br.com.branlycrm.contact.dto.response.ContactConflictError;
import br.com.branlycrm.contact.dto.response.ContactConsentsView;
import br.com.branlycrm.contact.dto.response.ContactSummaryView;
import br.com.branlycrm.contact.dto.response.ContactView;
import br.com.branlycrm.contact.dto.response.PhoneCheckView;
import br.com.branlycrm.contact.dto.response.TagView;
import br.com.branlycrm.contact.services.ContactService;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Contacts of the clinic selected in the session (CRM-021, docs/permissoes.md). Lists and checks
 * never return notes nor birth date; only the detail does.
 */
@Tag(name = "Contacts", description = "Contacts, their tags and consent in the selected clinic")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/contacts")
public final class ContactController {
  private final ContactService contacts;

  public ContactController(ContactService contacts) {
    this.contacts = contacts;
  }

  @Operation(
      operationId = "listContacts",
      summary = "List contacts (all roles), paginated and ordered by name",
      description =
          "q matches the name without accents or case, part of the phone digits, the whole number"
              + " in any accepted format or the start of the e-mail; notes are never searched."
              + " Archived contacts are left out unless includeArchived=true.")
  @ApiResponse(responseCode = "200", description = "Page of contacts without notes nor birth date")
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INPUT: q, tagId, includeArchived, page or pageSize")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @GetMapping
  public PageView<ContactSummaryView> list(
      @Parameter(description = "Search term, 2 to 100 characters") @RequestParam(required = false)
          String q,
      @Parameter(description = "Only contacts with this tag") @RequestParam(required = false)
          String tagId,
      @Parameter(description = "true includes archived contacts; default false")
          @RequestParam(required = false)
          String includeArchived,
      @Parameter(description = "1-based page, default 1") @RequestParam(required = false)
          String page,
      @Parameter(description = "Items per page, 1 to 100, default 25")
          @RequestParam(required = false)
          String pageSize,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return contacts.list(
        SelectedClinic.require(session),
        principal.subjectId(),
        q,
        tagId,
        includeArchived,
        page,
        pageSize);
  }

  @Operation(
      operationId = "checkContactPhone",
      summary = "Check a number before creating a contact (OWNER, MANAGER, ATTENDANT)",
      description =
          "Returns the normalized number and the contact already holding it in this clinic,"
              + " archived ones included, or null. The number travels in the body so it never"
              + " reaches access logs.")
  @ApiResponse(responseCode = "200", description = "Normalized number and existing contact")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: phone or body")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/phone-check")
  public PhoneCheckView checkPhone(
      @RequestBody PhoneCheckRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return contacts.checkPhone(SelectedClinic.require(session), principal.subjectId(), request);
  }

  @Operation(
      operationId = "createContact",
      summary = "Create a contact (OWNER, MANAGER, ATTENDANT)")
  @ApiResponse(responseCode = "201", description = "Contact created; Location points to it")
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INPUT: name, phone, email, birthDate, notes or body")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(
      responseCode = "409",
      description =
          "CONTACT_DUPLICATE_PHONE (contactId is the contact holding the number) or"
              + " CLINIC_NOT_SELECTED",
      content = @Content(schema = @Schema(implementation = ContactConflictError.class)))
  @SecurityRequirement(name = "csrf")
  @PostMapping
  public ResponseEntity<ContactView> create(
      @RequestBody ContactRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    ContactView created =
        contacts.create(SelectedClinic.require(session), principal.subjectId(), request);
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{contactId}")
                .buildAndExpand(created.id())
                .toUri())
        .body(created);
  }

  @Operation(
      operationId = "getContact",
      summary = "Contact detail with tags and current consent (all roles)")
  @ApiResponse(responseCode = "200", description = "Contact")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: contactId")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "CONTACT_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @GetMapping("/{contactId}")
  public ContactView get(
      @PathVariable UUID contactId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return contacts.get(SelectedClinic.require(session), principal.subjectId(), contactId);
  }

  @Operation(
      operationId = "updateContact",
      summary = "Replace a contact's data at the version read (OWNER, MANAGER, ATTENDANT)",
      description = "Absent optional fields are cleared. Archived contacts cannot be edited.")
  @ApiResponse(responseCode = "200", description = "Updated contact")
  @ApiResponse(
      responseCode = "400",
      description =
          "INVALID_INPUT: contactId, name, phone, email, birthDate, notes, version or body")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "CONTACT_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "CONTACT_STALE (reload and reapply), CONTACT_ARCHIVED, CONTACT_DUPLICATE_PHONE"
              + " (contactId is the contact holding the number) or CLINIC_NOT_SELECTED",
      content = @Content(schema = @Schema(implementation = ContactConflictError.class)))
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{contactId}")
  public ContactView update(
      @PathVariable UUID contactId,
      @RequestBody ContactRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return contacts.update(
        SelectedClinic.require(session), principal.subjectId(), contactId, request);
  }

  @Operation(
      operationId = "setContactArchived",
      summary = "Archive or unarchive a contact (OWNER, MANAGER)",
      description = "Idempotent. An archived contact keeps its number and leaves the default list.")
  @ApiResponse(responseCode = "200", description = "Contact in the requested state")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: contactId, archived or body")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "CONTACT_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CONTACT_STALE or CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{contactId}/archived")
  public ContactView setArchived(
      @PathVariable UUID contactId,
      @RequestBody ArchiveRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return contacts.setArchived(
        SelectedClinic.require(session), principal.subjectId(), contactId, request);
  }

  @Operation(
      operationId = "listContactConsents",
      summary = "Consent per channel and its history (all roles)")
  @ApiResponse(responseCode = "200", description = "Current consent and history")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: contactId")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "CONTACT_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @GetMapping("/{contactId}/consents")
  public ContactConsentsView consents(
      @PathVariable UUID contactId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return contacts.consents(SelectedClinic.require(session), principal.subjectId(), contactId);
  }

  @Operation(
      operationId = "recordContactConsent",
      summary = "Record a consent change said by the person (OWNER, MANAGER, ATTENDANT)",
      description =
          "Recorded with source MANUAL; also allowed on archived contacts, so an opt-out can"
              + " always be written down.")
  @ApiResponse(responseCode = "201", description = "Consent after the change")
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INPUT: contactId, channel, status or body")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "CONTACT_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CONSENT_REFUSED or CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{contactId}/consents")
  public ResponseEntity<ContactConsentsView> recordConsent(
      @PathVariable UUID contactId,
      @RequestBody ConsentRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    ContactConsentsView recorded =
        contacts.recordConsent(
            SelectedClinic.require(session), principal.subjectId(), contactId, request);
    return ResponseEntity.created(
            ServletUriComponentsBuilder.fromCurrentRequestUri().build().toUri())
        .body(recorded);
  }

  @Operation(
      operationId = "tagContact",
      summary = "Add a tag to a contact (OWNER, MANAGER, ATTENDANT); idempotent")
  @ApiResponse(responseCode = "200", description = "The contact's tags")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: contactId or tagId")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "CONTACT_NOT_FOUND or TAG_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CONTACT_ARCHIVED or CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{contactId}/tags/{tagId}")
  public List<TagView> tag(
      @PathVariable UUID contactId,
      @PathVariable UUID tagId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return contacts.tag(SelectedClinic.require(session), principal.subjectId(), contactId, tagId);
  }

  @Operation(
      operationId = "untagContact",
      summary = "Remove a tag from a contact (OWNER, MANAGER, ATTENDANT); idempotent")
  @ApiResponse(responseCode = "200", description = "The contact's tags")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: contactId or tagId")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED")
  @ApiResponse(responseCode = "404", description = "CONTACT_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "CLINIC_NOT_SELECTED")
  @SecurityRequirement(name = "csrf")
  @DeleteMapping("/{contactId}/tags/{tagId}")
  public List<TagView> untag(
      @PathVariable UUID contactId,
      @PathVariable UUID tagId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return contacts.untag(SelectedClinic.require(session), principal.subjectId(), contactId, tagId);
  }
}
