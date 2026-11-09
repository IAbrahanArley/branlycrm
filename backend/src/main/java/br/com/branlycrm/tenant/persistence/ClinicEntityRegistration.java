package br.com.branlycrm.tenant.persistence;

import java.util.List;

/**
 * Explicit list of clinic entities contributed by one module. Each module keeps its entities in its
 * own {@code persistence} package and declares one bean of this type, so the tenant module never
 * depends on business modules and nothing is found by classpath scanning.
 */
public interface ClinicEntityRegistration {
  List<Class<?>> entities();
}
