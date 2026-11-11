package br.com.branlycrm.pipeline.dto.response;

import java.util.UUID;

/** A loss reason; archived ones are not offered for new losses. Send {@code version} to rename. */
public record LossReasonView(UUID id, String name, boolean archived, long version) {}
