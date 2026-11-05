package br.com.branlycrm.support;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Disposable PostgreSQL 17 with pgvector, pinned by digest. Never the developer database. */
public final class TestPostgres {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "pgvector/pgvector@sha256:7d0a41464797aeb9862dfdc0bbf1526b82cfd324cdd8036df19e3545f4a9ccb8")
          .asCompatibleSubstituteFor("postgres");

  private TestPostgres() {}

  public static PostgreSQLContainer container() {
    return new PostgreSQLContainer(IMAGE);
  }
}
