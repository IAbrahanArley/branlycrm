package br.com.branlycrm.identity.domain;

import java.util.UUID;

/** Pending notice for an account about an event the API did not perform (V14), e.g. MFA_RESET. */
public record AccountNotice(UUID noticeId, String kind) {}
