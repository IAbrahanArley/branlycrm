package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.tenant.domain.ClinicSettings;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClinicSettingsTest {
  @ParameterizedTest
  @ValueSource(
      strings = {"America/Sao_Paulo", "America/Manaus", "America/Noronha", "Europe/Lisbon"})
  void acceptsIanaRegions(String zone) {
    assertThat(ClinicSettings.of(zone).timeZone()).isEqualTo(ZoneId.of(zone));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "UTC",
        "GMT",
        "Z",
        "+03:00",
        "UTC+3",
        "GMT-03:00",
        "Etc/GMT+3",
        "SystemV/AST4",
        "America/Atlantis",
        "america/sao_paulo",
        "America/Sao_Paulo; DROP TABLE x",
        "../America/Sao_Paulo"
      })
  void refusesOffsetsAliasesAndUnknownNames(String zone) {
    assertThatThrownBy(() -> ClinicSettings.of(zone)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesFixedOffsetZones() {
    assertThatThrownBy(() -> new ClinicSettings(ZoneOffset.ofHours(-3)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClinicSettings(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
