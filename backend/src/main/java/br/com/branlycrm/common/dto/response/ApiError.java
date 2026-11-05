package br.com.branlycrm.common.dto.response;

import java.util.List;

/**
 * Error body of every API response (docs/api-convencoes.md). {@code code} is stable and meant for
 * the frontend; {@code message} is human-readable Portuguese and may change; {@code fields} lists
 * the invalid input names for INVALID_INPUT and is empty otherwise. Never carries SQL, stack
 * traces, secrets or personal data.
 */
public record ApiError(String code, String message, List<String> fields) {
  public ApiError {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public ApiError(String code, String message) {
    this(code, message, List.of());
  }
}
