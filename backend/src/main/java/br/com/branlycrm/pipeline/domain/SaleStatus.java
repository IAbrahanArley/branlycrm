package br.com.branlycrm.pipeline.domain;

/** Only active sales count as revenue (RN-04); a cancelled one stays in the history. */
public enum SaleStatus {
  ACTIVE,
  CANCELLED
}
