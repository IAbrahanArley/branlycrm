package br.com.branlycrm.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** {@code @Import} it to drive the application clock from the test. */
@TestConfiguration(proxyBeanMethods = false)
public class MutableClockConfiguration {
  @Bean
  @Primary
  MutableClock mutableClock() {
    return new MutableClock();
  }
}
