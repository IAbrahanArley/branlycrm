package br.com.branlycrm.common.dto.response;

import java.util.List;

/**
 * One page of a list endpoint. {@code page} is 1-based; {@code total} counts every item matching
 * the filters, so the frontend can render pagination without a second request.
 */
public record PageView<T>(List<T> items, int page, int pageSize, long total) {
  public PageView {
    items = List.copyOf(items);
  }
}
