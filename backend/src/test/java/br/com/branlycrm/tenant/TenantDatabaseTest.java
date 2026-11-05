package br.com.branlycrm.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantDatabaseTest {
  @Test
  void acceptsOnlyNamesDerivedFromTheTenantUuid() {
    UUID id = UUID.randomUUID();
    String suffix = suffix(id);
    var database =
        new TenantDatabase(
            id,
            "clinic_" + suffix,
            "tenant_" + suffix,
            "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    assertThat(database.databaseName()).isEqualTo("clinic_" + suffix);
  }

  @Test
  void rejectsBindingToAnotherClinicsDatabaseRoleOrSecret() {
    UUID id = UUID.randomUUID();
    String own = suffix(id);
    String other = suffix(UUID.randomUUID());
    String secret = "TENANT_SECRET_" + own.toUpperCase(Locale.ROOT);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TenantDatabase(id, "clinic_" + other, "tenant_" + own, secret));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TenantDatabase(id, "clinic_" + own, "tenant_" + other, secret));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new TenantDatabase(
                    id,
                    "clinic_" + own,
                    "tenant_" + own,
                    "TENANT_SECRET_" + other.toUpperCase(Locale.ROOT)));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TenantDatabase(id, "postgres", "tenant_" + own, secret));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TenantDatabase(null, "clinic_" + own, "tenant_" + own, secret));
  }

  private static String suffix(UUID id) {
    return id.toString().replace("-", "");
  }
}
