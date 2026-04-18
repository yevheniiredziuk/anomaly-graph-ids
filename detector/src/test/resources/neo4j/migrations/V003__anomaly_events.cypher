// ============================================================================
// V003: Indexes on :AnomalyEvent nodes (audit trail produced by T10-T13
// sliding-window detector — one node per (host, window) when D(v,t) = true).
// ============================================================================

CREATE INDEX anomaly_event_window IF NOT EXISTS
    FOR (e:AnomalyEvent) ON (e.t_window_start);

CREATE INDEX anomaly_event_host IF NOT EXISTS
    FOR (e:AnomalyEvent) ON (e.host_ip);
