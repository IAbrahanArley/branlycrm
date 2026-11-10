package br.com.branlycrm.pipeline.dto.request;

/** Moves an open lead to another open stage, at the version read. */
public record StageChangeRequest(String stageId, Long version) {}
