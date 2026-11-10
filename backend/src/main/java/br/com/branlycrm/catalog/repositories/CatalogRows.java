package br.com.branlycrm.catalog.repositories;

import br.com.branlycrm.catalog.exceptions.CatalogException;
import br.com.branlycrm.common.exceptions.InvalidInputException;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

/** Helpers shared by the catalog repositories. */
final class CatalogRows {
  private CatalogRows() {}

  /** PostgreSQL keeps microseconds: what is returned must equal what is stored. */
  static Supplier<Instant> clock(ObjectProvider<Clock> clock) {
    Clock source = clock.getIfAvailable(Clock::systemUTC);
    return () -> source.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** A stored row that breaks a domain rule was written outside the application. */
  static <R, T> T stored(R row, Function<R, T> mapping) {
    try {
      return mapping.apply(row);
    } catch (InvalidInputException | IllegalArgumentException invalid) {
      throw new ClinicDataException(ClinicDataException.Reason.INVALID_DATA, invalid);
    }
  }

  /** The only unique constraint besides the key is the name; the version check means stale. */
  static RuntimeException conflict(
      ClinicDataException failure,
      CatalogException.Reason duplicate,
      CatalogException.Reason stale) {
    return switch (failure.reason()) {
      case CONFLICT -> new CatalogException(duplicate);
      case STALE -> new CatalogException(stale);
      default -> failure;
    };
  }
}
