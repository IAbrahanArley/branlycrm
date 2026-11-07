package br.com.branlycrm.agency.domain;

import java.util.UUID;

/** A partner agency (ADR-026). Its admins manage a portfolio, never clinic data. */
public record Agency(UUID agencyId, String name, String status) {}
