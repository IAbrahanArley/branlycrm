package br.com.branlycrm.tenant.domain;

/**
 * How much of a clinic resource a role reaches (docs/permissoes.md, ADR-025). {@code OWN} limits
 * the action to the authenticated subject itself (for example, taking a lead for oneself), always
 * computed on the server.
 */
public enum AccessScope {
  ALL,
  OWN,
  NONE
}
