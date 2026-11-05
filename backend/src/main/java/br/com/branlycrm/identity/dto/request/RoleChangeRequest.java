package br.com.branlycrm.identity.dto.request;

/** New clinic role: OWNER, MANAGER, RECEPTION, SALES or PROFESSIONAL. */
public record RoleChangeRequest(String role) {}
