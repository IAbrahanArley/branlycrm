package br.com.branlycrm.identity.dto.request;

/** New clinic role: OWNER, MANAGER, ATTENDANT or VIEWER. */
public record RoleChangeRequest(String role) {}
