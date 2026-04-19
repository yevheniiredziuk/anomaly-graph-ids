#!/usr/bin/env bash
# Fire-and-forget experimental run for T19 (filling the paper's Section 6/7/Abstract).
#
# Usage: ./scripts/run_t19_experiments.sh
#
# Expected total runtime: ~6 hours (detector full 2-day run dominates).
# Skippable: full JMH (--skip-jmh) cuts ~30-60 min at the cost of having to
#            keep the T17 quick-mode numbers in the paper.
#
# Safe to walk away from. Every step logs to the same file; tail it if you want
# to watch progress. Failures are logged but don't abort subsequent independent
# steps where possible.

set -uo pipefail

REPO_ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
cd "$REPO_ROOT"

# Load .env (POSTGRES_PORT override etc.)
if [[ -f .env ]]; then
    set -a; . ./.env; set +a
fi
export POSTGRES_PORT="${POSTGRES_PORT:-5432}"
export NEO4J_PASSWORD="${NEO4J_PASSWORD:-changeme-local-only}"
export NEO4J_BOLT_PORT="${NEO4J_BOLT_PORT:-7687}"

STAMP=$(date +%Y%m%d-%H%M%S)
LOG_DIR="$REPO_ROOT/results"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/t19-run-$STAMP.log"
SUMMARY="$LOG_DIR/t19-summary-$STAMP.txt"

SKIP_JMH=false
for arg in "$@"; do
    [[ "$arg" == "--skip-jmh" ]] && SKIP_JMH=true
done

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG"; }

log "=== T19 experimental run ==="
log "Log file: $LOG"
log "Summary:  $SUMMARY"
log "Skip JMH: $SKIP_JMH"

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
log ""
log "[0/10] Pre-flight checks..."
docker compose ps --format '{{.Name}} {{.Status}}' | tee -a "$LOG"
if ! docker compose ps --format '{{.Name}} {{.Status}}' | grep -q "agids-neo4j.*healthy"; then
    log "ERROR: agids-neo4j not healthy — run 'docker compose up -d' first"
    exit 1
fi
if ! docker compose ps --format '{{.Name}} {{.Status}}' | grep -q "agids-postgres.*healthy"; then
    log "ERROR: agids-postgres not healthy — run 'docker compose up -d' first"
    exit 1
fi

HOST_COUNT=$(docker exec agids-neo4j cypher-shell -u neo4j -p "$NEO4J_PASSWORD" \
    "MATCH (h:Host) RETURN count(h) AS n" 2>&1 | grep -oE '[0-9]+' | tail -1)
FLOW_COUNT=$(docker exec agids-postgres psql -U "${POSTGRES_USER:-ids}" -d "${POSTGRES_DB:-ids}" \
    -tAc "SELECT count(*) FROM flows" 2>&1 | grep -oE '^[0-9]+')
log "Neo4j Hosts: $HOST_COUNT   Postgres flows: $FLOW_COUNT"

# ---------------------------------------------------------------------------
# Step 1: wipe AnomalyEvents
# ---------------------------------------------------------------------------
log ""
log "[1/10] Wipe AnomalyEvents..."
docker exec agids-neo4j cypher-shell -u neo4j -p "$NEO4J_PASSWORD" \
    "MATCH (e:AnomalyEvent) DETACH DELETE e;" >> "$LOG" 2>&1

# ---------------------------------------------------------------------------
# Step 2: full 2-day detector run with thetaA=0 (~5 hours)
# ---------------------------------------------------------------------------
log ""
log "[2/10] Full detector run (thetaA=0, full Tue+Wed) — ~5 hours..."
DETECTOR_START=$(date +%s)
(cd detector && ../mvnw -B -q spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.detector.scoring.DetectorCliApplication \
    -Dspring-boot.run.jvmArguments="-Ddetector.weights.thetaA=0.0" \
    -Dspring-boot.run.arguments="--start=2017-07-04T00:00:00Z --end=2017-07-06T00:00:00Z") \
    >> "$LOG" 2>&1 || log "WARN: detector run ended non-zero — continuing"
DETECTOR_ELAPSED=$(( $(date +%s) - DETECTOR_START ))
log "Detector run took ${DETECTOR_ELAPSED}s ($(( DETECTOR_ELAPSED / 60 )) min)"

EVENTS=$(docker exec agids-neo4j cypher-shell -u neo4j -p "$NEO4J_PASSWORD" \
    "MATCH (e:AnomalyEvent) RETURN count(e) AS n" 2>&1 | grep -oE '[0-9]+' | tail -1)
