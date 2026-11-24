package br.com.branlycrm.inbox.services;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.inbox.domain.ConversationSummary;
import br.com.branlycrm.inbox.domain.InboxFilter;
import br.com.branlycrm.inbox.domain.InboxMessage;
import br.com.branlycrm.inbox.domain.MessageCursor;
import br.com.branlycrm.inbox.domain.OutboxItem;
import br.com.branlycrm.inbox.domain.QueuedReply;
import br.com.branlycrm.inbox.dto.request.ConversationChangeRequest;
import br.com.branlycrm.inbox.dto.request.ReplyRequest;
import br.com.branlycrm.inbox.dto.response.ConversationView;
import br.com.branlycrm.inbox.dto.response.MessagePageView;
import br.com.branlycrm.inbox.dto.response.OutboxView;
import br.com.branlycrm.inbox.exceptions.InboxException;
import br.com.branlycrm.inbox.repositories.InboxRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.services.TenantAccessService;
import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Shared inbox of the selected clinic (CRM-083).
 *
 * <ul>
 *   <li>INBOX_READ (every role) lists conversations and reads the history.
 *   <li>INBOX_REPLY (owner, manager, attendant) takes a conversation, releases one's own and marks
 *       "needs a person".
 *   <li>INBOX_ASSIGN (owner, manager) transfers to another member and releases a colleague's.
 * </ul>
 *
 * Inputs are validated before the access check; the assignee is an active member who replies.
 */
@Service
public class InboxService {
  static final int MESSAGES_DEFAULT = 50;
  static final int MESSAGES_MAX = 100;
  static final int REPLY_MAX = 4096;

  private final TenantAccessService access;
  private final InboxRepository inbox;
  private final ChannelRegistryRepository channels;

  public InboxService(
      TenantAccessService access, InboxRepository inbox, ChannelRegistryRepository channels) {
    this.access = access;
    this.inbox = inbox;
    this.channels = channels;
  }

