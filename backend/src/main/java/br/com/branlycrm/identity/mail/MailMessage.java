package br.com.branlycrm.identity.mail;

/**
 * One transactional e-mail. The body may carry a single-use link, so {@link #toString()} never
 * shows it.
 */
public record MailMessage(String to, String subject, String text) {
  public MailMessage {
    if (to == null || to.isBlank() || subject == null || subject.isBlank() || text == null) {
      throw new IllegalArgumentException("Recipient, subject and text are required");
    }
  }

  @Override
  public String toString() {
    return "MailMessage[subject=" + subject + "]";
  }
}
