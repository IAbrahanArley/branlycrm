package br.com.branlycrm.pipeline.dto.request;

/** COLD, WARM, HOT or QUALIFIED, at the version read. */
public record TemperatureChangeRequest(String temperature, Long version) {}
