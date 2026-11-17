package br.com.branlycrm.whatsapp.domain;

/** What an inbound WhatsApp event carries (ADR-036). */
public enum InboundKind {
  /** A message received, or echoed from the WhatsApp Business app (coexistence). */
  MESSAGE,
  /** A delivery status of a message sent by the clinic. */
  STATUS,
  /** Anything else: account, number, template or quality updates. */
  ACCOUNT
}
