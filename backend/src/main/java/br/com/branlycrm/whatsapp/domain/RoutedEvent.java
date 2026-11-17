package br.com.branlycrm.whatsapp.domain;

import java.util.Objects;

/** An inbound event and the route that decides its clinic. */
public record RoutedEvent(WebhookRoute route, InboundEvent event) {
  public RoutedEvent {
    Objects.requireNonNull(route, "route");
    Objects.requireNonNull(event, "event");
  }
}
