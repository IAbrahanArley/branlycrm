package br.com.branlycrm.pipeline.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The clinic's sales funnel with its active stages in display order: open stages by position, then
 * the won stage, then the lost one (CRM-01, ADR-033). The invariants the database keeps are checked
 * again here, so a funnel written outside them is refused instead of shown wrong.
 */
public record Pipeline(UUID id, String name, long version, List<PipelineStage> stages) {
  public Pipeline {
    if (id == null || name == null || name.isBlank() || stages == null) {
      throw new IllegalArgumentException("Incomplete pipeline");
    }
    stages = ordered(stages);
    List<PipelineStage> open = stages.stream().filter(s -> s.kind() == StageKind.OPEN).toList();
    for (int i = 0; i < open.size(); i++) {
      if (open.get(i).position() != i + 1) {
        throw new IllegalArgumentException("Open stage positions are not 1..n");
      }
    }
    if (count(stages, StageKind.WON) != 1 || count(stages, StageKind.LOST) != 1) {
      throw new IllegalArgumentException("A funnel has exactly one won and one lost stage");
    }
    if (stages.stream().filter(s -> s.key() == StageKey.NEW).count() != 1) {
      throw new IllegalArgumentException("A funnel has exactly one active entry stage");
    }
  }

  /** The stage every new lead starts in. */
  public PipelineStage entry() {
    return stages.stream().filter(s -> s.key() == StageKey.NEW).findFirst().orElseThrow();
  }

  private static List<PipelineStage> ordered(List<PipelineStage> stages) {
    List<PipelineStage> sorted = new ArrayList<>(stages);
    sorted.sort(
        Comparator.comparingInt((PipelineStage s) -> s.kind().ordinal())
            .thenComparing(s -> s.position() == null ? 0 : s.position()));
    return List.copyOf(sorted);
  }

  private static long count(List<PipelineStage> stages, StageKind kind) {
    return stages.stream().filter(s -> s.kind() == kind).count();
  }
}
