package br.com.branlycrm.common.exceptions;

import java.util.List;

/**
 * Client input that fails validation. Translated to 400 INVALID_INPUT listing only the field names,
 * never the rejected values. Other IllegalArgumentExceptions remain internal errors.
 */
public final class InvalidInputException extends RuntimeException {
  private final List<String> fields;

  public InvalidInputException(String... fields) {
    super("Invalid input: " + String.join(", ", fields));
    this.fields = List.of(fields);
  }

  public List<String> fields() {
    return fields;
  }
}
