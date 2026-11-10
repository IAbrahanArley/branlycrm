package br.com.branlycrm.contact.domain;

import java.util.List;
import java.util.Map;

/** A contact with its tags and current consent per channel, read together. */
public record ContactDetail(
    Contact contact, List<Tag> tags, Map<ConsentChannel, ConsentState> consent) {
  public ContactDetail {
    if (contact == null || tags == null || consent == null) {
      throw new IllegalArgumentException("Incomplete contact detail");
    }
    tags = List.copyOf(tags);
    consent = Map.copyOf(consent);
  }

  @Override
  public String toString() {
    return "ContactDetail[id=" + contact.id() + "]";
  }
}
