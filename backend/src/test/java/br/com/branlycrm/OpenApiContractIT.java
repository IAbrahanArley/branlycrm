package br.com.branlycrm;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.branlycrm.support.Browser;
import br.com.branlycrm.support.MfaFixture;
import br.com.branlycrm.support.TestPostgres;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps docs/api/openapi.json, the contract the frontend is built against
 * (docs/agents/PARALELO.md), equal to what the API actually documents. A backend change that alters
 * the contract fails here until the snapshot is regenerated with {@code ./mvnw verify
 * -Dopenapi.write=true} (or the OPENAPI_WRITE=true environment variable) and committed with the
 * change.
 *
 * <p>The documentation requires a session, so the test logs in with a synthetic account. The {@code
 * servers} entry is dropped because it carries the random test port.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.docker.compose.enabled=false", MfaFixture.KEY_PROPERTY})
class OpenApiContractIT {
  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  static final Path SNAPSHOT =
      Path.of(System.getProperty("user.dir")).resolve("../docs/api/openapi.json").normalize();

  private static final JsonMapper CANONICAL =
      JsonMapper.builder()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .build();

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  @Test
  void committedContractMatchesTheDocumentedApi() throws Exception {
    String password = "Synthetic contract secret 2026!";
    jdbc.update(
        "INSERT INTO user_accounts (email, display_name, password_hash, email_verified)"
            + " VALUES ('contract@example.test', 'Synthetic', ?, true)"
            + " ON CONFLICT (email) DO NOTHING",
        encoder.encode(password));
    Browser browser = new Browser(port);
    browser.csrf();
    assertThat(browser.login("contract@example.test", password).statusCode()).isEqualTo(204);
    var docs = browser.get("/v3/api-docs");
    assertThat(docs.statusCode()).isEqualTo(200);

    String actual = canonical(docs.body());
    assertEverySuccessIsDocumented(docs.body());
    if (writeRequested()) {
      Files.createDirectories(SNAPSHOT.getParent());
      Files.writeString(SNAPSHOT, actual, StandardCharsets.UTF_8);
      return;
    }
    assertThat(SNAPSHOT)
        .as("docs/api/openapi.json is missing: run ./mvnw verify -Dopenapi.write=true")
        .exists();
    String committed = Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n");
    assertThat(committed)
        .as(
            "The API contract changed: run ./mvnw verify -Dopenapi.write=true and commit"
                + " docs/api/openapi.json with the change")
        .isEqualTo(actual);
  }

  /**
   * Declaring only error responses makes springdoc drop the default 200, and the frontend loses the
   * response schema. Every operation must document a success, and every 200 must have one.
   */
  @SuppressWarnings("unchecked")
  private static void assertEverySuccessIsDocumented(String json) {
    Map<String, Object> document = CANONICAL.readValue(json, Map.class);
    Map<String, Map<String, Map<String, Object>>> paths =
        (Map<String, Map<String, Map<String, Object>>>) document.get("paths");
    java.util.List<String> missing = new java.util.ArrayList<>();
    paths.forEach(
        (path, operations) ->
            operations.forEach(
                (method, operation) -> {
                  Map<String, Map<String, Object>> responses =
                      (Map<String, Map<String, Object>>) operation.get("responses");
                  boolean success =
                      responses.keySet().stream().anyMatch(code -> code.startsWith("2"));
                  Map<String, Object> ok = responses.get("200");
                  if (!success || (ok != null && ok.get("content") == null)) {
                    missing.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path);
                  }
                }));
    assertThat(missing).as("operations without a documented success response").isEmpty();
  }

  @SuppressWarnings("unchecked")
  private static String canonical(String json) {
    Map<String, Object> document = CANONICAL.readValue(json, Map.class);
    document.remove("servers");
    return CANONICAL.writeValueAsString(document).replace("\r\n", "\n") + "\n";
  }

  private static boolean writeRequested() {
    return Boolean.parseBoolean(System.getProperty("openapi.write"))
        || Boolean.parseBoolean(System.getenv("OPENAPI_WRITE"));
  }
}
