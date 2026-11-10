package br.com.branlycrm.pipeline.domain;

/** What a timeline entry records (CRM-09, RN-08). */
public enum LeadEventType {
  CREATED,
  STAGE_CHANGED,
  TEMPERATURE_CHANGED,
  RESPONSIBLE_CHANGED,
  WON,
  LOST
}
