package br.com.branlycrm.contact.domain;

import java.util.UUID;

public record Tag(UUID id, TagName name) {
  public Tag {
    if (id == null || name == null) throw new IllegalArgumentException("Incomplete tag");
  }
}
