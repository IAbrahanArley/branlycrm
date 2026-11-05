package br.com.branlycrm.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiApplication {

	public static void main(String[] args) {
		if ("migrate".equals(System.getenv("APP_MODE"))) {
            br.com.branlycrm.api.config.ControlDatabaseMigrator.migrate();
            return;
        }
        SpringApplication.run(ApiApplication.class, args);
	}

}
