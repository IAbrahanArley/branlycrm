package br.com.branlycrm.onboarding.dto.request;

/** Address of the clinic's first owner; may differ from the previous invitation. */
public record OwnerInvitationRequest(String ownerEmail) {}