  /**
   * @param assignee {@code mine}, {@code none} or null
   */
  public PageView<ConversationView> list(
      UUID tenantId,
      UUID actor,
      String status,
      String assignee,
      String needsHuman,
      PageQuery page) {
    if (status != null && !status.equals("WAITING") && !status.equals("IN_SERVICE")) {
      throw new InvalidInputException("status");
    }
    if (assignee != null && !assignee.equals("mine") && !assignee.equals("none")) {
      throw new InvalidInputException("assignee");
    }
    Boolean marked = bool(needsHuman, "needsHuman");
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_READ);
    InboxFilter filter =
        new InboxFilter(
            status, "mine".equals(assignee) ? actor : null, "none".equals(assignee), marked);
    PageResult<ConversationSummary> result = inbox.search(clinic, filter, page);
    return new PageView<>(
        result.items().stream().map(InboxService::view).toList(),
        page.page(),
        page.pageSize(),
        result.total());
  }

  public ConversationView get(UUID tenantId, UUID actor, UUID conversationId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_READ);
    return view(inbox.get(clinic, conversationId));
  }

  /**
   * @param before older messages than this cursor
   * @param after newer messages than this cursor (the poll); not with {@code before}
   */
  public MessagePageView messages(
      UUID tenantId, UUID actor, UUID conversationId, String before, String after, String limit) {
    if (before != null && after != null) throw new InvalidInputException("after");
    MessageCursor older = before == null ? null : MessageCursor.decode(before, "before");
    MessageCursor newer = after == null ? null : MessageCursor.decode(after, "after");
    int size = limit(limit);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_READ);
    List<InboxMessage> messages = inbox.messages(clinic, conversationId, older, newer, size);
    String olderCursor =
        messages.isEmpty() || (newer != null)
            ? null
            : MessageCursor.of(messages.getFirst()).encode();
    String newerCursor =
        messages.isEmpty()
            ? (newer == null ? null : after)
            : MessageCursor.of(messages.getLast()).encode();
    if (older != null && messages.isEmpty()) newerCursor = null;
    return new MessagePageView(
        messages.stream()
            .map(
                m ->
                    new MessagePageView.Message(
                        m.id(),
                        m.direction(),
                        m.sentByApp(),
                        m.type(),
                        m.body(),
                        m.mediaMime(),
                        m.sentAt(),
                        m.status()))
            .toList(),
        olderCursor,
        newerCursor);
  }

  /**
   * Queues a text reply (CRM-084a): the worker sends it. Answering a conversation nobody serves
   * takes it; someone else's needs INBOX_ASSIGN. The 24-hour window is checked here and again by
   * the worker.
   */
  public OutboxView reply(UUID tenantId, UUID actor, UUID conversationId, ReplyRequest request) {
    if (request == null) throw new InvalidInputException("body");
    UUID key = uuid(request.idempotencyKey(), "idempotencyKey");
    String text = replyText(request.text());
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_REPLY);
    QueuedReply queued =
        inbox.enqueue(
            clinic, conversationId, key, text, clinic.allows(ClinicPermission.INBOX_ASSIGN));
    // After the clinic commit. Also on a repeated request: if the first signal was lost (the API
    // failed after the commit), the retry with the same key raises it. It is idempotent.
    if (!queued.item().status().equals("FAILED")) channels.signalOutbound(queued.channelId());
    return outbox(queued.item());
  }

  /** Replies of the conversation still queued, sending, uncertain or recently failed. */
  public List<OutboxView> pendingReplies(UUID tenantId, UUID actor, UUID conversationId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_READ);
    return inbox.pendingReplies(clinic, conversationId).stream().map(InboxService::outbox).toList();
  }

  /** 1 to 4096 characters; line breaks and tabs kept, other control characters refused. */
  static String replyText(String text) {
    if (text == null || text.isBlank() || text.length() > REPLY_MAX) {
      throw new InvalidInputException("text");
    }
    if (text.chars()
        .anyMatch(c -> Character.isISOControl(c) && c != 0x0A && c != 0x0D && c != 0x09)) {
      throw new InvalidInputException("text");
    }
    return text;
  }

  private static OutboxView outbox(OutboxItem item) {
    return new OutboxView(
        item.id(),
        item.conversationId(),
        item.status(),
        item.body(),
        item.errorCode(),
        item.requestedBy(),
        item.createdAt(),
        item.sentAt());
  }

  public ConversationView take(UUID tenantId, UUID actor, UUID conversationId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_REPLY);
    return view(inbox.take(clinic, conversationId));
  }

  public ConversationView release(
      UUID tenantId, UUID actor, UUID conversationId, ConversationChangeRequest request) {
    long version = version(request);
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_REPLY);
    return view(
        inbox.release(
            clinic, conversationId, version, clinic.allows(ClinicPermission.INBOX_ASSIGN)));
  }

  public ConversationView assign(
      UUID tenantId, UUID actor, UUID conversationId, ConversationChangeRequest request) {
    long version = version(request);
    UUID assignee = uuid(request.assigneeId(), "assigneeId");
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_ASSIGN);
    requireReplier(tenantId, assignee);
    return view(inbox.assign(clinic, conversationId, version, assignee));
  }

  public ConversationView markNeedsHuman(
      UUID tenantId, UUID actor, UUID conversationId, ConversationChangeRequest request) {
    long version = version(request);
    if (request.needsHuman() == null) throw new InvalidInputException("needsHuman");
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_REPLY);
    return view(inbox.markNeedsHuman(clinic, conversationId, version, request.needsHuman()));
  }

  private void requireReplier(UUID tenantId, UUID member) {
    ClinicAccess assignee;
    try {
      assignee = access.authorize(tenantId, member);
    } catch (TenantAccessException notAMember) {
      throw new InboxException(InboxException.Reason.ASSIGNEE_INVALID);
    }
    if (!assignee.allows(ClinicPermission.INBOX_REPLY)) {
      throw new InboxException(InboxException.Reason.ASSIGNEE_INVALID);
    }
  }

  private static ConversationView view(ConversationSummary c) {
    return new ConversationView(
        c.id(),
        c.contactId(),
        c.contactName(),
        c.contactPhone(),
        c.status(),
        c.assigneeId(),
        c.assignedAt(),
        c.needsHuman(),
        c.awaitingReply(),
        c.lastMessageAt(),
        c.lastInboundAt(),
        c.lastType(),
        c.preview(),
        c.contactArchived(),
        c.version());
  }

  private static long version(ConversationChangeRequest request) {
    if (request == null) throw new InvalidInputException("body");
    if (request.version() == null || request.version() < 0) {
      throw new InvalidInputException("version");
    }
    return request.version();
  }

  private static UUID uuid(String value, String field) {
    if (value == null) throw new InvalidInputException(field);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(field);
    }
  }

  private static Boolean bool(String value, String field) {
    if (value == null) return null;
    if (value.equals("true")) return true;
    if (value.equals("false")) return false;
    throw new InvalidInputException(field);
  }

  private static int limit(String value) {
    if (value == null) return MESSAGES_DEFAULT;
    try {
      int size = Integer.parseInt(value);
      if (size < 1 || size > MESSAGES_MAX) throw new InvalidInputException("limit");
      return size;
    } catch (NumberFormatException invalid) {
      throw new InvalidInputException("limit");
    }
  }
}
