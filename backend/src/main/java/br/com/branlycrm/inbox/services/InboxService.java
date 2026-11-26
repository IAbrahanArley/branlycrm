package br.com.branlycrm.inbox.services;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.common.domain.PageResult;
import br.com.branlycrm.common.dto.response.PageView;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.inbox.domain.ConversationSummary;
import br.com.branlycrm.inbox.domain.InboxFilter;
import br.com.branlycrm.inbox.domain.InboxMessage;
import br.com.branlycrm.inbox.domain.MediaUpload;
import br.com.branlycrm.inbox.domain.MessageCursor;
import br.com.branlycrm.inbox.domain.OutboxItem;
import br.com.branlycrm.inbox.domain.QueuedReply;
import br.com.branlycrm.inbox.dto.request.ConversationChangeRequest;
import br.com.branlycrm.inbox.dto.request.ReplyRequest;
import br.com.branlycrm.inbox.dto.request.TemplateReplyRequest;
import br.com.branlycrm.inbox.dto.response.ConversationView;
import br.com.branlycrm.inbox.dto.response.MediaLinkView;
import br.com.branlycrm.inbox.dto.response.MessagePageView;
import br.com.branlycrm.inbox.dto.response.OutboxView;
import br.com.branlycrm.inbox.dto.response.TemplateView;
import br.com.branlycrm.inbox.exceptions.InboxException;
import br.com.branlycrm.inbox.repositories.InboxRepository;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicPermission;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.services.TenantAccessService;
import br.com.branlycrm.whatsapp.domain.MediaType;
import br.com.branlycrm.whatsapp.domain.MessageTemplate;
import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import br.com.branlycrm.whatsapp.storage.MediaStorage;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
  static final int CAPTION_MAX = 1024;

  private final TenantAccessService access;
  private final InboxRepository inbox;
  private final ChannelRegistryRepository channels;
  private final MediaStorage storage;
  private final java.time.Clock clock;
  private final int templateDailyLimit;

  public InboxService(
      TenantAccessService access,
      InboxRepository inbox,
      ChannelRegistryRepository channels,
      MediaStorage storage,
      org.springframework.beans.factory.ObjectProvider<java.time.Clock> clock,
      @org.springframework.beans.factory.annotation.Value(
              "${app.whatsapp.templates.daily-limit:200}")
          int templateDailyLimit) {
    this.access = access;
    this.inbox = inbox;
    this.channels = channels;
    this.storage = storage;
    this.clock = clock.getIfAvailable(java.time.Clock::systemUTC);
    this.templateDailyLimit = templateDailyLimit;
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
                        m.status(),
                        m.mediaStatus()))
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

  /**
   * Queues a file to send (CRM-084b2), with the rules of {@link #reply}. The type comes from the
   * first bytes, never from the name or the declared type; the file is stored in the private bucket
   * before the outbox item is written, and removed again when the send is refused meanwhile.
   *
   * @param caption optional, up to 1024 characters; not for audio
   */
  public OutboxView sendMedia(
      UUID tenantId,
      UUID actor,
      UUID conversationId,
      String idempotencyKey,
      String caption,
      MediaUpload upload) {
    UUID key = uuid(idempotencyKey, "idempotencyKey");
    String text = caption == null || caption.isEmpty() ? null : caption(caption);
    if (upload == null || upload.size() <= 0) throw new InvalidInputException("file");
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_REPLY);
    boolean override = clinic.allows(ClinicPermission.INBOX_ASSIGN);
    Optional<QueuedReply> repeated = inbox.checkReply(clinic, conversationId, key, override);
    if (repeated.isPresent()) return signalled(repeated.get());
    if (upload.size() > MediaType.OUTBOUND_MAX_BYTES) {
      throw new InboxException(InboxException.Reason.MEDIA_TOO_LARGE);
    }
    Inspected file = inspect(upload);
    MediaType type =
        MediaType.detect(file.head())
            .filter(detected -> detected.outboundMessageType().isPresent())
            .orElseThrow(() -> new InboxException(InboxException.Reason.MEDIA_TYPE_NOT_ALLOWED));
    if (file.size() > type.outboundMaxBytes()) {
      throw new InboxException(InboxException.Reason.MEDIA_TOO_LARGE);
    }
    if (text != null && type.kind() == MediaType.Kind.AUDIO) {
      throw new InvalidInputException("caption");
    }
    String fileName =
        type.kind() == MediaType.Kind.DOCUMENT ? fileName(upload.fileName(), type) : null;
    UUID mediaId = UUID.randomUUID();
    String storageKey = "clinics/" + clinic.tenantId() + "/media/" + mediaId;
    try {
      storage.put(
          storageKey,
          () -> {
            try {
              return upload.opener().open();
            } catch (IOException unreadable) {
              throw new java.io.UncheckedIOException(unreadable);
            }
          },
          type.contentType(),
          file.size());
    } catch (MediaStorage.StorageException unavailable) {
      throw new InboxException(InboxException.Reason.MEDIA_UNAVAILABLE);
    }
    QueuedReply queued;
    try {
      queued =
          inbox.enqueueMedia(
              clinic,
              conversationId,
              key,
              text,
              new InboxRepository.OutboundFile(
                  mediaId, storageKey, type.contentType(), file.size(), file.sha256(), fileName),
              override);
    } catch (RuntimeException refused) {
      // Also on an ambiguous commit (connection lost during COMMIT): the row may exist without
      // its object, and the worker then fails it with STORAGE_UNAVAILABLE after its retries.
      storage.delete(storageKey);
      throw refused;
    }
    // A concurrent request with the same key won: its file is the one sent.
    if (!queued.created()) storage.delete(storageKey);
    return signalled(queued);
  }

  /** The first bytes, the counted size and the SHA-256 of an upload, read once. */
  private record Inspected(byte[] head, long size, String sha256) {}

  private static Inspected inspect(MediaUpload upload) {
    try (InputStream in = upload.opener().open()) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] head = new byte[MediaType.HEAD_BYTES];
      int headLength = 0;
      long total = 0;
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > MediaType.OUTBOUND_MAX_BYTES) {
          throw new InboxException(InboxException.Reason.MEDIA_TOO_LARGE);
        }
        digest.update(buffer, 0, read);
        int copy = Math.min(read, head.length - headLength);
        if (copy > 0) {
          System.arraycopy(buffer, 0, head, headLength, copy);
          headLength += copy;
        }
      }
      if (total == 0 || total != upload.size()) throw new InvalidInputException("file");
      return new Inspected(
          Arrays.copyOf(head, headLength), total, HexFormat.of().formatHex(digest.digest()));
    } catch (IOException unreadable) {
      throw new InvalidInputException("file");
    } catch (NoSuchAlgorithmException missing) {
      throw new IllegalStateException("SHA-256 unavailable", missing);
    }
  }

  /**
   * The name the patient sees for a document: the last segment of what the client sent, without
   * control characters, quotes or separators, at most 120 characters, ending with the extension of
   * the detected type.
   */
  static String fileName(String requested, MediaType type) {
    String suffix = "." + type.extension();
    String name = requested == null ? "" : requested;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name =
        name.codePoints()
            .filter(InboxService::visibleNameCharacter)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString()
            .strip();
    if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
      name = name.substring(0, name.length() - suffix.length()).strip();
    }
    if (name.isEmpty()) name = "documento";
    int max = 120 - suffix.length();
    if (name.codePointCount(0, name.length()) > max) {
      name = name.substring(0, name.offsetByCodePoints(0, max)).strip();
    }
    return name + suffix;
  }

  /**
   * Printable characters only: no control, format (bidirectional overrides that disguise a name),
   * line or paragraph separators, surrogates or private use; and no quotes or path separators.
   */
  private static boolean visibleNameCharacter(int c) {
    if (c == '"' || c == '/' || c == '\\') return false;
    return switch (Character.getType(c)) {
      case Character.CONTROL,
          Character.FORMAT,
          Character.LINE_SEPARATOR,
          Character.PARAGRAPH_SEPARATOR,
          Character.SURROGATE,
          Character.PRIVATE_USE,
          Character.UNASSIGNED ->
          false;
      default -> true;
    };
  }

  /** 1 to 1024 characters (Meta's caption limit), with the rules of a reply's text. */
  static String caption(String caption) {
    if (caption.length() > CAPTION_MAX) throw new InvalidInputException("caption");
    try {
      return replyText(caption);
    } catch (InvalidInputException invalid) {
      throw new InvalidInputException("caption");
    }
  }

  /** Raises the outbound signal (also on a repeated request: the first may have been lost). */
  private OutboxView signalled(QueuedReply queued) {
    if (!queued.item().status().equals("FAILED")) channels.signalOutbound(queued.channelId());
    return outbox(queued.item());
  }

  /**
   * Queues an approved template (CRM-085), inside or outside the 24-hour window, with the other
   * rules of {@link #reply}. The values are checked against the template's parameters with Meta's
   * rules; the history keeps the text as the patient sees it.
   */
  public OutboxView sendTemplate(
      UUID tenantId, UUID actor, UUID conversationId, TemplateReplyRequest request) {
    if (request == null) throw new InvalidInputException("body");
    UUID key = uuid(request.idempotencyKey(), "idempotencyKey");
    UUID templateId = uuid(request.templateId(), "templateId");
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_REPLY);
    boolean override = clinic.allows(ClinicPermission.INBOX_ASSIGN);
    // A repeated request returns what the first one queued, even if the template changed since.
    Optional<QueuedReply> repeated = inbox.checkReply(clinic, conversationId, key, override, false);
    if (repeated.isPresent()) return signalled(repeated.get());
    InboxRepository.TemplateOption option =
        inbox
            .template(clinic, templateId)
            .orElseThrow(() -> new InboxException(InboxException.Reason.TEMPLATE_NOT_FOUND));
    MessageTemplate template = option.template();
    if (!template.approved() || !template.supported()) {
      throw new InboxException(InboxException.Reason.TEMPLATE_NOT_APPROVED);
    }
    Map<String, String> header = request.header() == null ? Map.of() : request.header();
    Map<String, String> body = request.body() == null ? Map.of() : request.body();
    String text;
    try {
      text = template.render(header, body);
    } catch (IllegalArgumentException invalid) {
      throw new InvalidInputException(invalid.getMessage());
    }
    String values = templateValues(header, body);
    QueuedReply queued =
        inbox.enqueueTemplate(
            clinic, conversationId, key, templateId, text, values, templateDailyLimit, override);
    return signalled(queued);
  }

  /** The clinic's templates, approved and supported first (CRM-085, INBOX_READ). */
  public List<TemplateView> templates(UUID tenantId, UUID actor) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_READ);
    return inbox.templates(clinic).stream().map(InboxService::templateView).toList();
  }

  /**
   * Asks the worker to copy the templates again, for one approved minutes ago (CRM-085,
   * CHANNEL_CONNECT).
   */
  public void requestTemplateSync(UUID tenantId, UUID actor) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.CHANNEL_CONNECT);
    if (!channels.requestTemplateSync(clinic.tenantId())) {
      throw new InboxException(InboxException.Reason.CHANNEL_NOT_CONNECTED);
    }
  }

  private static TemplateView templateView(InboxRepository.TemplateOption option) {
    MessageTemplate t = option.template();
    return new TemplateView(
        option.id(),
        t.name(),
        t.language(),
        t.category(),
        t.status(),
        t.supported(),
        t.approved() && t.supported(),
        t.headerText(),
        t.bodyText(),
        t.footerText(),
        t.headerParams(),
        t.bodyParams(),
        option.syncedAt());
  }

  private static String templateValues(Map<String, String> header, Map<String, String> body) {
    var root = TEMPLATE_JSON.createObjectNode();
    var headerNode = root.putObject("header");
    header.forEach(headerNode::put);
    var bodyNode = root.putObject("body");
    body.forEach(bodyNode::put);
    return TEMPLATE_JSON.writeValueAsString(root);
  }

  private static final tools.jackson.databind.json.JsonMapper TEMPLATE_JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

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
        item.sentAt(),
        item.kind(),
        item.contentType(),
        item.fileName());
  }

  /**
   * A temporary link to the file of a received message (CRM-084b1, INBOX_READ). The object key
   * comes from the clinic's database; the link downloads as an attachment with the detected type.
   */
  public MediaLinkView mediaLink(UUID tenantId, UUID actor, UUID conversationId, UUID messageId) {
    ClinicAccess clinic = access.require(tenantId, actor, ClinicPermission.INBOX_READ);
    InboxRepository.MediaFile file =
        inbox
            .media(clinic, conversationId, messageId)
            .orElseThrow(() -> new InboxException(InboxException.Reason.MEDIA_NOT_FOUND));
    switch (file.status()) {
      case "STORED" -> {}
      case "PENDING" -> throw new InboxException(InboxException.Reason.MEDIA_NOT_READY);
      default -> throw new InboxException(InboxException.Reason.MEDIA_REJECTED);
    }
    String extension =
        java.util.Arrays.stream(MediaType.values())
            .filter(type -> type.contentType().equals(file.contentType()))
            .map(MediaType::extension)
            .findFirst()
            .orElse("bin");
    try {
      String url = storage.link(file.storageKey(), file.contentType(), messageId + "." + extension);
      return new MediaLinkView(
          url, file.contentType(), clock.instant().plus(MediaStorage.LINK_TTL));
    } catch (MediaStorage.StorageException unavailable) {
      throw new InboxException(InboxException.Reason.MEDIA_UNAVAILABLE);
    }
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
