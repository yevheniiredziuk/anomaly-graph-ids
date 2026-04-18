-- Utility: wipes all baseline_detections and resets the identity sequence.
-- Call before a fresh detector run to avoid duplicated event rows.

TRUNCATE baseline_detections RESTART IDENTITY;

SELECT 'baseline_detections reset' AS status,
       COUNT(*) AS current_rows
FROM baseline_detections;