log "AnomalyEvents recorded: $EVENTS"

# ---------------------------------------------------------------------------
# Step 3: baseline refresh (ensures baseline_detections covers full 2-day)
# ---------------------------------------------------------------------------
log ""
log "[3/10] Baseline refresh..."
./scripts/run_baseline_detectors.sh --reset >> "$LOG" 2>&1 \
    || log "WARN: baseline refresh failed"

# ---------------------------------------------------------------------------
# Step 4: grid search over full 2-day → weight_simplex.csv + best config
# ---------------------------------------------------------------------------
log ""
log "[4/10] Grid search on full 2-day period..."
(cd evaluation && ../mvnw -B -q spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.evaluation.cli.EvaluationCliApplication \
    -Dspring-boot.run.arguments="--mode=export-simplex --start=2017-07-04T00:00:00Z --end=2017-07-06T00:00:00Z") \
    >> "$LOG" 2>&1 || log "WARN: grid search ended non-zero"

# Normalize CSV location (tool writes relative to its CWD)
for p in "$REPO_ROOT/evaluation/results/weight_simplex.csv"; do
    [[ -f "$p" ]] && mv "$p" "$REPO_ROOT/results/weight_simplex.csv"
done
rmdir "$REPO_ROOT/evaluation/results" 2>/dev/null || true

# Parse best config from log. Line format:
#   Grid search best: w1=X.X, w2=X.X, w3=X.X, θA=X.X → F1=...
BEST_LINE=$(grep "Grid search best" "$LOG" | tail -1)
NUMS=$(echo "$BEST_LINE" | sed -E 's/.*:(.*)→.*/\1/' | grep -oE '[0-9]+\.[0-9]+')
BEST_W1=$(echo "$NUMS" | sed -n '1p')
BEST_W2=$(echo "$NUMS" | sed -n '2p')
BEST_W3=$(echo "$NUMS" | sed -n '3p')
BEST_THETA=$(echo "$NUMS" | sed -n '4p')
log "Best config: w1=$BEST_W1 w2=$BEST_W2 w3=$BEST_W3 thetaA=$BEST_THETA"

if [[ -z "${BEST_W1:-}" || -z "${BEST_THETA:-}" ]]; then
    log "ERROR: could not parse best config from log — skipping downstream exports"
    BEST_W1=0.4; BEST_W2=0.3; BEST_W3=0.3; BEST_THETA=0.5
    log "Falling back to defaults: w1=$BEST_W1 w2=$BEST_W2 w3=$BEST_W3 thetaA=$BEST_THETA"
fi

# ---------------------------------------------------------------------------
# Step 5: per-attack recall export
# ---------------------------------------------------------------------------
log ""
log "[5/10] Per-attack recall export..."
(cd evaluation && ../mvnw -B -q spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.evaluation.cli.EvaluationCliApplication \
    -Dspring-boot.run.arguments="--mode=export-per-attack --start=2017-07-04T00:00:00Z --end=2017-07-06T00:00:00Z --w1=$BEST_W1 --w2=$BEST_W2 --w3=$BEST_W3 --thetaA=$BEST_THETA") \
    >> "$LOG" 2>&1 || log "WARN: per-attack export failed"
[[ -f "$REPO_ROOT/evaluation/results/per_attack_recall.csv" ]] && \
    mv "$REPO_ROOT/evaluation/results/per_attack_recall.csv" "$REPO_ROOT/results/"
rmdir "$REPO_ROOT/evaluation/results" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Step 6: ROC export
# ---------------------------------------------------------------------------
log ""
log "[6/10] ROC export..."
(cd evaluation && ../mvnw -B -q spring-boot:run \
    -Dspring-boot.run.main-class=ua.mitit.ids.evaluation.cli.EvaluationCliApplication \
    -Dspring-boot.run.arguments="--mode=export-roc --start=2017-07-04T00:00:00Z --end=2017-07-06T00:00:00Z --w1=$BEST_W1 --w2=$BEST_W2 --w3=$BEST_W3") \
    >> "$LOG" 2>&1 || log "WARN: ROC export failed"
[[ -f "$REPO_ROOT/evaluation/results/roc_data.csv" ]] && \
    mv "$REPO_ROOT/evaluation/results/roc_data.csv" "$REPO_ROOT/results/"
