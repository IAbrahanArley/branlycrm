package br.com.branlycrm.tenant.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.hibernate.HibernateException;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;

class ClinicPersistenceTest {
  @Test
  void accessRefusalsKeepTheirTypeWhereverHibernateWrapsThem() {
    var denied = new TenantAccessException();
    assertThat(ClinicPersistence.translate(new PersistenceException("wrapped", denied)))
        .isSameAs(denied);
    var secret = new TenantSecretException(TenantSecretException.Reason.MISSING, "missing");
    assertThat(ClinicPersistence.translate(new HibernateException("wrapped", secret)))
        .isSameAs(secret);
  }

  @Test
  void databaseFailuresBecomeStableCodes() {
    assertThat(reason("42P01")).isEqualTo(ClinicDataException.Reason.SCHEMA_OUTDATED);
    assertThat(reason("42703")).isEqualTo(ClinicDataException.Reason.SCHEMA_OUTDATED);
    assertThat(reason(TenantConnectionRepository.IDENTITY_MISMATCH))
        .isEqualTo(ClinicDataException.Reason.IDENTITY_MISMATCH);
    assertThat(reason("55P03")).isEqualTo(ClinicDataException.Reason.UNAVAILABLE);
    assertThat(reason("23505")).isEqualTo(ClinicDataException.Reason.CONFLICT);
    assertThat(
            ((ClinicDataException)
                    ClinicPersistence.translate(
                        new jakarta.persistence.OptimisticLockException("stale")))
                .reason())
        .isEqualTo(ClinicDataException.Reason.STALE);
    assertThat(reason(null)).isEqualTo(ClinicDataException.Reason.UNAVAILABLE);
    var translated =
        ClinicPersistence.translate(
            new PersistenceException(new SQLException("relation \"x\" does not exist", "42P01")));
    assertThat(translated).hasMessage("CLINIC_DATA_SCHEMA_OUTDATED");
  }

  @Test
  void domainExceptionsFromTheCallbackPassUnchanged() {
    var rule = new IllegalArgumentException("domain rule");
    assertThat(ClinicPersistence.translate(rule)).isSameAs(rule);
  }

  @Test
  void providerHasNoConnectionWithoutATenant() {
    var provider = new ClinicConnectionProvider(null);
    assertThatThrownBy(provider::getAnyConnection).isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> provider.getConnection(null)).isInstanceOf(SQLException.class);
  }

  /** No resolver is registered: Hibernate refuses a session without tenant before connecting. */
  @Test
  void sessionWithoutTenantIsRefused() {
    StandardServiceRegistry registry =
        ClinicPersistence.registry(new ClinicConnectionProvider(null));
    try (SessionFactory factory =
        ClinicPersistence.metadata(registry, List.of(ClinicSettingsEntity.class))
            .buildSessionFactory()) {
      assertThatThrownBy(factory::openSession).isInstanceOf(HibernateException.class);
      assertThat(factory.getCache()).isNotNull();
      assertThat(factory.getSessionFactoryOptions().isSecondLevelCacheEnabled()).isFalse();
      assertThat(factory.getSessionFactoryOptions().isQueryCacheEnabled()).isFalse();
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  @Test
  void tenantKeepsTheSubjectOutOfItsText() {
    UUID subject = UUID.randomUUID();
    assertThat(new ClinicTenant(UUID.randomUUID(), subject).toString())
        .doesNotContain(subject.toString());
  }

  private static ClinicDataException.Reason reason(String sqlState) {
    RuntimeException translated =
        ClinicPersistence.translate(
            new HibernateException("wrapped", new SQLException("synthetic", sqlState)));
    return ((ClinicDataException) translated).reason();
  }
}
