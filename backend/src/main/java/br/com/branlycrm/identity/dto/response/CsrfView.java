package br.com.branlycrm.identity.dto.response;

/** CSRF token for the current session; request a new one after login and logout. */
public record CsrfView(String token, String headerName, String parameterName) {}
