package br.com.branlycrm.whatsapp.domain;

import java.util.Objects;

/** What the worker does with one pending event. */
public sealed interface InboundAction {
  /**
   * Nothing to write: statuses and account events until CRM-084 and CRM-081b, or an event that can
   * never be applied (an invalid number). The event is marked processed.
   *
   * @param code stable reason kept with the event, or null when there is nothing to report
   */
  record Skip(String code) implements InboundAction {}

  /**
   * A delivery status of a reply sent from the CRM (CRM-084a).
   *
   * @param status sent, delivered, read or failed (lower case, as Meta sends it)
   * @param outboxId the {@code biz_opaque_callback_data}, when it is an outbox id
   * @param errorCode for failed: META_ and Meta's error code
   */
  record Status(
      String wamid, String status, java.util.UUID outboxId, java.time.Instant at, String errorCode)
      implements InboundAction {
    public Status {
      Objects.requireNonNull(wamid, "wamid");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(at, "at");
    }
  }

  /** Contact, lead (RN-01), origin and message, in one transaction. */
  record Store(InboundMessage message) implements InboundAction {
    public Store {
      Objects.requireNonNull(message, "message");
    }
  }
}
