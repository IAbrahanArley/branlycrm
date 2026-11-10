package br.com.branlycrm.contact.dto.request;

/**
 * A consent change recorded by the team. The source is always MANUAL on this API: imports and
 * WhatsApp record their own, and the client never chooses it.
 */
public record ConsentRequest(String channel, String status) {}
