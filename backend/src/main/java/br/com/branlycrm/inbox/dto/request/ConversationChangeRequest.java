package br.com.branlycrm.inbox.dto.request;

/**
 * A change of a conversation, against the version the client read (CRM-083).
 *
 * @param assigneeId the member who will serve it (transfer only)
 * @param needsHuman the new mark (needs-human only)
 */
public record ConversationChangeRequest(Long version, String assigneeId, Boolean needsHuman) {}
