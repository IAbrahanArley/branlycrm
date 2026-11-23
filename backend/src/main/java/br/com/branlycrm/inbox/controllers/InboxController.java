package br.com.branlycrm.inbox.controllers;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.identity.security.CrmPrincipal;
import br.com.branlycrm.identity.security.SelectedClinic;
import br.com.branlycrm.inbox.dto.request.ConversationChangeRequest;
import br.com.branlycrm.inbox.dto.response.ConversationView;
import br.com.branlycrm.inbox.dto.response.MessagePageView;
import br.com.branlycrm.inbox.services.InboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
