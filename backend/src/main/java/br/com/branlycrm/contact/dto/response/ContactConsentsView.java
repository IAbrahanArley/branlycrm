package br.com.branlycrm.contact.dto.response;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Current consent per channel and the history, current record first on each channel. */
public record ContactConsentsView(Map<String, String> current, List<ConsentRecordView> history) {
  public ContactConsentsView {
    current = Collections.unmodifiableMap(new LinkedHashMap<>(current));
    history = List.copyOf(history);
  }
}
