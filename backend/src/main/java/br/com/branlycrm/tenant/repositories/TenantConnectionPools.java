package br.com.branlycrm.tenant.repositories;

import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.domain.TenantSecret;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Bounded, on-demand Hikari pools, one per clinic and secret version.
 *
 * <ul>
 *   <li>At most {@code maxPools} clinics hold a pool at once, each with at most {@code
 *       maxPerTenant} connections: the global ceiling is their product, never one unbounded pool
 *       per clinic.
 *   <li>A pool unused for {@code idleExpiry} is closed; when the ceiling is reached the least
 *       recently used idle pool is closed, and if every pool is busy the request fails fast.
 *   <li>A new secret version retires the old pool, so a rotated password is used from the next
 *       operation on and connections opened with the previous one are closed.
 *   <li>Only pools with no active or waiting borrowers are closed.
 * </ul>
 */
@Repository
public class TenantConnectionPools implements AutoCloseable {
  private final String host;
  private final int port;
  private final int maxPools;
  private final int maxPerTenant;
  private final long idleExpiryNanos;
  private final LongSupplier clock;
  private final Map<UUID, Pool> pools = new HashMap<>();
  private final List<Pool> retiring = new ArrayList<>();

  @Autowired
  public TenantConnectionPools(
      @Value("${app.tenancy.host:localhost}") String host,
      @Value("${app.tenancy.port:5432}") int port,
      @Value("${app.tenancy.pool.max-pools:20}") int maxPools,
      @Value("${app.tenancy.pool.max-per-tenant:3}") int maxPerTenant,
      @Value("${app.tenancy.pool.idle-expiry:PT10M}") Duration idleExpiry) {
    this(host, port, maxPools, maxPerTenant, idleExpiry, System::nanoTime);
  }

  public TenantConnectionPools(
      String host,
      int port,
      int maxPools,
      int maxPerTenant,
      Duration idleExpiry,
      LongSupplier clock) {
    if (!host.matches("[a-zA-Z0-9.-]+") || port < 1 || port > 65535) {
      throw new IllegalArgumentException("Invalid tenant database endpoint");
    }
    if (maxPools < 1 || maxPerTenant < 1 || idleExpiry.isNegative()) {
      throw new IllegalArgumentException("Invalid tenant pool limits");
    }
    this.host = host;
    this.port = port;
    this.maxPools = maxPools;
    this.maxPerTenant = maxPerTenant;
    this.idleExpiryNanos = idleExpiry.toNanos();
    this.clock = clock;
  }

  /** Borrows a connection; the caller closes it to return it to the clinic pool. */
  public Connection borrow(TenantDatabase database, TenantSecret secret) throws SQLException {
    if (!database.tenantId().equals(secret.tenantId())) {
      throw new IllegalArgumentException("Secret belongs to another clinic");
    }
    for (int attempt = 0; ; attempt++) {
      HikariDataSource source = source(database, secret);
      try {
        return source.getConnection();
      } catch (SQLException exception) {
        // Closed between lookup and borrow by expiry in another thread: look it up again.
        if (!source.isClosed() || attempt > 0) throw exception;
      }
    }
  }

  /** Pools currently holding resources, including rotated ones still lending connections. */
  public synchronized int openPools() {
    return pools.size() + retiring.size();
  }

  @Override
  public synchronized void close() {
    pools.values().forEach(pool -> pool.source.close());
    retiring.forEach(pool -> pool.source.close());
    pools.clear();
    retiring.clear();
  }

  private synchronized HikariDataSource source(TenantDatabase database, TenantSecret secret)
      throws SQLException {
    long now = clock.getAsLong();
    expireIdle(now);
    Pool pool = pools.get(database.tenantId());
    if (pool != null && pool.version == secret.version()) {
      pool.lastUsed = now;
      return pool.source;
    }
    if (pool != null) {
      // Rotated secret: closing a pool aborts borrowed connections, so a busy pool only stops
      // lending and is closed once its borrowers return.
      pools.remove(database.tenantId());
      if (pool.idle()) pool.source.close();
      else retiring.add(pool);
    }
    if (openPools() >= maxPools && !evictLeastRecentlyUsedIdle()) {
      throw new SQLTransientConnectionException("Tenant connection capacity exhausted");
    }
    Pool created = new Pool(secret.version(), create(database, secret), now);
    pools.put(database.tenantId(), created);
    return created.source;
  }

  private void expireIdle(long now) {
    retiring.removeIf(
        pool -> {
          if (!pool.idle()) return false;
          pool.source.close();
          return true;
        });
    for (Iterator<Pool> iterator = pools.values().iterator(); iterator.hasNext(); ) {
      Pool pool = iterator.next();
      if (now - pool.lastUsed >= idleExpiryNanos && pool.idle()) {
        pool.source.close();
        iterator.remove();
      }
    }
  }

  private boolean evictLeastRecentlyUsedIdle() {
    UUID victim = null;
    long oldest = Long.MAX_VALUE;
    for (var entry : pools.entrySet()) {
      if (entry.getValue().idle() && entry.getValue().lastUsed < oldest) {
        oldest = entry.getValue().lastUsed;
        victim = entry.getKey();
      }
    }
    if (victim == null) return false;
    pools.remove(victim).source.close();
    return true;
  }

  private HikariDataSource create(TenantDatabase database, TenantSecret secret) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database.databaseName());
    config.setUsername(database.databaseUser());
    config.setPassword(secret.password());
    config.setPoolName("tenant-" + database.tenantId());
    config.setMaximumPoolSize(maxPerTenant);
    config.setMinimumIdle(0);
    config.setIdleTimeout(Duration.ofMinutes(1).toMillis());
    config.setMaxLifetime(Duration.ofMinutes(30).toMillis());
    config.setConnectionTimeout(Duration.ofSeconds(5).toMillis());
    // Lazy: an unreachable clinic fails the borrowing operation, not the pool constructor.
    config.setInitializationFailTimeout(-1);
    config.addDataSourceProperty("connectTimeout", "5");
    config.addDataSourceProperty("logServerErrorDetail", "false");
    return new HikariDataSource(config);
  }

  private static final class Pool {
    final long version;
    final HikariDataSource source;
    long lastUsed;

    Pool(long version, HikariDataSource source, long lastUsed) {
      this.version = version;
      this.source = source;
      this.lastUsed = lastUsed;
    }

    boolean idle() {
      var bean = source.getHikariPoolMXBean();
      return bean == null
          || (bean.getActiveConnections() == 0 && bean.getThreadsAwaitingConnection() == 0);
    }
  }
}
