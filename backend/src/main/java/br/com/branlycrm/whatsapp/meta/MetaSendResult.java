package br.com.branlycrm.whatsapp.meta;

/**
 * Outcome of sending a message (CRM-084a, ADR-005): whether Meta may have received it decides if it
 * can be sent again.
 */
public sealed interface MetaSendResult {
  /** Accepted by Meta. */
  record Sent(String wamid) implements MetaSendResult {}

  /** Never reached Meta, or Meta refused it before processing (rate limit): safe to try again. */
  record NotSent(String code) implements MetaSendResult {}

  /**
   * May have reached Meta (timeout, connection lost after the request, server error): never sent
   * again automatically; a delivery status reconciles it.
   */
  record Uncertain(String code) implements MetaSendResult {}

  /** Refused for good (outside the 24-hour window, invalid number...). */
  record Refused(String code) implements MetaSendResult {}

  /** The channel's token is invalid or revoked (error 190). */
  record TokenInvalid() implements MetaSendResult {}
}
