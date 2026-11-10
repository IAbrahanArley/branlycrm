package br.com.branlycrm.pipeline.domain;

import java.util.UUID;

/**
 * One active stage of the funnel. Open stages carry their position (1..n); the won and lost stages
 * have none and are always shown after them.
 */
public record PipelineStage(UUID id, String name, StageKind kind, StageKey key, Integer position) {
  public PipelineStage {
    if (id == null || name == null || name.isBlank() || kind == null) {
      throw new IllegalArgumentException("Incomplete stage");
    }
    if ((kind == StageKind.OPEN) != (position != null)) {
      throw new IllegalArgumentException("Only open stages have a position");
    }
    if (key != null && expectedKind(key) != kind) {
      throw new IllegalArgumentException("Stage key and kind disagree");
    }
  }

  private static StageKind expectedKind(StageKey key) {
    return switch (key) {
      case WON -> StageKind.WON;
      case LOST -> StageKind.LOST;
      case NEW, IN_SERVICE, SCHEDULED, ATTENDED -> StageKind.OPEN;
    };
  }
}
