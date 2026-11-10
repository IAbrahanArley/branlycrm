package br.com.branlycrm.pipeline.domain;

/** How a sale was paid (CRM-05); optional on the sale. */
public enum PaymentMethod {
  PIX,
  CASH,
  CREDIT_CARD,
  DEBIT_CARD,
  BANK_SLIP,
  TRANSFER,
  OTHER
}
