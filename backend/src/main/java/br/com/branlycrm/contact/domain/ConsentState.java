package br.com.branlycrm.contact.domain;

/** Current consent on one channel. No record means NONE, never an implicit opt-in. */
public enum ConsentState {
  OPT_IN,
  OPT_OUT,
  NONE
}
