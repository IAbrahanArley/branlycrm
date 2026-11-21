package br.com.branlycrm.whatsapp.worker;

import br.com.branlycrm.whatsapp.repositories.ChannelRegistryRepository;
import br.com.branlycrm.whatsapp.secrets.ChannelSealer;
import br.com.branlycrm.whatsapp.secrets.ChannelUnsealer;
import br.com.branlycrm.whatsapp.services.InboundProcessingService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * WhatsApp worker loop (ADR-036, APP_MODE=whatsapp-worker): listens to {@code whatsapp_inbound} on
 * a dedicated connection outside the pool and sweeps the durable pending hint every 30 seconds.
 * Notifications are only hints (any control role can send one): both paths only request a drain.
 * Draining runs on a single thread, so events are applied one at a time (CRM-082b); requests that
 * arrive while it runs are coalesced into one more pass. The raw payload retention runs daily.
 */
@Component
@ConditionalOnProperty(name = "app.whatsapp.worker.enabled", havingValue = "true")
public class WhatsappWorker implements SmartLifecycle {
  static final String CHANNEL = "whatsapp_inbound";
  private static final Logger LOG = LoggerFactory.getLogger(WhatsappWorker.class);

  private final ChannelRegistryRepository channels;
  private final InboundProcessingService inbound;
  private final ChannelUnsealer unsealer;
  private final ChannelSealer sealer;
  private final String url;
  private final String username;
  private final String password;
  private final long sweepSeconds;
  private final AtomicLong wakeups = new AtomicLong();
  private final AtomicBoolean drainRequested = new AtomicBoolean();
  private volatile ScheduledExecutorService executor;
  private volatile ExecutorService processor;
  private volatile boolean running;

  public WhatsappWorker(
      ChannelRegistryRepository channels,
      InboundProcessingService inbound,
      ChannelUnsealer unsealer,
      ChannelSealer sealer,
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String username,
      @Value("${spring.datasource.password}") String password,
      @Value("${app.whatsapp.worker.sweep-seconds:30}") long sweepSeconds) {
    this.channels = channels;
    this.inbound = inbound;
    this.unsealer = unsealer;
    this.sealer = sealer;
    this.url = url;
    this.username = username;
    this.password = password;
    this.sweepSeconds = Math.max(5, sweepSeconds);
  }

  @Override
  public void start() {
    if (!unsealer.configured()) {
      LOG.warn("WhatsApp worker started without channel private keys: tokens cannot be opened");
    } else if (!unsealer.opens(sealer)) {
      // The API seals with the newest public key: without its private pair nothing opens.
      LOG.error("WhatsApp key pair mismatch: the newest public key has no matching private key");
    }
    running = true;
    executor =
        Executors.newScheduledThreadPool(
            2,
            task -> {
              Thread thread = new Thread(task, "whatsapp-worker");
              thread.setDaemon(true);
              return thread;
            });
    processor =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "whatsapp-inbound");
              thread.setDaemon(true);
              return thread;
            });
    executor.execute(this::listen);
    executor.scheduleWithFixedDelay(this::requestDrain, 0, sweepSeconds, TimeUnit.SECONDS);
    executor.scheduleWithFixedDelay(this::retention, 1, 24 * 60, TimeUnit.MINUTES);
    LOG.info("WhatsApp worker started");
  }

  @Override
  public void stop() {
    running = false;
    ScheduledExecutorService current = executor;
    if (current != null) current.shutdownNow();
    ExecutorService draining = processor;
    if (draining != null) draining.shutdownNow();
    LOG.info("WhatsApp worker stopped");
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** How many notifications or sweeps found work; for tests and metrics. */
  public long wakeups() {
    return wakeups.get();
  }

  /** Asks for one more drain; coalesced while one is already queued. */
  void requestDrain() {
    ExecutorService draining = processor;
    if (!running || draining == null) return;
    if (drainRequested.compareAndSet(false, true)) {
      try {
        draining.execute(this::sweep);
      } catch (RuntimeException rejected) {
        drainRequested.set(false);
      }
    }
  }

  /** Drains every channel with the durable signal, until none has a full batch left. */
  void sweep() {
    drainRequested.set(false);
    try {
      boolean more = true;
      while (running && more) {
        more = false;
        List<UUID> pending = channels.channelsWithPendingEvents();
        if (!pending.isEmpty()) wakeups.incrementAndGet();
        for (UUID channel : pending) {
          if (!running) return;
          try {
            more |= inbound.drain(channel).more();
          } catch (RuntimeException failure) {
            LOG.warn(
                "WhatsApp drain failed channel={} code={}",
                channel,
                failure.getClass().getSimpleName());
          }
        }
      }
    } catch (RuntimeException failure) {
      LOG.warn("WhatsApp sweep failed code={}", failure.getClass().getSimpleName());
    }
  }

  private void retention() {
    ExecutorService draining = processor;
    if (!running || draining == null) return;
    try {
      draining.execute(
          () -> {
            try {
              inbound.applyRetention();
            } catch (RuntimeException failure) {
              LOG.warn("WhatsApp retention failed code={}", failure.getClass().getSimpleName());
            }
          });
    } catch (RuntimeException rejected) {
      // Shutting down.
    }
  }

  private Properties listenProperties() {
    Properties properties = new Properties();
    properties.setProperty("user", username);
    properties.setProperty("password", password);
    properties.setProperty("connectTimeout", "5");
    properties.setProperty("tcpKeepAlive", "true");
    properties.setProperty("ApplicationName", "branly-whatsapp-listener");
    return properties;
  }

  /** A dedicated connection: LISTEN must not hold a pooled connection forever. */
  private void listen() {
    while (running) {
      try (Connection connection = DriverManager.getConnection(url, listenProperties());
          Statement statement = connection.createStatement()) {
        statement.execute("LISTEN " + CHANNEL);
        PGConnection notifications = connection.unwrap(PGConnection.class);
        while (running) {
          PGNotification[] received = notifications.getNotifications(5_000);
          if (received != null && received.length > 0) requestDrain();
        }
      } catch (SQLException | RuntimeException failure) {
        if (!running) return;
        LOG.warn(
            "WhatsApp listener reconnecting code={} sqlState={}",
            failure.getClass().getSimpleName(),
            failure instanceof SQLException sql ? sql.getSQLState() : null);
        try {
          TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }
}
