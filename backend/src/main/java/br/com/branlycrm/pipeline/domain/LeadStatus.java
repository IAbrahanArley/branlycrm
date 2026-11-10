package br.com.branlycrm.pipeline.domain;

/** A lead is open until it reaches the won stage (by a sale) or the lost stage (with a reason). */
public enum LeadStatus {
  OPEN,
  WON,
  LOST
}
