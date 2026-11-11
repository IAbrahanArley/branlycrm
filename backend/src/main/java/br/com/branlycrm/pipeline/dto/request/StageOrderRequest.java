package br.com.branlycrm.pipeline.dto.request;

import java.util.List;

/** Every active open stage id, in the new order, with the funnel's {@code version}. */
public record StageOrderRequest(List<String> stageIds, Long version) {}
