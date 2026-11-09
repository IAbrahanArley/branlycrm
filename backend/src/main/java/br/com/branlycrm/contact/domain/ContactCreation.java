package br.com.branlycrm.contact.domain;

import java.util.UUID;

/**
 * Outcome of creating a contact: a new row, or the contact that already holds the number (archived
 * ones included). Never an error, so concurrent creations of one number resolve to one contact.
 */
public sealed interface ContactCreation {
  UUID id();

  record Created(UUID id) implements ContactCreation {}

  record Existing(UUID id) implements ContactCreation {}
}
