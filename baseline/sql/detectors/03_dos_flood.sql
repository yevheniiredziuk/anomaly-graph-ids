-- ============================================================================
-- DoS-flood detector.
--
-- Heuristic: destination Y is victim of volumetric DoS in [t_start, t_end) if
-- total incoming flows >= flow_threshold AND a single source accounts for
-- >= concentration_min of those flows.
--
-- Catches single-source volumetric DoS (CICIDS2017 Wed: Hulk, GoldenEye).
-- Deliberately does NOT catch slow DoS (slowloris, Slowhttptest) — those
-- generate FEWER flows, not more. That's a known limitation of rate-based
-- detectors and is part of the Section 6.3 argument.
-- ============================================================================

CREATE OR REPLACE FUNCTION detect_dos_flood(
    p_t_start           TIMESTAMPTZ,
    p_t_end             TIMESTAMPTZ,
    p_flow_threshold    INTEGER DEFAULT 1000,
    p_concentration_min FLOAT   DEFAULT 0.7
)
RETURNS TABLE (
    src_ip_out       INET,
    dst_ip_out       INET,
    max_source_flows INTEGER,
    concentration    FLOAT
)
LANGUAGE plpgsql
AS $$
BEGIN
    WITH per_source AS (
        SELECT f.destination_ip, f.source_ip, COUNT(*) AS src_flows
        FROM flows f
        WHERE f.t_start >= p_t_start AND f.t_start < p_t_end
        GROUP BY f.destination_ip, f.source_ip
    ),
    per_dst AS (
        SELECT
            destination_ip,
            SUM(src_flows)                                          AS total_flows,
            MAX(src_flows)                                          AS max_src_flows,
            (ARRAY_AGG(source_ip ORDER BY src_flows DESC))[1]       AS top_source
        FROM per_source
        GROUP BY destination_ip
    ),
    victims AS (
        SELECT *
        FROM per_dst
        WHERE total_flows   >= p_flow_threshold
          AND top_source    IS NOT NULL
          AND max_src_flows::FLOAT / total_flows >= p_concentration_min
    )
    INSERT INTO baseline_detections (
        detector_name, src_ip, dst_ip,
        t_window_start, t_window_end, severity_score, details_jsonb
    )
    SELECT
        'dos_flood',
        v.top_source,
        v.destination_ip,
        p_t_start,
        p_t_end,
        LEAST(1.0::FLOAT, v.max_src_flows::FLOAT / 10000.0) AS severity,
        jsonb_build_object(
            'total_flows',      v.total_flows,
            'max_source_flows', v.max_src_flows,
            'concentration',    v.max_src_flows::FLOAT / v.total_flows,
            'threshold',        p_flow_threshold
        )
    FROM victims v;

    RETURN QUERY
    WITH per_source AS (
        SELECT f.destination_ip, f.source_ip, COUNT(*) AS src_flows
        FROM flows f
        WHERE f.t_start >= p_t_start AND f.t_start < p_t_end
        GROUP BY f.destination_ip, f.source_ip
    ),
    per_dst AS (
        SELECT
            destination_ip,
            SUM(src_flows)                                    AS total_flows,
            MAX(src_flows)                                    AS max_src_flows,
            (ARRAY_AGG(source_ip ORDER BY src_flows DESC))[1] AS top_source
        FROM per_source
        GROUP BY destination_ip
    )
    SELECT
        top_source,
        destination_ip,
        max_src_flows::INTEGER,
        max_src_flows::FLOAT / total_flows
    FROM per_dst
    WHERE total_flows >= p_flow_threshold
      AND top_source  IS NOT NULL
      AND max_src_flows::FLOAT / total_flows >= p_concentration_min
    ORDER BY max_src_flows DESC;
END;
$$;

COMMENT ON FUNCTION detect_dos_flood IS
    'Detects volumetric DoS: destination receives >= threshold flows with >= concentration from a single source.';
