package br.com.branlycrm.identity.mfa.config;

import br.com.branlycrm.identity.mfa.crypto.MfaCrypto;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Second-factor key and clock, only in the HTTP process: the provisioner, the migrator and the
 * platform command never hold the key. A missing or malformed key stops the startup.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class MfaConfiguration {
  @Bean
  MfaCrypto mfaCrypto(@Value("${app.mfa.encryption-key:}") String key) {
    return MfaCrypto.fromBase64(key);
  }

  /** Session and TOTP time; tests replace it with a controllable clock. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
