package br.com.branlycrm.tenant.domain;

/**
 * How much of a clinic resource a role reaches (docs/permissoes.md). {@code OWN} means records
 * assigned to, created by, or linked to the authenticated subject, always computed on the server.
 */
public enum AccessScope {
  ALL,
  OWN,
  NONE
}
