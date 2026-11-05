package br.com.branlycrm;

import br.com.branlycrm.config.ControlDatabaseMigrator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BranlyCrmApplication {

  public static void main(String[] args) {
    // ADR-016: migrations run in their own process with the migrator credential, never the API.
    if ("migrate".equals(System.getenv("APP_MODE"))) {
      ControlDatabaseMigrator.migrate();
      return;
    }
    SpringApplication.run(BranlyCrmApplication.class, args);
  }
}
