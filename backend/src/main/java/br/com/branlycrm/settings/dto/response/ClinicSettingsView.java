package br.com.branlycrm.settings.dto.response;

/** Settings of the selected clinic: its name and IANA time zone (CRM-013). */
public record ClinicSettingsView(String clinicName, String timeZone) {}
