package br.com.branlycrm.contact;

import static br.com.branlycrm.support.TestPostgres.PASSWORD;
import static br.com.branlycrm.support.TestPostgres.PROVISIONER;
import static br.com.branlycrm.support.TestPostgres.WORKER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.branlycrm.common.domain.PageQuery;
import br.com.branlycrm.contact.domain.ConsentChannel;
import br.com.branlycrm.contact.domain.ConsentRecord;
import br.com.branlycrm.contact.domain.ConsentSource;
import br.com.branlycrm.contact.domain.ConsentState;
import br.com.branlycrm.contact.domain.ConsentStatus;
import br.com.branlycrm.contact.domain.Contact;
import br.com.branlycrm.contact.domain.ContactCreatedVia;
import br.com.branlycrm.contact.domain.ContactCreation;
import br.com.branlycrm.contact.domain.ContactFields;
import br.com.branlycrm.contact.domain.ContactSearch;
import br.com.branlycrm.contact.domain.ContactSummary;
import br.com.branlycrm.contact.domain.PhoneNumber;
import br.com.branlycrm.contact.domain.Tag;
import br.com.branlycrm.contact.domain.TagName;
import br.com.branlycrm.contact.exceptions.ContactException;
import br.com.branlycrm.contact.repositories.ConsentRepository;
import br.com.branlycrm.contact.repositories.ContactRepository;
import br.com.branlycrm.contact.repositories.TagRepository;
import br.com.branlycrm.provisioning.services.TenantProvisioningService;
import br.com.branlycrm.support.MutableClock;
import br.com.branlycrm.support.MutableClockConfiguration;
import br.com.branlycrm.support.TestPostgres;
import br.com.branlycrm.tenant.domain.ClinicAccess;
import br.com.branlycrm.tenant.domain.ClinicRole;
import br.com.branlycrm.tenant.domain.TenantDatabase;
import br.com.branlycrm.tenant.exceptions.ClinicDataException;
import br.com.branlycrm.tenant.exceptions.TenantAccessException;
import br.com.branlycrm.tenant.persistence.ClinicPersistence;
import br.com.branlycrm.tenant.secrets.FileTenantSecretReader;
import br.com.branlycrm.tenant.services.TenantAccessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Contacts, tags and consent (CRM-020, ADR-032) in clinic databases created by the real
 * provisioner, accessed as the clinic runtime role. All personal data is synthetic.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.docker.compose.enabled=false",
      "app.provisioning.enabled=true",
      "app.provisioning.scheduler.enabled=false",
      "app.provisioning.backoff=PT0S"
    })
@Import(MutableClockConfiguration.class)
class ContactPersistenceIT {
  @Container static final PostgreSQLContainer POSTGRES = TestPostgres.container();

  private static final Path SECRETS = temporaryDirectory();
  private static final PageQuery FIRST_PAGE = new PageQuery(1, 100);

  @Autowired TenantProvisioningService provisioning;
  @Autowired TenantAccessService access;
  @Autowired ContactRepository contacts;
  @Autowired TagRepository tags;
  @Autowired ConsentRepository consents;
  @Autowired ClinicPersistence persistence;
  @Autowired MutableClock clock;

  JdbcTemplate superuser;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String control = POSTGRES.getDatabaseName();
    registry.add("spring.datasource.url", () -> TestPostgres.url(POSTGRES, control));
    registry.add("spring.datasource.username", () -> WORKER);
    registry.add("spring.datasource.password", () -> PASSWORD);
    registry.add("spring.flyway.url", () -> TestPostgres.url(POSTGRES, control));
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
    registry.add("app.tenancy.host", POSTGRES::getHost);
    registry.add("app.tenancy.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("app.tenancy.secrets.directory", SECRETS::toString);
    registry.add("app.provisioning.admin.username", () -> PROVISIONER);
    registry.add("app.provisioning.admin.password", () -> PASSWORD);
    registry.add("app.provisioning.control-database", () -> control);
    // Room for the concurrency test: every creation holds its own connection.
    registry.add("app.tenancy.pool.max-per-tenant", () -> 10);
  }

  @BeforeAll
  static void deploymentRoles() throws SQLException {
    TestPostgres.bootstrapRoles(POSTGRES);
  }

