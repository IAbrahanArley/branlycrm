package br.com.branlycrm.common.domain;

import java.util.List;

/** Items of one page plus the total matching the filters, as read by a repository. */
public record PageResult<T>(List<T> items, long total) {
  public PageResult {
    items = List.copyOf(items);
  }
}
