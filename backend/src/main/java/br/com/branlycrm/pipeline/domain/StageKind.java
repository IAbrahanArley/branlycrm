package br.com.branlycrm.pipeline.domain;

/**
 * What reaching a stage means for a lead: still open, won (sale) or lost (with a reason). The
 * declaration order is the display order of a funnel (open, then won, then lost): keep it.
 */
public enum StageKind {
  OPEN,
  WON,
  LOST
}
