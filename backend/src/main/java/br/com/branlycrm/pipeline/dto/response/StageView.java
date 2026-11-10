package br.com.branlycrm.pipeline.dto.response;

import java.util.UUID;

/**
 * A funnel stage. {@code kind} is OPEN, WON or LOST; {@code key} is the stable key of a default
 * stage (NEW, IN_SERVICE, SCHEDULED, ATTENDED, WON, LOST) or null; {@code position} orders the open
 * stages and is null for WON and LOST, which always come last.
 */
public record StageView(UUID id, String name, String kind, String key, Integer position) {}
