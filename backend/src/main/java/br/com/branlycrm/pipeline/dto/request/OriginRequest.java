package br.com.branlycrm.pipeline.dto.request;

/** An origin chosen by a person; {@code version} is the lead's on corrections. */
public record OriginRequest(String type, String code, String title, Long version) {}
