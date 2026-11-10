package br.com.branlycrm.pipeline.dto.request;

/** A note on the lead; free text up to 2000 characters, never searched. */
public record NoteRequest(String body) {}
