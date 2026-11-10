package br.com.branlycrm.pipeline.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * The clinic's funnel with its active stages in display order: open stages by position, then the
 * won stage, then the lost one. {@code version} changes with every configuration change.
 */
public record PipelineView(UUID id, String name, long version, List<StageView> stages) {
  public PipelineView {
    stages = List.copyOf(stages);
  }
}
