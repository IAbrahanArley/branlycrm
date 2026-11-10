package br.com.branlycrm.pipeline.domain;

/**
 * Stable key of the default stages, independent of the name a clinic gives them. NEW is where every
 * lead starts (CRM-031, CRM-082); stages a clinic creates have no key.
 */
public enum StageKey {
  NEW,
  IN_SERVICE,
  SCHEDULED,
  ATTENDED,
  WON,
  LOST
}
