package br.com.branlycrm.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Clock the test moves by hand: TOTP steps and session limits become deterministic. Starts one
 * second into the current 30-second step.
 */
public final class MutableClock extends Clock {
  private final AtomicReference<Instant> now;

  public MutableClock() {
    long seconds = Instant.now().getEpochSecond();
    this.now = new AtomicReference<>(Instant.ofEpochSecond(seconds - seconds % 30 + 1));
  }

  public void advance(Duration duration) {
    now.updateAndGet(current -> current.plus(duration));
  }

  /** Moves to the next 30-second TOTP step. */
  public void nextStep() {
    advance(Duration.ofSeconds(30));
  }

  @Override
  public Instant instant() {
    return now.get();
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }
}
