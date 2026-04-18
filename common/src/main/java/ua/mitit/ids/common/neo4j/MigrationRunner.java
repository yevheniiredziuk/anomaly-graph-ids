package ua.mitit.ids.common.neo4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Minimal Flyway-like migration runner for Neo4j.
 *
 * <p>Conventions (inspired by Flyway):
 *
 * <ul>
 *   <li>Files are classpath resources named {@code V{number}__{name}.cypher}
 *   <li>Files sorted by {number} ascending, applied in order
 *   <li>Applied migrations tracked via a single {@code :SchemaMigration} node in Neo4j
 *   <li>Idempotent: if all migrations already applied, this is a no-op
 *   <li>Content is split on semicolons outside line comments; each statement runs in its own
 *       auto-commit transaction (required for schema DDL in Neo4j 5+)
 * </ul>
 *
 * <p>Non-goals: no rollback, no checksum verification, no repair mode. If these limitations bite in
 * the future — migrate to a proper tool.
 */
public class MigrationRunner {

  private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);
  private static final Pattern FILENAME_PATTERN = Pattern.compile("V(\\d+)__(.+)\\.cypher");

  private final Driver driver;
  private final String resourceLocation;

  public MigrationRunner(Driver driver, String resourceLocation) {
    this.driver = driver;
    this.resourceLocation = resourceLocation;
  }

  public void migrate() throws IOException {
    log.info("Starting Neo4j schema migration from {}", resourceLocation);

    List<Migration> migrations = discoverMigrations();
    log.info("Discovered {} migration files", migrations.size());

    try (Session session = driver.session()) {
      ensureMigrationTrackingNode(session);
      long lastApplied = getLastAppliedVersion(session);
      log.info("Last applied version: {}", lastApplied);

      for (Migration m : migrations) {
        if (m.version() <= lastApplied) {
          log.info("Skipping already-applied migration V{}", m.version());
          continue;
        }
        applyMigration(session, m);
      }
    }
    log.info("Migration complete.");
  }

  private List<Migration> discoverMigrations() throws IOException {
    PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver(getClass().getClassLoader());
    Resource[] resources = resolver.getResources(resourceLocation + "/V*.cypher");

    return Arrays.stream(resources)
        .map(this::parseMigration)
        .sorted(Comparator.comparingLong(Migration::version))
        .toList();
  }

  private Migration parseMigration(Resource resource) {
    String filename = resource.getFilename();
    if (filename == null) {
      throw new IllegalStateException("Resource without filename: " + resource);
    }
    Matcher m = FILENAME_PATTERN.matcher(filename);
    if (!m.matches()) {
      throw new IllegalStateException(
          "Invalid migration filename: " + filename + " (expected V{number}__{name}.cypher)");
    }
    long version = Long.parseLong(m.group(1));
    String name = m.group(2);
    try {
      String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return new Migration(version, name, content);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot read migration: " + filename, e);
    }
  }

  private void ensureMigrationTrackingNode(Session session) {
    session.executeWriteWithoutResult(
        tx ->
            tx.run(
                "MERGE (s:SchemaMigration {id: 'singleton'}) "
                    + "ON CREATE SET "
                    + "  s.last_applied_version = 0, "
                    + "  s.last_applied_at = datetime(), "
                    + "  s.total_applied = 0"));
  }

  private long getLastAppliedVersion(Session session) {
    return session.executeRead(
        tx -> {
          var result =
              tx.run(
                  "MATCH (s:SchemaMigration {id: 'singleton'}) "
                      + "RETURN s.last_applied_version AS version");
          if (!result.hasNext()) {
            return 0L;
          }
          return result.next().get("version").asLong(0L);
        });
  }

  private void applyMigration(Session session, Migration m) {
    log.info("Applying V{}: {}", m.version(), m.name());
    Instant start = Instant.now();
    List<String> statements = splitStatements(m.content());
    log.info("  {} statements in V{}", statements.size(), m.version());

    // Each schema DDL statement runs in its own auto-commit transaction.
    // session.run(String) is an auto-commit — required for CREATE INDEX/CONSTRAINT in Neo4j 5+.
    for (String stmt : statements) {
      String trimmed = stmt.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      session.run(trimmed).consume();
    }

    session.executeWriteWithoutResult(
        tx ->
            tx.run(
                "MATCH (s:SchemaMigration {id: 'singleton'}) "
                    + "SET s.last_applied_version = $v, "
                    + "    s.last_applied_at = datetime(), "
                    + "    s.total_applied = s.total_applied + 1",
                Map.of("v", m.version())));

    long elapsed = Duration.between(start, Instant.now()).toMillis();
    log.info("Applied V{} in {} ms", m.version(), elapsed);
  }

  /**
   * Splits Cypher script on semicolons, respecting single-line {@code //} comments.
   *
   * <p>Does NOT handle: semicolons inside string literals (not expected in DDL migrations), nested
   * block comments.
   */
  private List<String> splitStatements(String content) {
    StringBuilder current = new StringBuilder();
    List<String> result = new ArrayList<>();
    boolean inLineComment = false;

    for (int i = 0; i < content.length(); i++) {
      char c = content.charAt(i);
      if (inLineComment) {
        if (c == '\n') {
          inLineComment = false;
        }
        current.append(c);
        continue;
      }
      if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
        inLineComment = true;
        current.append(c);
        continue;
      }
      if (c == ';') {
        result.add(current.toString());
        current.setLength(0);
        continue;
      }
      current.append(c);
    }
    if (!current.toString().trim().isEmpty()) {
      result.add(current.toString());
    }
    return result;
  }

  private record Migration(long version, String name, String content) {}
}
