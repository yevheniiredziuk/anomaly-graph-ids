// ============================================================================
// V001: Initial schema for temporal network traffic graph.
// Applies formal model from Section 3 of the article.
// ============================================================================
//
// NOTE: each statement must be on its own — Cypher in Neo4j 5+ does not allow
// multiple DDL statements in a single query. Our migration runner executes
// them one-by-one.

// ----- Constraints -----

CREATE CONSTRAINT host_ip_unique IF NOT EXISTS
    FOR (h:Host) REQUIRE h.ip IS UNIQUE;

CREATE CONSTRAINT service_composite_unique IF NOT EXISTS
    FOR (s:Service) REQUIRE (s.host_ip, s.port, s.protocol) IS UNIQUE;

// ----- Range indexes for temporal queries -----
// Critical for window-based filtering:
//   MATCH ...WHERE r.start_time >= ... AND r.start_time < ...

CREATE INDEX connects_to_start_time IF NOT EXISTS
    FOR ()-[r:CONNECTS_TO]-() ON (r.start_time);

CREATE INDEX connects_to_end_time IF NOT EXISTS
    FOR ()-[r:CONNECTS_TO]-() ON (r.end_time);

// Index on label used ONLY for evaluation queries (ground truth),
// NEVER in production detection logic.
CREATE INDEX connects_to_label IF NOT EXISTS
    FOR ()-[r:CONNECTS_TO]-() ON (r.label);

// ----- Property-existence indexes (optional but helps query planner) -----

CREATE INDEX host_first_seen IF NOT EXISTS
    FOR (h:Host) ON (h.first_seen);