  @AfterAll
  static void removeTheSecretDirectoryThisClassCreated() throws IOException {
    try (var files = Files.list(SECRETS)) {
      for (Path file : files.toList()) Files.delete(file);
    }
    Files.delete(SECRETS);
  }

  @Autowired
  void superuser() {
    superuser =
        new JdbcTemplate(
            new DriverManagerDataSource(
                TestPostgres.url(POSTGRES, POSTGRES.getDatabaseName()),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()));
  }

  @Test
  void sameNumberInAnyFormatIsOneContactPerClinic() throws Exception {
    Clinic a = provision();
    Clinic b = provision();

    ContactCreation first =
        contacts.create(
            a.access(), fields("João Sintético", "(11) 98765-4321"), ContactCreatedVia.MANUAL);
    ContactCreation again =
        contacts.create(
            a.access(), fields("Outro Nome", "+55 11 8765-4321"), ContactCreatedVia.WHATSAPP);
    ContactCreation inB =
        contacts.create(
            b.access(), fields("João Sintético", "5511987654321"), ContactCreatedVia.MANUAL);

    assertThat(first).isInstanceOf(ContactCreation.Created.class);
    assertThat(again).isEqualTo(new ContactCreation.Existing(first.id()));
    assertThat(inB).isInstanceOf(ContactCreation.Created.class);
    assertThat(inB.id()).isNotEqualTo(first.id());
    assertThat(owner(a, "SELECT phone_e164 || ' ' || name || ' ' || name_search FROM contacts"))
        .containsExactly("+5511987654321 João Sintético joao sintetico");
    assertThat(owner(b, "SELECT count(*)::text FROM contacts")).containsExactly("1");
  }

