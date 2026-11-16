package br.com.branlycrm.settings.dto.request;

/** New clinic name: trimmed, 1 to 160 characters, without control characters. */
public record ClinicNameRequest(String clinicName) {}
