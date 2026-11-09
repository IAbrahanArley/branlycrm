package br.com.branlycrm.tenant.persistence;

import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.repositories.TenantConnectionRepository;
import br.com.branlycrm.tenant.secrets.TenantSecretException;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persistence unit of the clinic databases (CRM-015, ADR-030), separate from the central JPA.
 *
 * <ul>
 *   <li>The Hibernate {@link SessionFactory} is a private field, never a Spring bean: a bean of
 *       that type is an EntityManagerFactory and would switch the central JPA auto-configuration
 *       off.
 *   <li>Every session is opened for an explicit {@link ClinicTenant}; there is no current-tenant
 *       resolver, so a session without a tenant fails.
 *   <li>Connections come only from {@link TenantConnectionRepository#open}: membership, active
 *       clinic, secret and tenant_identity are checked for every transaction.
 *   <li>One transaction per call, never nested: a clinic pool holds few connections and nested
 *       calls under load would wait on themselves until the pool timeout. A nested call fails at
 *       once. Managed entities never leave the callback.
 *   <li>Clinic and central databases share no transaction; no use case may rely on both committing
 *       together.
 * </ul>
 */
@Component
public class ClinicPersistence implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ClinicPersistence.class);
  private static final String UNDEFINED_TABLE = "42P01";
  private static final String UNDEFINED_COLUMN = "42703";

  /**
   * Only a nesting flag per thread; it never carries a tenant, which is always passed explicitly.
   */
  private static final ThreadLocal<Boolean> IN_TRANSACTION = ThreadLocal.withInitial(() -> false);

  private final List<Class<?>> entities;
  private final StandardServiceRegistry registry;
  private final SessionFactory factory;

  public ClinicPersistence(
      TenantConnectionRepository connections, List<ClinicEntityRegistration> registrations) {
    Set<Class<?>> unique = new LinkedHashSet<>();
    registrations.forEach(registration -> unique.addAll(registration.entities()));
    this.entities = List.copyOf(unique);
    this.registry = registry(new ClinicConnectionProvider(connections));
    try {
      this.factory = metadata(registry, entities).buildSessionFactory();
    } catch (RuntimeException error) {
      StandardServiceRegistryBuilder.destroy(registry);
      throw error;
    }
  }

  /**
   * Runs {@code work} in one transaction of the clinic database authorized for {@code tenant}.
   * Commits when it returns, rolls back on any failure and always closes the session. The callback
   * returns domain records, never managed entities, and never calls this method again.
   *
   * @throws TenantAccessException the subject may not reach the clinic (not distinguished from a
   *     missing clinic)
   * @throws ClinicDataException the database failed, lacks a pending migration or holds invalid
   *     data
   * @throws IllegalStateException called from inside another clinic transaction
   */
  public <T> T inTransaction(ClinicTenant tenant, Function<Session, T> work) {
    Objects.requireNonNull(tenant, "tenant");
    Objects.requireNonNull(work, "work");
    if (IN_TRANSACTION.get()) {
      throw new IllegalStateException("Clinic transactions must not be nested");
    }
    IN_TRANSACTION.set(true);
    try {
      return run(tenant, work);
    } finally {
      IN_TRANSACTION.remove();
    }
  }

  private <T> T run(ClinicTenant tenant, Function<Session, T> work) {
    Session session = factory.withOptions().tenantIdentifier(tenant).openSession();
    Transaction transaction = null;
    Throwable failure = null;
    try {
      transaction = session.beginTransaction();
      T result = work.apply(session);
      transaction.commit();
      return result;
    } catch (RuntimeException error) {
      failure = error;
      rollback(transaction, error);
      RuntimeException translated = translate(error);
      if (translated instanceof ClinicDataException data) report(tenant, data);
      throw translated;
    } catch (Error error) {
      failure = error;
      rollback(transaction, error);
      throw error;
    } finally {
      close(session, failure);
    }
  }

  /** The registered clinic entities, for schema validation against a provisioned clinic. */
  public List<Class<?>> entities() {
    return entities;
  }

  /**
   * Hibernate settings shared by this unit and by schema validation. JDBC metadata is never read at
   * boot, because the only connections available belong to a tenant.
   */
  public static Map<String, Object> settings() {
    Map<String, Object> settings = new HashMap<>();
    settings.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    settings.put("hibernate.boot.allow_jdbc_metadata_access", false);
    settings.put("jakarta.persistence.database-product-name", "PostgreSQL");
    settings.put("jakarta.persistence.database-major-version", 17);
    settings.put("hibernate.hbm2ddl.auto", "none");
    settings.put("jakarta.persistence.schema-generation.database.action", "none");
    settings.put("jakarta.persistence.validation.mode", "none");
    settings.put("hibernate.cache.use_second_level_cache", false);
    settings.put("hibernate.cache.use_query_cache", false);
    settings.put("hibernate.generate_statistics", false);
    settings.put("hibernate.show_sql", false);
    settings.put(
        "hibernate.connection.handling_mode", "DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION");
    settings.put("hibernate.jdbc.time_zone", "UTC");
    settings.put("jakarta.persistence.query.timeout", 5000);
    return settings;
  }

  /** The service registry of this unit, with the tenant-only connection provider. */
  static StandardServiceRegistry registry(ClinicConnectionProvider provider) {
    Map<String, Object> settings = new HashMap<>(settings());
    settings.put("hibernate.multi_tenant_connection_provider", provider);
    return new StandardServiceRegistryBuilder().applySettings(settings).build();
  }

  static org.hibernate.boot.Metadata metadata(
      StandardServiceRegistry registry, List<Class<?>> entities) {
    MetadataSources sources = new MetadataSources(registry);
    entities.forEach(sources::addAnnotatedClass);
    return sources.buildMetadata();
  }

  @Override
  public void close() {
    try {
      factory.close();
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  private static void rollback(Transaction transaction, Throwable error) {
    if (transaction == null) return;
    try {
      if (transaction.isActive()) transaction.rollback();
    } catch (RuntimeException rollbackError) {
      error.addSuppressed(rollbackError);
    }
  }

  private static void close(Session session, Throwable failure) {
    try {
      session.close();
    } catch (RuntimeException closeError) {
      if (failure == null) throw translate(closeError);
      failure.addSuppressed(closeError);
    }
  }

  /** Stable code, SQLState and clinic only: no SQL text nor values reach the log. */
  private static void report(ClinicTenant tenant, ClinicDataException error) {
    String state = sqlState(error);
    if (error.reason() == ClinicDataException.Reason.IDENTITY_MISMATCH) {
      LOG.error("SECURITY clinic database identity mismatch tenant={}", tenant.tenantId());
    } else {
      LOG.warn(
          "Clinic data failure code={} sqlState={} tenant={}",
          error.getMessage(),
          state,
          tenant.tenantId());
    }
  }

  /**
   * Access refusals keep their type wherever Hibernate wrapped them; database failures become a
   * stable code; anything else (domain rules raised by the callback) passes unchanged.
   */
  static RuntimeException translate(RuntimeException error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof TenantAccessException denied) return denied;
      if (cause instanceof TenantSecretException secret) return secret;
    }
    if (!(error instanceof PersistenceException)) return error;
    String state = sqlState(error);
    if (TenantConnectionRepository.IDENTITY_MISMATCH.equals(state)) {
      return new ClinicDataException(ClinicDataException.Reason.IDENTITY_MISMATCH, error);
    }
    if (UNDEFINED_TABLE.equals(state) || UNDEFINED_COLUMN.equals(state)) {
      return new ClinicDataException(ClinicDataException.Reason.SCHEMA_OUTDATED, error);
    }
    return new ClinicDataException(ClinicDataException.Reason.UNAVAILABLE, error);
  }

  private static String sqlState(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
    }
    return null;
  }
}
