-- ============================================================================
-- Run all detectors over Tuesday+Wednesday in sliding 5-minute windows.
-- Mirrors the graph-based detector's (T10-T13) schedule for fair comparison.
-- ============================================================================

DO $$
DECLARE
    window_start  TIMESTAMPTZ;
    window_end    TIMESTAMPTZ;
    period_start  TIMESTAMPTZ := '2017-07-04 00:00:00+00';
    period_end    TIMESTAMPTZ := '2017-07-06 00:00:00+00';
    window_size   INTERVAL    := INTERVAL '5 minutes';
    windows_done  INTEGER     := 0;
    total_detects INTEGER;
    t0            TIMESTAMPTZ;
BEGIN
    t0 := clock_timestamp();
    window_start := period_start;

    WHILE window_start + window_size <= period_end LOOP
        window_end := window_start + window_size;

        PERFORM detect_port_scan  (window_start, window_end);
        PERFORM detect_brute_force(window_start, window_end);
        PERFORM detect_dos_flood  (window_start, window_end);

        windows_done := windows_done + 1;
        IF windows_done % 50 = 0 THEN
            RAISE NOTICE 'Processed % windows so far, elapsed %',
                         windows_done, clock_timestamp() - t0;
        END IF;

        window_start := window_end;
    END LOOP;

    SELECT COUNT(*) INTO total_detects FROM baseline_detections;
    RAISE NOTICE 'Done: % windows, % total detections, elapsed %',
                 windows_done, total_detects, clock_timestamp() - t0;
END;
$$;

SELECT
    detector_name,
    COUNT(*)                 AS detections,
    COUNT(DISTINCT src_ip)   AS unique_sources
FROM baseline_detections
GROUP BY detector_name
ORDER BY detections DESC;