rmdir "$REPO_ROOT/evaluation/results" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Step 7: ablation — 3 runs with one w_i = 0, others re-normalised to sum=1
# ---------------------------------------------------------------------------
log ""
log "[7/10] Ablation..."
ABLATIONS=(
    "no_alpha1:0.0:0.5:0.5"
    "no_alpha2:0.5:0.0:0.5"
    "no_alpha3:0.5:0.5:0.0"
)
ABLATION_LOG="$LOG_DIR/t19-ablation-$STAMP.txt"
: > "$ABLATION_LOG"
for a in "${ABLATIONS[@]}"; do
    LABEL=${a%%:*}
    REST=${a#*:}
    W1=${REST%%:*}; REST=${REST#*:}
    W2=${REST%%:*}; W3=${REST#*:}
    log "  Ablation $LABEL: w1=$W1 w2=$W2 w3=$W3 thetaA=$BEST_THETA"
    echo "=== Ablation $LABEL ===" >> "$ABLATION_LOG"
    (cd evaluation && ../mvnw -B -q spring-boot:run \
        -Dspring-boot.run.main-class=ua.mitit.ids.evaluation.cli.EvaluationCliApplication \
        -Dspring-boot.run.arguments="--mode=evaluate-graph --start=2017-07-04T00:00:00Z --end=2017-07-06T00:00:00Z --w1=$W1 --w2=$W2 --w3=$W3 --thetaA=$BEST_THETA") \
        2>&1 | tee -a "$ABLATION_LOG" | grep -E "GRAPH|F1=|Per-attack" | tee -a "$LOG" || true
done
log "Ablation output: $ABLATION_LOG"

# ---------------------------------------------------------------------------
# Step 8: full JMH
# ---------------------------------------------------------------------------
log ""
if [[ "$SKIP_JMH" == "true" ]]; then
    log "[8/10] Full JMH — SKIPPED (--skip-jmh)"
else
    log "[8/10] Full JMH benchmarks (~30-60 min)..."
    ./scripts/run_benchmarks.sh >> "$LOG" 2>&1 \
        || log "WARN: JMH failed — T17 quick-mode numbers remain the fallback"
fi

# ---------------------------------------------------------------------------
# Step 9: regenerate plots from new CSVs
# ---------------------------------------------------------------------------
log ""
log "[9/10] Regenerate plots..."
./scripts/viz/run_all_plots.sh >> "$LOG" 2>&1 || log "WARN: plot regeneration failed"

# ---------------------------------------------------------------------------
# Step 10: summary
# ---------------------------------------------------------------------------
log ""
log "[10/10] Writing summary..."
{
    echo "T19 experimental run summary"
    echo "=============================="
    echo "Timestamp:     $STAMP"
    echo "Log file:      $LOG"
    echo "Ablation log:  $ABLATION_LOG"
    echo ""
    echo "Data volumes:"
    echo "  Neo4j Hosts:            $HOST_COUNT"
    echo "  Postgres flows:         $FLOW_COUNT"
    echo "  AnomalyEvents recorded: $EVENTS"
    echo ""
    echo "Best grid-search config:"
    echo "  w1      = $BEST_W1"
    echo "  w2      = $BEST_W2"
    echo "  w3      = $BEST_W3"
    echo "  thetaA  = $BEST_THETA"
    echo ""
    echo "CSV artifacts:"
    for f in weight_simplex per_attack_recall roc_data window_size_sweep; do
        p="$REPO_ROOT/results/$f.csv"
        if [[ -f "$p" ]]; then
            echo "  $(wc -l < "$p" | tr -d ' ') rows  $p"
        else
            echo "  MISSING        $p"
        fi
    done
    echo ""
    echo "JMH results:"
    ls -1t "$REPO_ROOT"/results/benchmark-*.json 2>/dev/null | head -3 | sed 's/^/  /'
    echo ""
    echo "Figures:"
    ls -1 "$REPO_ROOT/results/figures/"*.png 2>/dev/null | sed 's/^/  /' || echo "  (no PNGs)"
    echo ""
    echo "Ablation headline numbers:"
    grep -E "GRAPH|F1=" "$ABLATION_LOG" | head -20 | sed 's/^/  /'
    echo ""
    echo "---"
    echo "Open a fresh Claude Code session, paste this file, and ask to fill the"
    echo "placeholders in SECTION_{5,6,7}.md and ABSTRACT.md."
} > "$SUMMARY"

log "Summary written: $SUMMARY"
log ""
log "=== Run complete ==="
log ""
cat "$SUMMARY"
