package br.com.branlycrm;

import br.com.branlycrm.config.ControlDatabaseMigrator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class BranlyCrmApplication {

  public static void main(String[] args) {
    // ADR-016: migrations run in their own process with the migrator credential, never the API.
    if ("migrate".equals(System.getenv("APP_MODE"))) {
      ControlDatabaseMigrator.migrate();
      return;
    }
    // Provisioner process: no HTTP server, provisioner credential and secret writer only here.
    if ("provision".equals(System.getenv("APP_MODE"))) {
      new SpringApplicationBuilder(BranlyCrmApplication.class)
          .web(WebApplicationType.NONE)
          .properties("app.provisioning.enabled=true", "app.provisioning.scheduler.enabled=true")
          .run(args);
      return;
    }
    // Platform command (ADR-028): runs once with its own credential and exits; no HTTP server.
    if ("platform".equals(System.getenv("APP_MODE"))) {
      var context =
          new SpringApplicationBuilder(BranlyCrmApplication.class)
              .web(WebApplicationType.NONE)
              .properties("app.platform.enabled=true")
              .run(args);
      System.exit(SpringApplication.exit(context));
      return;
    }
    SpringApplication.run(BranlyCrmApplication.class, args);
  }
}
