package br.com.branlycrm.inbox.controllers;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.inbox.domain.MediaUpload;
import br.com.branlycrm.inbox.dto.request.ConversationChangeRequest;
import br.com.branlycrm.inbox.dto.request.ReplyRequest;
import br.com.branlycrm.inbox.dto.request.TemplateReplyRequest;
import br.com.branlycrm.inbox.dto.response.ConversationView;
import br.com.branlycrm.inbox.dto.response.MediaLinkView;
import br.com.branlycrm.inbox.dto.response.MessagePageView;
import br.com.branlycrm.inbox.dto.response.OutboxView;
import br.com.branlycrm.inbox.services.InboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Shared WhatsApp inbox of the clinic selected in the session (CRM-083). */
@Tag(name = "Inbox", description = "WhatsApp conversations shared by the clinic's team")
@SecurityRequirement(name = "session")
@RestController
@RequestMapping("/v1/clinic/inbox/conversations")
public final class InboxController {
  private final InboxService inbox;

  public InboxController(InboxService inbox) {
    this.inbox = inbox;
  }

  @Operation(
      operationId = "listConversations",
      summary = "Conversations, most recent first (every role); poll every 5 s while visible")
  @ApiResponse(responseCode = "200", description = "Page of conversations")
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INPUT: status, assignee, needsHuman, page or pageSize")
  @GetMapping
  public PageView<ConversationView> list(
      @Parameter(description = "WAITING or IN_SERVICE") @RequestParam(required = false)
          String status,
      @Parameter(description = "mine or none") @RequestParam(required = false) String assignee,
      @Parameter(description = "true or false") @RequestParam(required = false) String needsHuman,
      @RequestParam(required = false) String page,
      @RequestParam(required = false) String pageSize,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.list(
        SelectedClinic.require(session),
        principal.subjectId(),
        status,
        assignee,
        needsHuman,
        PageQuery.of(page, pageSize));
  }

  @Operation(operationId = "getConversation", summary = "One conversation (every role)")
  @ApiResponse(responseCode = "200", description = "Conversation")
  @ApiResponse(responseCode = "404", description = "CONVERSATION_NOT_FOUND")
  @GetMapping("/{conversationId}")
  public ConversationView get(
      @PathVariable UUID conversationId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.get(SelectedClinic.require(session), principal.subjectId(), conversationId);
  }

  @Operation(
      operationId = "listConversationMessages",
      summary =
          "History, oldest first: the latest messages, the ones before a cursor, or the new ones"
              + " after a cursor (poll)")
  @ApiResponse(responseCode = "200", description = "Messages and cursors")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: before, after or limit")
  @ApiResponse(responseCode = "404", description = "CONVERSATION_NOT_FOUND")
  @GetMapping("/{conversationId}/messages")
  public MessagePageView messages(
      @PathVariable UUID conversationId,
      @RequestParam(required = false) String before,
      @RequestParam(required = false) String after,
      @Parameter(description = "1 to 100, default 50") @RequestParam(required = false) String limit,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.messages(
        SelectedClinic.require(session),
        principal.subjectId(),
        conversationId,
        before,
        after,
        limit);
  }

  @Operation(
      operationId = "replyToConversation",
      summary =
          "Queue a text reply (OWNER, MANAGER, ATTENDANT); takes a conversation nobody serves."
              + " The worker sends it; the same idempotencyKey never queues twice")
  @ApiResponse(responseCode = "202", description = "The reply in the outbox")
  @ApiResponse(responseCode = "400", description = "INVALID_INPUT: idempotencyKey or text")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED (VIEWER)")
  @ApiResponse(responseCode = "404", description = "CONVERSATION_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "CONVERSATION_TAKEN, CONVERSATION_WINDOW_CLOSED or CONVERSATION_IDEMPOTENCY_CONFLICT")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{conversationId}/messages")
  public ResponseEntity<OutboxView> reply(
      @PathVariable UUID conversationId,
      @RequestBody ReplyRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return ResponseEntity.accepted()
        .body(
            inbox.reply(
                SelectedClinic.require(session), principal.subjectId(), conversationId, request));
  }

