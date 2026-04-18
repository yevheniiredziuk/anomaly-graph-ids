-- ============================================================================
-- Port-scan detector (flow-centric rule-based).
--
-- Heuristic: host X is flagged in [t_start, t_end) if it initiates connections
-- to >= threshold DISTINCT (dst_ip, dst_port) pairs. The distinct-pair form
-- covers both vertical scans (one IP, many ports) and horizontal scans
-- (many IPs, same port).
--
-- Known limitation: CICIDS2017 Tuesday has no dedicated port-scan campaign;
-- FTP/SSH-Patator reconnaissance may trigger false positives. That's part of
-- the "why graph methods add value" story in Section 6.3.
-- ============================================================================

CREATE OR REPLACE FUNCTION detect_port_scan(
    p_t_start        TIMESTAMPTZ,
    p_t_end          TIMESTAMPTZ,
    p_port_threshold INTEGER DEFAULT 50
)
RETURNS TABLE (
    src_ip_out       INET,
    unique_endpoints INTEGER
)
LANGUAGE plpgsql
AS $$
BEGIN
    WITH scanners AS (
        SELECT
            f.source_ip,
            COUNT(DISTINCT (f.destination_ip, f.destination_port)) AS uniq_endpoints,
            COUNT(DISTINCT f.destination_ip) AS uniq_destinations,
            COUNT(DISTINCT f.destination_port) AS uniq_ports
        FROM flows f
        WHERE f.t_start >= p_t_start
          AND f.t_start <  p_t_end
        GROUP BY f.source_ip
        HAVING COUNT(DISTINCT (f.destination_ip, f.destination_port)) >= p_port_threshold
    )
    INSERT INTO baseline_detections (
        detector_name, src_ip, dst_ip,
        t_window_start, t_window_end, severity_score, details_jsonb
    )
    SELECT
        'port_scan',
        s.source_ip,
        NULL::INET,  -- port scan has no single destination
        p_t_start,
        p_t_end,
        LEAST(1.0::FLOAT, s.uniq_endpoints::FLOAT / 500.0) AS severity,
        jsonb_build_object(
            'unique_endpoints',    s.uniq_endpoints,
            'unique_destinations', s.uniq_destinations,
            'unique_ports',        s.uniq_ports,
            'threshold',           p_port_threshold
        )
    FROM scanners s;

    RETURN QUERY
    SELECT
        f.source_ip,
        COUNT(DISTINCT (f.destination_ip, f.destination_port))::INTEGER
    FROM flows f
    WHERE f.t_start >= p_t_start
      AND f.t_start <  p_t_end
    GROUP BY f.source_ip
    HAVING COUNT(DISTINCT (f.destination_ip, f.destination_port)) >= p_port_threshold
    ORDER BY 2 DESC;
END;
$$;

COMMENT ON FUNCTION detect_port_scan IS
    'Detects port scanning: source IPs contacting >= threshold distinct (dst_ip, dst_port) pairs in window.';
