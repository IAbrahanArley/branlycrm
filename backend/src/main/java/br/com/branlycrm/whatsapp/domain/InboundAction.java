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

  /** Contact, lead (RN-01), origin and message, in one transaction. */
  record Store(InboundMessage message) implements InboundAction {
    public Store {
      Objects.requireNonNull(message, "message");
    }
  }
}
