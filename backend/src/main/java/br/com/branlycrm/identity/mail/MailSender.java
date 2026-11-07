package br.com.branlycrm.identity.mail;

/** Sends transactional e-mail (ADR-021). Never called inside a database transaction. */
public interface MailSender {
  /**
   * @return {@code true} when the provider accepted the message; {@code false} on any failure,
   *     which is logged with a stable code only, never with the message body.
   */
  boolean send(MailMessage message);
}
