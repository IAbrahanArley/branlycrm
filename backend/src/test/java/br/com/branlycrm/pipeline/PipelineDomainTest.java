package br.com.branlycrm.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.pipeline.domain.Pipeline;
import br.com.branlycrm.pipeline.domain.PipelineStage;
import br.com.branlycrm.pipeline.domain.StageKey;
import br.com.branlycrm.pipeline.domain.StageKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PipelineDomainTest {
  private static final UUID FUNNEL = UUID.randomUUID();

  @Test
  void stagesAreOrderedOpenByPositionThenWonThenLost() {
    List<PipelineStage> shuffled =
        List.of(
            stage("Perdido", StageKind.LOST, StageKey.LOST, null),
            stage("Compareceu", StageKind.OPEN, StageKey.ATTENDED, 3),
            stage("Venda fechada", StageKind.WON, StageKey.WON, null),
            stage("Novo", StageKind.OPEN, StageKey.NEW, 1),
            stage("Agendado", StageKind.OPEN, null, 2));

    Pipeline pipeline = new Pipeline(FUNNEL, "Funil", 0, shuffled);

    assertThat(pipeline.stages())
        .extracting(PipelineStage::name)
        .containsExactly("Novo", "Agendado", "Compareceu", "Venda fechada", "Perdido");
    assertThat(pipeline.entry().key()).isEqualTo(StageKey.NEW);
  }

  @Test
  void theEntryStageNeedNotBeFirst() {
    var pipeline =
        new Pipeline(
            FUNNEL,
            "Funil",
            0,
            List.of(
                stage("Triagem", StageKind.OPEN, null, 1),
                stage("Novo", StageKind.OPEN, StageKey.NEW, 2),
                stage("Venda fechada", StageKind.WON, StageKey.WON, null),
                stage("Perdido", StageKind.LOST, StageKey.LOST, null)));
    assertThat(pipeline.entry().name()).isEqualTo("Novo");
  }

  @Test
  void funnelRulesAreCheckedAgainWhenRead() {
    List<PipelineStage> valid = defaults();
    assertBroken(without(valid, StageKind.WON));
    assertBroken(without(valid, StageKind.LOST));
    assertBroken(with(valid, stage("Outra venda", StageKind.WON, null, null)));
    assertBroken(with(valid, stage("Outra perda", StageKind.LOST, null, null)));
    assertBroken(with(valid, stage("Lacuna", StageKind.OPEN, null, 6)));
    assertBroken(with(valid, stage("Repetida", StageKind.OPEN, null, 2)));
    assertBroken(
        valid.stream().filter(stage -> stage.key() != StageKey.NEW).toList(), "no entry stage");
  }

  @Test
  void stagesCarryConsistentKindKeyAndPosition() {
    assertThatThrownBy(() -> stage("Aberta sem posição", StageKind.OPEN, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> stage("Ganha com posição", StageKind.WON, StageKey.WON, 5))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> stage("Chave trocada", StageKind.OPEN, StageKey.WON, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> stage(" ", StageKind.OPEN, null, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<PipelineStage> defaults() {
    return List.of(
        stage("Novo", StageKind.OPEN, StageKey.NEW, 1),
        stage("Em atendimento", StageKind.OPEN, StageKey.IN_SERVICE, 2),
        stage("Agendado", StageKind.OPEN, StageKey.SCHEDULED, 3),
        stage("Compareceu", StageKind.OPEN, StageKey.ATTENDED, 4),
        stage("Venda fechada", StageKind.WON, StageKey.WON, null),
        stage("Perdido", StageKind.LOST, StageKey.LOST, null));
  }

  private static List<PipelineStage> without(List<PipelineStage> stages, StageKind kind) {
    return stages.stream().filter(stage -> stage.kind() != kind).toList();
  }

  private static List<PipelineStage> with(List<PipelineStage> stages, PipelineStage extra) {
    List<PipelineStage> copy = new ArrayList<>(stages);
    copy.add(extra);
    return copy;
  }

  private static void assertBroken(List<PipelineStage> stages) {
    assertBroken(stages, "broken funnel");
  }

  private static void assertBroken(List<PipelineStage> stages, String description) {
    assertThatThrownBy(() -> new Pipeline(FUNNEL, "Funil", 0, stages))
        .as(description)
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static PipelineStage stage(String name, StageKind kind, StageKey key, Integer position) {
    return new PipelineStage(UUID.randomUUID(), name, kind, key, position);
  }
}
