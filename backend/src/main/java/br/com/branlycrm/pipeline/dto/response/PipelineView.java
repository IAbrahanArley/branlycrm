package br.com.branlycrm.pipeline.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * The clinic's funnel with its active stages in display order: open stages by position, then the
 * won stage, then the lost one. {@code version} changes with every stage change and is sent back on
 * every stage write. {@code archivedStages} are archived open stages, by name, to unarchive.
 */
public record PipelineView(
    UUID id, String name, long version, List<StageView> stages, List<StageView> archivedStages) {
  public PipelineView {
    stages = List.copyOf(stages);
    archivedStages = List.copyOf(archivedStages);
  }
}
