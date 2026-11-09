package br.com.branlycrm.contact.domain;

/**
 * How a consent change reached the clinic. MANUAL is the team recording what the person said;
 * WHATSAPP is an explicit request in a conversation (a received message alone is never an opt-in).
 */
public enum ConsentSource {
  MANUAL,
  IMPORT,
  WHATSAPP
}