  @Operation(
      operationId = "sendConversationMedia",
      summary =
          "Queue a file (multipart: file, idempotencyKey, optional caption) like a text reply."
              + " Type from the first bytes: JPG, PNG, MP4, 3GP, audio, PDF and Office; images up"
              + " to 5 MB, the rest up to 16 MB")
  @ApiResponse(responseCode = "202", description = "The file in the outbox (kind MEDIA)")
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INPUT: idempotencyKey, caption (also any caption on audio) or file")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED (VIEWER)")
  @ApiResponse(responseCode = "404", description = "CONVERSATION_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "CONVERSATION_TAKEN, CONVERSATION_WINDOW_CLOSED or CONVERSATION_IDEMPOTENCY_CONFLICT")
  @ApiResponse(
      responseCode = "413",
      description = "CONVERSATION_MEDIA_TOO_LARGE (FILE_TOO_LARGE above 17 MB)")
  @ApiResponse(responseCode = "415", description = "CONVERSATION_MEDIA_TYPE_NOT_ALLOWED")
  @ApiResponse(responseCode = "503", description = "CONVERSATION_MEDIA_UNAVAILABLE")
  @SecurityRequirement(name = "csrf")
  @PostMapping(
      path = "/{conversationId}/messages/media",
      consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<OutboxView> sendMedia(
      @PathVariable UUID conversationId,
      @RequestPart("file") MultipartFile file,
      @RequestParam(name = "idempotencyKey", required = false) String idempotencyKey,
      @RequestParam(name = "caption", required = false) String caption,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return ResponseEntity.accepted()
        .body(
            inbox.sendMedia(
                SelectedClinic.require(session),
                principal.subjectId(),
                conversationId,
                idempotencyKey,
                caption,
                new MediaUpload(file.getOriginalFilename(), file.getSize(), file::getInputStream)));
  }

  @Operation(
      operationId = "sendConversationTemplate",
      summary =
          "Queue an approved template, inside or outside the 24-hour window (OWNER, MANAGER,"
              + " ATTENDANT), with the values of its parameters; at most 200 a day per clinic")
  @ApiResponse(responseCode = "202", description = "The template in the outbox (kind TEMPLATE)")
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INPUT: idempotencyKey, templateId, header.KEY or body.KEY")
  @ApiResponse(responseCode = "403", description = "CLINIC_ACCESS_DENIED (VIEWER)")
  @ApiResponse(
      responseCode = "404",
      description = "CONVERSATION_NOT_FOUND or CONVERSATION_TEMPLATE_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description =
          "CONVERSATION_TAKEN, CONVERSATION_IDEMPOTENCY_CONFLICT,"
              + " CONVERSATION_TEMPLATE_NOT_APPROVED or CONVERSATION_TEMPLATE_OPTED_OUT")
  @ApiResponse(responseCode = "429", description = "CONVERSATION_TEMPLATE_LIMIT")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{conversationId}/messages/template")
  public ResponseEntity<OutboxView> sendTemplate(
      @PathVariable UUID conversationId,
      @RequestBody TemplateReplyRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return ResponseEntity.accepted()
        .body(
            inbox.sendTemplate(
                SelectedClinic.require(session), principal.subjectId(), conversationId, request));
  }

  @Operation(
      operationId = "listPendingReplies",
      summary = "Replies still queued, sending or uncertain, and failures of the last 7 days")
  @ApiResponse(responseCode = "200", description = "Outbox items, newest first")
  @ApiResponse(responseCode = "404", description = "CONVERSATION_NOT_FOUND")
  @GetMapping("/{conversationId}/outbox")
  public List<OutboxView> pendingReplies(
      @PathVariable UUID conversationId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.pendingReplies(
        SelectedClinic.require(session), principal.subjectId(), conversationId);
  }

  @Operation(
      operationId = "getMessageMedia",
      summary =
          "Temporary link (5 min) to the file of a received message (every role); downloads as"
              + " an attachment")
  @ApiResponse(responseCode = "200", description = "Link, type and expiry")
  @ApiResponse(responseCode = "404", description = "CONVERSATION_MEDIA_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description = "CONVERSATION_MEDIA_NOT_READY or CONVERSATION_MEDIA_REJECTED")
  @GetMapping("/{conversationId}/messages/{messageId}/media")
  public MediaLinkView media(
      @PathVariable UUID conversationId,
      @PathVariable UUID messageId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.mediaLink(
        SelectedClinic.require(session), principal.subjectId(), conversationId, messageId);
  }

  @Operation(
      operationId = "takeConversation",
      summary = "Take a conversation nobody serves (OWNER, MANAGER, ATTENDANT); atomic")
  @ApiResponse(responseCode = "200", description = "Conversation, IN_SERVICE with the actor")
  @ApiResponse(responseCode = "409", description = "CONVERSATION_TAKEN")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{conversationId}/take")
  public ConversationView take(
      @PathVariable UUID conversationId,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.take(SelectedClinic.require(session), principal.subjectId(), conversationId);
  }

  @Operation(
      operationId = "releaseConversation",
      summary = "Release back to WAITING: one's own (ATTENDANT up), anyone's (OWNER, MANAGER)")
  @ApiResponse(responseCode = "200", description = "Conversation, WAITING")
  @ApiResponse(
      responseCode = "409",
      description = "CONVERSATION_STALE, CONVERSATION_NOT_ASSIGNED or CONVERSATION_NOT_YOURS")
  @SecurityRequirement(name = "csrf")
  @PostMapping("/{conversationId}/release")
  public ConversationView release(
      @PathVariable UUID conversationId,
      @RequestBody ConversationChangeRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.release(
        SelectedClinic.require(session), principal.subjectId(), conversationId, request);
  }

  @Operation(
      operationId = "assignConversation",
      summary = "Transfer to an active member who replies (OWNER, MANAGER)")
  @ApiResponse(responseCode = "200", description = "Conversation, IN_SERVICE with the assignee")
  @ApiResponse(
      responseCode = "409",
      description = "CONVERSATION_STALE or CONVERSATION_ASSIGNEE_INVALID")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{conversationId}/assignee")
  public ConversationView assign(
      @PathVariable UUID conversationId,
      @RequestBody ConversationChangeRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.assign(
        SelectedClinic.require(session), principal.subjectId(), conversationId, request);
  }

  @Operation(
      operationId = "markConversationNeedsHuman",
      summary = "Mark or clear \"needs a person\" (OWNER, MANAGER, ATTENDANT)")
  @ApiResponse(responseCode = "200", description = "Conversation")
  @ApiResponse(responseCode = "409", description = "CONVERSATION_STALE")
  @SecurityRequirement(name = "csrf")
  @PutMapping("/{conversationId}/needs-human")
  public ConversationView markNeedsHuman(
      @PathVariable UUID conversationId,
      @RequestBody ConversationChangeRequest request,
      @AuthenticationPrincipal CrmPrincipal principal,
      HttpSession session) {
    return inbox.markNeedsHuman(
        SelectedClinic.require(session), principal.subjectId(), conversationId, request);
  }
}
