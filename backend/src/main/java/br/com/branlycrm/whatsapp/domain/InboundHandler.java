package br.com.branlycrm.whatsapp.domain;

/** Turns a pending event into an action; pure, so it runs inside the clinic transaction. */
@FunctionalInterface
public interface InboundHandler {
  InboundAction decide(StoredEvent event);
}
