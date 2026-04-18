-- ============================================================================
-- Brute-force detector.
--
-- Heuristic: host X is flagged as brute-forcing service (dst_ip:dst_port) in
-- [t_start, t_end) if it initiates >= threshold connections with a dominant
-- share (>= short_ratio_min) of short-lived flows (< 5 s).
--
-- 5 s ceiling accommodates network jitter; real password attempts are
-- typically sub-second. Raise the threshold if too many benign HTTP keepalive
-- bursts trigger.
--
-- CICIDS2017 context: FTP-Patator and SSH-Patator on Tuesday are the canonical
-- targets for this detector.
-- ============================================================================

CREATE OR REPLACE FUNCTION detect_brute_force(
    p_t_start           TIMESTAMPTZ,
    p_t_end             TIMESTAMPTZ,
    p_attempt_threshold INTEGER DEFAULT 100,
    p_short_ratio_min   FLOAT   DEFAULT 0.7
)
RETURNS TABLE (
    src_ip_out    INET,
    dst_ip_out    INET,
    dst_port_out  INTEGER,
    attempts      INTEGER,
    short_ratio   FLOAT
)
LANGUAGE plpgsql
AS $$
BEGIN
    WITH bf AS (
        SELECT
            f.source_ip,
            f.destination_ip,
            f.destination_port,
            COUNT(*) AS attempts,
            SUM(CASE WHEN f.flow_duration_us < 5000000 THEN 1 ELSE 0 END)::FLOAT
              / COUNT(*) AS short_ratio
        FROM flows f
        WHERE f.t_start >= p_t_start
          AND f.t_start <  p_t_end
        GROUP BY f.source_ip, f.destination_ip, f.destination_port
        HAVING COUNT(*) >= p_attempt_threshold
           AND SUM(CASE WHEN f.flow_duration_us < 5000000 THEN 1 ELSE 0 END)::FLOAT
               / COUNT(*) >= p_short_ratio_min
    )
    INSERT INTO baseline_detections (
        detector_name, src_ip, dst_ip,
        t_window_start, t_window_end, severity_score, details_jsonb
    )
    SELECT
        'brute_force',
        bf.source_ip,
        bf.destination_ip,
        p_t_start,
        p_t_end,
        LEAST(1.0::FLOAT, bf.attempts::FLOAT / 1000.0) AS severity,
        jsonb_build_object(
            'attempts',    bf.attempts,
            'short_ratio', bf.short_ratio,
            'dst_port',    bf.destination_port,
            'threshold',   p_attempt_threshold
        )
    FROM bf;

    RETURN QUERY
    SELECT
        f.source_ip,
        f.destination_ip,
        f.destination_port,
        COUNT(*)::INTEGER,
        (SUM(CASE WHEN f.flow_duration_us < 5000000 THEN 1 ELSE 0 END)::FLOAT / COUNT(*))
    FROM flows f
    WHERE f.t_start >= p_t_start
      AND f.t_start <  p_t_end
    GROUP BY f.source_ip, f.destination_ip, f.destination_port
    HAVING COUNT(*) >= p_attempt_threshold
       AND (SUM(CASE WHEN f.flow_duration_us < 5000000 THEN 1 ELSE 0 END)::FLOAT / COUNT(*))
           >= p_short_ratio_min
    ORDER BY 4 DESC;
END;
$$;

COMMENT ON FUNCTION detect_brute_force IS
    'Detects brute force: many short-lived flows from X to same (dst_ip, dst_port).';