  @Test
  void concurrentCreationsOfOneNumberResolveToOneContact() throws Exception {
    Clinic a = provision();
    int writers = 8;
    CyclicBarrier start = new CyclicBarrier(writers);
    List<Future<ContactCreation>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(writers)) {
      for (int i = 0; i < writers; i++) {
        String name = "Concorrente " + i;
        Callable<ContactCreation> create =
            () -> {
              start.await(10, TimeUnit.SECONDS);
              return contacts.create(
                  a.access(), fields(name, "(21) 99999-0001"), ContactCreatedVia.WHATSAPP);
            };
        results.add(pool.submit(create));
      }
      List<ContactCreation> outcomes = new ArrayList<>();
      for (var result : results) outcomes.add(result.get(30, TimeUnit.SECONDS));

      assertThat(outcomes).filteredOn(ContactCreation.Created.class::isInstance).hasSize(1);
      assertThat(outcomes.stream().map(ContactCreation::id).distinct()).hasSize(1);
    }
    assertThat(owner(a, "SELECT count(*)::text FROM contacts")).containsExactly("1");
  }

  @Test
  void archivedContactKeepsItsNumberAndLeavesTheDefaultList() throws Exception {
    Clinic a = provision();
    UUID id = create(a, "Arquivada Sintética", "(31) 98888-7777");

    Contact archived = contacts.archive(a.access(), id);
    Contact twice = contacts.archive(a.access(), id);

    assertThat(archived.archived()).isTrue();
    assertThat(twice.archivedAt()).isEqualTo(archived.archivedAt());
    assertThat(contacts.create(a.access(), fields("Nova", "31988887777"), ContactCreatedVia.MANUAL))
        .isEqualTo(new ContactCreation.Existing(id));
    assertThat(contacts.search(a.access(), ContactSearch.all(), FIRST_PAGE).total()).isZero();
    assertThat(contacts.search(a.access(), new ContactSearch(null, null, true), FIRST_PAGE).items())
        .extracting(ContactSummary::id)
        .containsExactly(id);
  }

  @Test
  void editionChecksVersionAndNumberOwnership() throws Exception {
    Clinic a = provision();
    UUID ana = create(a, "Ana Sintética", "(41) 97777-1111");
    UUID bia = create(a, "Bia Sintética", "(41) 97777-2222");
    Contact read = contacts.find(a.access(), ana).orElseThrow();

    Contact edited =
        contacts.update(
            a.access(),
            ana,
            read.version(),
            new ContactFields(
                "Ána Édite",
                PhoneNumber.parse("41977771111"),
                "ana@example.test",
                LocalDate.of(1990, 1, 2),
                "Prefere contato à tarde."));

    assertThat(edited.version()).isEqualTo(read.version() + 1);
    assertThat(owner(a, "SELECT name_search FROM contacts WHERE id = '" + ana + "'"))
        .containsExactly("ana edite");
    expectReason(
        () -> contacts.update(a.access(), ana, read.version(), fieldsOf(edited)),
        ContactException.Reason.STALE);
    expectReason(
        () ->
            contacts.update(
                a.access(),
                ana,
                edited.version(),
                new ContactFields("Ana", PhoneNumber.parse("41977772222"), null, null, null)),
        ContactException.Reason.DUPLICATE_PHONE);
    assertThatThrownBy(
            () ->
                contacts.update(
                    a.access(),
                    ana,
                    edited.version(),
                    new ContactFields("Ana", PhoneNumber.parse("4197777-2222"), null, null, null)))
        .extracting(error -> ((ContactException) error).existingContactId())
        .isEqualTo(bia);
    assertThat(contacts.find(a.access(), bia).orElseThrow().fields().name())
        .isEqualTo("Bia Sintética");
  }

  @Test
  void searchMatchesNamePhoneAndEmailWithLiteralWildcards() throws Exception {
    Clinic a = provision();
    UUID joao = create(a, "João da Silva", "(51) 98111-2233");
    UUID promo = create(a, "Cliente 50% off_x", "(51) 98111-4455");
    contacts.update(
        a.access(),
        joao,
        0,
        new ContactFields(
            "João da Silva", PhoneNumber.parse("51981112233"), "Joao.S@example.test", null, null));

    assertThat(ids(a, "JOAO")).containsExactly(joao);
    assertThat(ids(a, "112233")).containsExactly(joao);
    assertThat(ids(a, "(51) 98111")).containsExactlyInAnyOrder(joao, promo);
    assertThat(ids(a, "joao.s@")).containsExactly(joao);
    assertThat(ids(a, "051 8111-2233")).as("old format, whole number").containsExactly(joao);
    assertThat(ids(a, "50%")).containsExactly(promo);
    assertThat(ids(a, "f_x")).containsExactly(promo);
    assertThat(ids(a, "%%")).isEmpty();
    assertThat(ids(a, "Prefere")).as("notes are never searched").isEmpty();
  }

  @Test
  void tagsAreUniquePerClinicAndFilterTheList() throws Exception {
    Clinic a = provision();
    Clinic b = provision();
    UUID tagged = create(a, "Com Tag", "(61) 98000-0001");
    UUID plain = create(a, "Sem Tag", "(61) 98000-0002");
    Tag vip = tags.create(a.access(), new TagName("VIP"));
    tags.create(b.access(), new TagName("VIP"));

    expectReason(
        () -> tags.create(a.access(), new TagName(" vip ")), ContactException.Reason.DUPLICATE_TAG);
    tags.tag(a.access(), tagged, vip.id());
    tags.tag(a.access(), tagged, vip.id());

    assertThat(tags.tagsOf(a.access(), tagged)).containsExactly(vip);
    assertThat(tags.tagsOf(a.access(), plain)).isEmpty();
    assertThat(
            contacts
                .search(a.access(), new ContactSearch(null, vip.id(), false), FIRST_PAGE)
                .items())
        .extracting(ContactSummary::id)
        .containsExactly(tagged);

    tags.delete(a.access(), vip.id());
    assertThat(tags.tagsOf(a.access(), tagged)).isEmpty();
    assertThat(owner(a, "SELECT count(*)::text FROM contact_tags")).containsExactly("0");
    expectReason(() -> tags.delete(a.access(), vip.id()), ContactException.Reason.NOT_FOUND);
  }

  @Test
  void consentFollowsTheMostRecentRecordAndImportCannotUndoAnOptOut() throws Exception {
    Clinic a = provision();
    UUID id = create(a, "Consentimento Sintético", "(71) 98222-3333");
    assertThat(consents.current(a.access(), id))
        .containsEntry(ConsentChannel.WHATSAPP, ConsentState.NONE);

    consents.record(
        a.access(), id, ConsentChannel.WHATSAPP, ConsentStatus.OPT_IN, ConsentSource.MANUAL);
    consents.record(
        a.access(), id, ConsentChannel.WHATSAPP, ConsentStatus.OPT_OUT, ConsentSource.WHATSAPP);
    assertThat(consents.current(a.access(), id))
        .containsEntry(ConsentChannel.WHATSAPP, ConsentState.OPT_OUT)
        .containsEntry(ConsentChannel.EMAIL, ConsentState.NONE);

    expectReason(
        () ->
            consents.record(
                a.access(),
                id,
                ConsentChannel.WHATSAPP,
                ConsentStatus.OPT_IN,
                ConsentSource.IMPORT),
        ContactException.Reason.CONSENT_REFUSED);
    consents.record(
        a.access(), id, ConsentChannel.WHATSAPP, ConsentStatus.OPT_IN, ConsentSource.MANUAL);
    assertThat(consents.current(a.access(), id))
        .containsEntry(ConsentChannel.WHATSAPP, ConsentState.OPT_IN);
    assertThat(consents.history(a.access(), id)).hasSize(3);
    assertThat(consents.history(a.access(), id).getFirst().recordedBy())
        .isEqualTo(a.access().subjectId());
  }

  @Test
  void optOutWinsARecordAtTheSameInstant() throws Exception {
    Clinic a = provision();
    UUID id = create(a, "Empate Sintético", "(81) 98333-4444");
    try (Connection owner = TestPostgres.admin(POSTGRES, a.database().databaseName())) {
      owner
          .createStatement()
          .execute(
              "INSERT INTO contact_consents"
                  + " (id, contact_id, channel, status, source, recorded_at, recorded_by)"
                  + " SELECT gen_random_uuid(), '"
                  + id
                  + "', 'EMAIL', s, 'MANUAL', TIMESTAMPTZ '2026-11-09 12:00:00Z', gen_random_uuid()"
                  + " FROM unnest(ARRAY['OPT_IN', 'OPT_OUT', 'OPT_IN']) AS s");
    }

    assertThat(consents.current(a.access(), id))
        .containsEntry(ConsentChannel.EMAIL, ConsentState.OPT_OUT);
  }

  /** The record order follows the writes, even when the process clock stands still or goes back. */
  @Test
  void consentOrderFollowsTheWritesNotTheClock() throws Exception {
    Clinic a = provision();
    UUID id = create(a, "Relógio Sintético", "(85) 98555-6666");

    consents.record(
        a.access(), id, ConsentChannel.WHATSAPP, ConsentStatus.OPT_IN, ConsentSource.MANUAL);
    consents.record(
        a.access(), id, ConsentChannel.WHATSAPP, ConsentStatus.OPT_OUT, ConsentSource.MANUAL);
    consents.record(
        a.access(), id, ConsentChannel.WHATSAPP, ConsentStatus.OPT_IN, ConsentSource.MANUAL);
    assertThat(consents.current(a.access(), id))
        .as("same instant: the later manual opt-in prevails")
        .containsEntry(ConsentChannel.WHATSAPP, ConsentState.OPT_IN);

    clock.advance(Duration.ofMinutes(-10));
    consents.record(
        a.access(), id, ConsentChannel.WHATSAPP, ConsentStatus.OPT_OUT, ConsentSource.WHATSAPP);
    clock.advance(Duration.ofMinutes(10));
    assertThat(consents.current(a.access(), id))
        .as("clock moved back: the later opt-out still prevails")
        .containsEntry(ConsentChannel.WHATSAPP, ConsentState.OPT_OUT);
  }

  /** Concurrent imports never slip an opt-in past an opt-out recorded by the team. */
  @Test
  void concurrentConsentChangesAreCheckedOneAfterTheOther() throws Exception {
    Clinic a = provision();
    UUID id = create(a, "Concorrência Sintética", "(85) 98666-7777");
    int writers = 6;
    CyclicBarrier start = new CyclicBarrier(writers);
    List<Future<?>> results = new ArrayList<>();
    try (var pool = Executors.newFixedThreadPool(writers)) {
      for (int i = 0; i < writers; i++) {
        boolean optOut = i % 2 == 0;
        results.add(
            pool.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  try {
                    consents.record(
                        a.access(),
                        id,
                        ConsentChannel.EMAIL,
                        optOut ? ConsentStatus.OPT_OUT : ConsentStatus.OPT_IN,
                        optOut ? ConsentSource.MANUAL : ConsentSource.IMPORT);
                  } catch (ContactException refused) {
                    assertThat(refused.reason()).isEqualTo(ContactException.Reason.CONSENT_REFUSED);
                  }
                  return null;
                }));
      }
      for (var result : results) result.get(30, TimeUnit.SECONDS);
    }

    List<ConsentRecord> written =
        consents.history(a.access(), id).stream()
            .sorted(Comparator.comparing(ConsentRecord::recordedAt))
            .toList();
    assertThat(written.stream().map(ConsentRecord::recordedAt).distinct()).hasSize(written.size());
    boolean optedOut = false;
    for (ConsentRecord record : written) {
      if (record.status() == ConsentStatus.OPT_OUT) optedOut = true;
      else assertThat(optedOut).as("import opt-in after an opt-out").isFalse();
    }
  }

  @Test
  void rowWrittenOutsideTheRulesIsInvalidDataNotInvalidInput() throws Exception {
    Clinic a = provision();
    UUID id = create(a, "Linha Sintética", "(85) 98777-8888");
    try (Connection owner = TestPostgres.admin(POSTGRES, a.database().databaseName())) {
      // A right-to-left override passes the database check but not the domain rules.
      owner
          .createStatement()
          .execute("UPDATE contacts SET name = 'Linha ' || chr(8238) || 'Sintética'");
    }
    assertThat(owner(a, "SELECT position(chr(8238) in name)::text FROM contacts"))
        .containsExactly("7");

    for (ThrowingCallable call :
        List.<ThrowingCallable>of(
            () -> contacts.find(a.access(), id),
            () -> contacts.search(a.access(), ContactSearch.all(), FIRST_PAGE))) {
      assertThatThrownBy(call)
          .isInstanceOf(ClinicDataException.class)
          .hasMessage("CLINIC_DATA_INVALID_DATA");
    }
  }

  /** Clinic A's access never reads, changes, counts nor links clinic B's data. */
  @Test
  void anotherClinicsDataIsInvisibleAndUntouchable() throws Exception {
    Clinic a = provision();
    Clinic b = provision();
    UUID mine = create(a, "Paciente A", "(11) 97000-0001");
    UUID theirs = create(b, "Paciente B", "(11) 97000-0002");
    Tag tagA = tags.create(a.access(), new TagName("Retorno"));
    Tag tagB = tags.create(b.access(), new TagName("Retorno B"));
    tags.tag(b.access(), theirs, tagB.id());
    consents.record(
        b.access(), theirs, ConsentChannel.EMAIL, ConsentStatus.OPT_OUT, ConsentSource.MANUAL);

    assertThat(contacts.find(a.access(), theirs)).isEmpty();
    assertThat(contacts.search(a.access(), new ContactSearch(null, null, true), FIRST_PAGE).items())
        .extracting(ContactSummary::id)
        .containsExactly(mine);
    assertThat(ids(a, "Paciente")).containsExactly(mine);
    assertThat(ids(a, "970000002")).isEmpty();
    assertThat(
            contacts
                .search(a.access(), new ContactSearch(null, tagB.id(), true), FIRST_PAGE)
                .total())
        .isZero();
    assertThat(tags.list(a.access())).containsExactly(tagA);
    for (ThrowingCallable call :
        List.<ThrowingCallable>of(
            () -> contacts.update(a.access(), theirs, 0, fields("Invasor", "(11) 97000-0003")),
            () -> contacts.archive(a.access(), theirs),
            () -> tags.tag(a.access(), theirs, tagA.id()),
            () -> tags.tag(a.access(), mine, tagB.id()),
            () -> tags.untag(a.access(), theirs, tagB.id()),
            () -> tags.tagsOf(a.access(), theirs),
            () -> tags.rename(a.access(), tagB.id(), new TagName("Invasor")),
            () -> tags.delete(a.access(), tagB.id()),
            () -> consents.current(a.access(), theirs),
            () -> consents.history(a.access(), theirs),
            () ->
                consents.record(
                    a.access(),
                    theirs,
                    ConsentChannel.EMAIL,
                    ConsentStatus.OPT_IN,
                    ConsentSource.MANUAL))) {
      expectReason(call, ContactException.Reason.NOT_FOUND);
    }

    // A forged access pairing A's subject with B's clinic is refused by the registry.
    var forged = new ClinicAccess(b.database(), a.access().subjectId(), ClinicRole.OWNER);
    assertThatThrownBy(() -> contacts.find(forged, theirs))
        .isInstanceOf(TenantAccessException.class);

    assertThat(owner(b, "SELECT name || ' ' || coalesce(archived_at::text, '-') FROM contacts"))
        .containsExactly("Paciente B -");
    assertThat(owner(b, "SELECT count(*)::text FROM contact_tags")).containsExactly("1");
    assertThat(owner(b, "SELECT count(*)::text FROM contact_consents")).containsExactly("1");
    assertThat(owner(b, "SELECT name FROM tags")).containsExactly("Retorno B");
  }

  @Test
  void runtimeCannotDeleteHistoryNorRewriteIdentityColumns() throws Exception {
    Clinic a = provision();
    UUID id = create(a, "Grants Sintético", "(91) 98444-5555");
    consents.record(
        a.access(), id, ConsentChannel.WHATSAPP, ConsentStatus.OPT_IN, ConsentSource.MANUAL);
    try (Connection runtime = runtime(a.database())) {
      for (String forbidden :
          List.of(
              "DELETE FROM contacts",
              "TRUNCATE contacts",
              "UPDATE contacts SET id = gen_random_uuid()",
              "UPDATE contacts SET created_at = now()",
              "UPDATE contacts SET created_by = NULL",
              "UPDATE contacts SET created_via = 'IMPORT'",
              "DELETE FROM contact_consents",
              "UPDATE contact_consents SET status = 'OPT_OUT'",
              "TRUNCATE contact_consents",
              "UPDATE tags SET created_by = gen_random_uuid()",
              "UPDATE contact_tags SET created_at = now()",
              "CREATE INDEX forbidden ON contacts (notes)")) {
        assertThatThrownBy(() -> runtime.createStatement().execute(forbidden))
            .as(forbidden)
            .isInstanceOf(SQLException.class)
            .extracting(error -> ((SQLException) error).getSQLState())
            .isEqualTo("42501");
      }
    }
  }

  @Test
  void databaseRefusesNonCanonicalPhonesEvenFromTheOwner() throws Exception {
    Clinic a = provision();
    create(a, "Autor Sintético", "(11) 90000-0002");
    try (Connection owner = TestPostgres.admin(POSTGRES, a.database().databaseName())) {
      for (String phone : List.of("+551187654321", "11987654321", "+55011987654321")) {
        assertThatThrownBy(
                () ->
                    owner
                        .createStatement()
                        .execute(
                            "INSERT INTO contacts (id, name, name_search, phone_e164, created_via,"
                                + " created_at, updated_at) VALUES (gen_random_uuid(), 'X', 'x', '"
                                + phone
                                + "', 'MANUAL', now(), now())"))
            .as(phone)
            .extracting(error -> ((SQLException) error).getSQLState())
            .isEqualTo("23514");
      }
      for (String anonymous :
          List.of(
              "INSERT INTO contacts (id, name, name_search, phone_e164, created_via, created_at,"
                  + " updated_at) VALUES (gen_random_uuid(), 'X', 'x', '+5511900000001', 'MANUAL',"
                  + " now(), now())",
              "INSERT INTO contact_consents (id, contact_id, channel, status, source, recorded_at)"
                  + " SELECT gen_random_uuid(), id, 'EMAIL', 'OPT_IN', 'IMPORT', now()"
                  + " FROM contacts LIMIT 1")) {
        assertThatThrownBy(() -> owner.createStatement().execute(anonymous))
            .as("only the WhatsApp worker writes without an author")
            .extracting(error -> ((SQLException) error).getSQLState())
            .isEqualTo("23514");
      }
      assertThat(owner(a, "SELECT extname FROM pg_extension WHERE extname = 'pg_trgm'"))
          .containsExactly("pg_trgm");
    }
  }

  /** Hibernate validates every clinic mapping against a provisioned database, as the runtime. */
  @Test
  void clinicMappingsMatchTheProvisionedSchema() throws Exception {
    Clinic a = provision();
    Map<String, Object> validation = new HashMap<>(ClinicPersistence.settings());
    validation.put("hibernate.boot.allow_jdbc_metadata_access", true);
    validation.put("hibernate.hbm2ddl.auto", "validate");
    validation.put(
        "hibernate.connection.url", TestPostgres.url(POSTGRES, a.database().databaseName()));
    validation.put("hibernate.connection.username", a.database().databaseUser());
    validation.put("hibernate.connection.password", password(a.database()));
    StandardServiceRegistry registry =
        new StandardServiceRegistryBuilder().applySettings(validation).build();
    try {
      MetadataSources sources = new MetadataSources(registry);
      persistence.entities().forEach(sources::addAnnotatedClass);
      sources.buildMetadata().buildSessionFactory().close();
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
    assertThat(persistence.entities()).hasSizeGreaterThanOrEqualTo(5);
  }

  private record Clinic(TenantDatabase database, ClinicAccess access) {}

  private static ContactFields fields(String name, String phone) {
    return new ContactFields(name, PhoneNumber.parse(phone), null, null, null);
  }

  private static ContactFields fieldsOf(Contact contact) {
    return contact.fields();
  }

  private UUID create(Clinic clinic, String name, String phone) {
    ContactCreation created =
        contacts.create(clinic.access(), fields(name, phone), ContactCreatedVia.MANUAL);
    assertThat(created).isInstanceOf(ContactCreation.Created.class);
    return created.id();
  }

  private List<UUID> ids(Clinic clinic, String term) {
    return contacts
        .search(clinic.access(), new ContactSearch(term, null, false), FIRST_PAGE)
        .items()
        .stream()
        .map(ContactSummary::id)
        .toList();
  }

  private static void expectReason(ThrowingCallable call, ContactException.Reason reason) {
    assertThatThrownBy(call)
        .isInstanceOf(ContactException.class)
        .extracting(error -> ((ContactException) error).reason())
        .isEqualTo(reason);
  }

  private Clinic provision() {
    UUID owner = UUID.randomUUID();
    superuser.update(
        "INSERT INTO user_accounts (subject_id, email, display_name, password_hash, email_verified)"
            + " VALUES (?, ?, 'Synthetic', '{bcrypt}synthetic', true)",
        owner,
        owner + "@example.test");
    UUID tenant = UUID.randomUUID();
    String suffix = tenant.toString().replace("-", "");
    var database =
        new TenantDatabase(
            tenant,
            "clinic_" + suffix,
            "tenant_" + suffix,
            "TENANT_SECRET_" + suffix.toUpperCase(Locale.ROOT));
    superuser.update(
        "INSERT INTO tenants (tenant_id, clinic_name, database_name, database_user,"
            + " credential_reference) VALUES (?, 'Clínica sintética', ?, ?, ?)",
        tenant,
        database.databaseName(),
        database.databaseUser(),
        database.credentialReference());
    superuser.update(
        "INSERT INTO tenant_provisioning_tasks (tenant_id, admin_subject_id, max_attempts)"
            + " VALUES (?, ?, 5)",
        tenant,
        owner);
    assertThat(provisioning.runPending()).isEqualTo(1);
    return new Clinic(database, access.authorize(tenant, owner));
  }

  private static List<String> owner(Clinic clinic, String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection owner = TestPostgres.admin(POSTGRES, clinic.database().databaseName());
        var rows = owner.createStatement().executeQuery(sql)) {
      while (rows.next()) values.add(rows.getString(1));
    }
    return values;
  }

  private Connection runtime(TenantDatabase database) throws SQLException {
    return DriverManager.getConnection(
        TestPostgres.url(POSTGRES, database.databaseName()),
        database.databaseUser(),
        password(database));
  }

  private static String password(TenantDatabase database) {
    return new FileTenantSecretReader(SECRETS.toString()).read(database).orElseThrow().password();
  }

  private static Path temporaryDirectory() {
    try {
      return Files.createTempDirectory("branly-contact-persistence-");
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
