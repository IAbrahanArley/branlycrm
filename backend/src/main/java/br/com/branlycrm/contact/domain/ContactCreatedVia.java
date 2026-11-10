package br.com.branlycrm.contact.domain;

/**
 * Where the contact record was created. Marketing origin belongs to the lead (CRM-090, RN-03), not
 * to the contact.
 */
public enum ContactCreatedVia {
  MANUAL,
  IMPORT,
  WHATSAPP
}
