package br.com.branlycrm.settings.dto.request;

/** IANA Area/Location time zone, such as America/Sao_Paulo; offsets and Etc/ are refused. */
public record TimeZoneRequest(String timeZone) {}
