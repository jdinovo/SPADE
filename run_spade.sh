#!/usr/bin/env bash
# run_spade.sh - run every SPADE scenario x class x seed x visibility x density
# through Eclipse MOSAIC, then collect each run's output into
#   data/<scenario>/<class>/<seed>/<density>-den_<visibility>-vis/
# Run from the repo root (the directory containing 2lane3way/, 2lane4way/, ...).
#
# Main scenarios run with SEEDS (4); "-test" scenarios run with TEST_SEEDS (1).
# Total runs: 4*4*63 + 4*1*63 = 1,260
#
# Usage:  ./run_spade.sh            # run everything (skips completed runs)
#         DRY_RUN=1 ./run_spade.sh  # generate configs to a temp file, print plan, run nothing
#         ./run_spade.sh clean      # restore configs, delete all .orig files and meta files

set -uo pipefail

MODE="${1:-run}"
REPO_ROOT="$(pwd)"
MOSAIC="${MOSAIC:-../eclipse-mosaic-25.2/mosaic.sh}"
DRY_RUN="${DRY_RUN:-0}"
STATE_DIR=".run_state"
LOG_DIR="run_logs"
DATA_DIR="data"
MARKER="$STATE_DIR/start.marker"

SCENARIOS=(
  2lane3way 2lane4way 4lane4way 4lanepxo
  2lane3way-test 2lane4way-test 4lane4way-test 4lanepxo-test
)
SEEDS=(11111 22222 33333 44444)       # training/validation scenarios
TEST_SEEDS=(55555)                    # "-test" scenarios (seed unseen in training)
VISIBILITIES=(0.95 0.6 0.1)
DENSITIES=(low medium high)
declare -A VIS_LABEL=( [0.95]=high [0.6]=medium [0.1]=low )

declare -A CLASSES=(
  [BENIGN]="org.example.RsuSpatApp"
  [FALSE_STATE]="org.example.attacks.TamperSpatApp"
  [REPLAY]="org.example.attacks.ReplaySpatApp"
  [TIMING_MANIP]="org.example.attacks.TimingSpatApp"
  [SYBIL]="org.example.attacks.SybilSpatApp"
  [DOS]="org.example.attacks.DosSpatApp"
  [IMPERSONATION]="org.example.attacks.ImpersonationSpatApp"
)
ORDER=(BENIGN FALSE_STATE REPLAY TIMING_MANIP SYBIL DOS IMPERSONATION)

# ---------------------------------------------------------------------------
# restore / cleanup helpers
# ---------------------------------------------------------------------------
# Put every config back and re-enable visualization.
restore_configs() {
  for s in "${SCENARIOS[@]}"; do
    for f in mapping/mapping_config.json scenario_config.json; do
      [[ -f "$REPO_ROOT/$s/$f.orig" ]] && cp "$REPO_ROOT/$s/$f.orig" "$REPO_ROOT/$s/$f"
    done
    [[ -f "$REPO_ROOT/$s/sumo/sumo_config.json.disabled" ]] && \
      mv -f "$REPO_ROOT/$s/sumo/sumo_config.json.disabled" "$REPO_ROOT/$s/sumo/sumo_config.json"
  done
}

# Delete the .orig backups (only call after restore_configs).
remove_orig() {
  for s in "${SCENARIOS[@]}"; do
    rm -f "$REPO_ROOT/$s/mapping/mapping_config.json.orig" "$REPO_ROOT/$s/scenario_config.json.orig"
  done
}

# Delete files created only to run this script.
remove_meta() {
  rm -rf "$REPO_ROOT/$STATE_DIR" "$REPO_ROOT/$LOG_DIR"
}

# "clean" command: restore, then remove .orig and meta files, then exit.
if [[ "$MODE" == "clean" ]]; then
  restore_configs
  remove_orig
  remove_meta
  echo "Cleaned: configs restored, .orig files and meta files removed."
  exit 0
fi

# ---------------------------------------------------------------------------
# startup checks
# ---------------------------------------------------------------------------
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
[[ -f "$MOSAIC" ]] || { echo "ERROR: $MOSAIC not found"; exit 1; }
MOSAIC_DIR="$(cd "$(dirname "$MOSAIC")" && pwd)"   # mosaic.sh must run from here
MOSAIC_BIN="./$(basename "$MOSAIC")"
[[ -x "$MOSAIC_DIR/$(basename "$MOSAIC")" ]] || { echo "ERROR: $MOSAIC is not executable"; exit 1; }

LOG_SOURCES=("$MOSAIC_DIR/logs" "$REPO_ROOT/logs")

mkdir -p "$STATE_DIR" "$LOG_DIR" "$DATA_DIR"

seeds_for() {
  if [[ "$1" == *-test ]]; then echo "${TEST_SEEDS[@]}"; else echo "${SEEDS[@]}"; fi
}

# If a previous run crashed before restoring, put things back first so the
# new backups are taken from the true originals.
restore_configs

# Back up the original configs once
for s in "${SCENARIOS[@]}"; do
  for f in mapping/mapping_config.json scenario_config.json; do
    [[ -f "$s/$f" ]] || { echo "ERROR: $s/$f missing"; exit 1; }
    [[ -f "$s/$f.orig" ]] || cp "$s/$f" "$s/$f.orig"
  done
  [[ -f "$s/mapping/routes.txt" ]] || { echo "ERROR: $s/mapping/routes.txt missing"; exit 1; }
done

FINISHED=0
on_exit() {
  echo "Restoring original configs and re-enabling visualization..."
  restore_configs
  remove_orig
  if [[ "$DRY_RUN" == "1" ]]; then
    rm -f "$REPO_ROOT/$STATE_DIR/dryrun_mapping.json"     # dry run: don't touch resume markers
  elif [[ "$FINISHED" == "1" && ${#failed[@]} -eq 0 && ${#empty[@]} -eq 0 ]]; then
    remove_meta                                           # clean, complete batch only
  fi
}
failed=(); empty=()
trap on_exit EXIT

# Disable visualization for the duration of the batch (restored in on_exit)
if [[ "$DRY_RUN" != "1" ]]; then
  for s in "${SCENARIOS[@]}"; do
    [[ -f "$s/sumo/sumo_config.json" ]] && mv -f "$s/sumo/sumo_config.json" "$s/sumo/sumo_config.json.disabled"
  done
fi

# gen_mapping <scenario> <class> <visibility> <density> <seed> <out_file>
# Builds mapping_config.json from <scenario>/mapping/mapping_config.json.orig:
#   - sets the RSU application class
#   - sets VehicleApp("<orig first arg>", <visibility>)
#   - replaces the vehicles array using route ids from mapping/routes.txt
# Traffic is seeded by scenario+density only (no shuffling), so each density's
# vehicle list is identical across attack classes, seeds and visibilities.
gen_mapping() {
  python3 - "$@" <<'PY'
import sys, json, re, random
scen, cls, vis, dens, seed, out = sys.argv[1:7]
base = f"{scen}/mapping"

with open(f"{base}/mapping_config.json.orig") as f:
    cfg = json.load(f)

routes = []
with open(f"{base}/routes.txt") as f:
    for line in f:
        line = line.strip()
        if re.fullmatch(r"\d+", line):      # a route id is a line containing only digits
            routes.append(line)
routes = list(dict.fromkeys(routes))
if not routes:
    sys.exit(f"ERROR: no route ids found in {base}/routes.txt")

# --- applications -----------------------------------------------------------
n_rsu = n_veh = 0
for proto in cfg.get("prototypes", []):
    apps = proto.get("applications", [])
    for i, a in enumerate(apps):
        if re.fullmatch(r"org\.example\.(attacks\.)?\w+SpatApp", a):
            apps[i] = cls
            n_rsu += 1
            continue
        m = re.fullmatch(r'(org\.example\.VehicleApp\(\s*"[^"]*")[^)]*\)', a)
        if m:
            apps[i] = f"{m.group(1)}, {vis})"
            n_veh += 1
if n_rsu != 1 or n_veh != 1:
    sys.exit(f"ERROR: expected 1 RSU app and 1 VehicleApp entry, found {n_rsu}/{n_veh} in {base}")

# --- vehicles ---------------------------------------------------------------
TARGET_FLOW = 60   # constant for every entry and density
PARAMS = {  # total vehicles per run
    "low":    {"total": (6, 8)},
    "medium": {"total": (13, 17)},
    "high":   {"total": (27, 33)},
}
PER_ENTRY = (2, 5)   # vehicles per route entry (maxNumberVehicles)
p = PARAMS[dens]
rng = random.Random(f"{scen}|{dens}")

total = rng.randint(*p["total"])
start = rng.randrange(len(routes))      # fixed starting point in routes.txt

chosen, counts, remaining, k = [], [], total, 0
while remaining > 0 and k < len(routes):   # consecutive routes, wrapping, until total is met
    c = min(remaining, rng.randint(*PER_ENTRY))
    chosen.append(routes[(start + k) % len(routes)])
    counts.append(c)
    remaining -= c
    k += 1

i = 0
while remaining > 0:                    # only if routes ran out: spread the rest
    counts[i % len(counts)] += 1
    remaining -= 1
    i += 1

vehicles = []
for k, (route, cnt) in enumerate(zip(chosen, counts)):
    vehicles.append({
        "startingTime": 0.0 if k == 0 else round(rng.uniform(0.0, 10.0), 1),
        "targetFlow": TARGET_FLOW,
        "maxNumberVehicles": cnt,
        "route": route,
        "types": [{"name": "Car"}],
    })
cfg["vehicles"] = vehicles

with open(out, "w") as f:
    json.dump(cfg, f, indent=4)
print(f"vehicles={total} entries={len(chosen)} routes_available={len(routes)}")
PY
}

set_seed() {    # $1=scenario $2=seed
  local file="$1/scenario_config.json"
  sed -E -i "s/(\"randomSeed\"[[:space:]]*:[[:space:]]*)-?[0-9]+/\1$2/" "$file"
  grep -Eq "\"randomSeed\"[[:space:]]*:[[:space:]]*$2" "$file" || {
    echo "ERROR: could not set randomSeed in $file"; return 1; }
}

move_file() {
  local f="$1" root="$2" dest="$3" rel
  rel="${f#"$root"/}"
  mkdir -p "$dest/$(dirname "$rel")"
  mv -f "$f" "$dest/$rel"
}

# Like move_file, but drops the first directory level under source_root
# (MOSAIC's timestamped run folder).
move_file_flat() {
  local f="$1" root="$2" dest="$3" rel
  rel="${f#"$root"/}"
  [[ "$rel" == */* ]] && rel="${rel#*/}"
  mkdir -p "$dest/$(dirname "$rel")"
  mv -f "$f" "$dest/$rel"
}

# collect_outputs <scenario> <seed> <dest_dir>  -> prints number of files moved
collect_outputs() {
  local scen="$1" seed="$2" dest="$3" count=0 f src i=0
  local csvsrc="$scen/application/logs_${seed}"
  mkdir -p "$dest"

  if [[ -d "$csvsrc" ]]; then
    while IFS= read -r -d '' f; do
      move_file "$f" "$csvsrc" "$dest/csv"; count=$((count+1))
    done < <(find "$csvsrc" -type f -print0)
    find "$csvsrc" -depth -type d -empty -delete 2>/dev/null
  fi

  for src in "${LOG_SOURCES[@]}"; do
    i=$((i+1))
    [[ -d "$src" ]] || continue
    while IFS= read -r -d '' f; do
      move_file_flat "$f" "$src" "$dest/mosaic_logs_$i"; count=$((count+1))
    done < <(find "$src" -type f -newer "$MARKER" -print0)
    find "$src" -mindepth 1 -type d -empty -delete 2>/dev/null
  done

  echo "$count"
}

total=0
for s in "${SCENARIOS[@]}"; do
  read -r -a _sd <<< "$(seeds_for "$s")"
  total=$(( total + ${#ORDER[@]} * ${#_sd[@]} * ${#VISIBILITIES[@]} * ${#DENSITIES[@]} ))
done
n=0

for scen in "${SCENARIOS[@]}"; do
  read -r -a scen_seeds <<< "$(seeds_for "$scen")"
  for label in "${ORDER[@]}"; do
    for seed in "${scen_seeds[@]}"; do
      for vis in "${VISIBILITIES[@]}"; do
        for dens in "${DENSITIES[@]}"; do
          n=$((n+1))
          vlabel="${VIS_LABEL[$vis]}"
          tag="${scen}_${label}_${seed}_${dens}-den_${vlabel}-vis"
          dest="$DATA_DIR/$scen/$label/$seed/${dens}-den_${vlabel}-vis"
          echo "[$n/$total] $tag"

          if [[ -f "$STATE_DIR/$tag.done" ]]; then echo "  already done, skipping"; continue; fi

          if [[ "$DRY_RUN" == "1" ]]; then
            summary=$(gen_mapping "$scen" "${CLASSES[$label]}" "$vis" "$dens" "$seed" "$STATE_DIR/dryrun_mapping.json") \
              || { echo "  GENERATION FAILED"; echo "$summary"; exit 1; }
            echo "  would run: $summary (visibility $vis) -> $dest"
            continue
          fi

          summary=$(gen_mapping "$scen" "${CLASSES[$label]}" "$vis" "$dens" "$seed" "$scen/mapping/mapping_config.json") \
            || { echo "$summary"; exit 1; }
          set_seed "$scen" "$seed" || exit 1
          echo "  $summary (visibility $vis)"

          stale="$scen/application/logs_${seed}"
          if [[ -d "$stale" ]]; then
            echo "  WARNING: stale $stale found, moving to $DATA_DIR/_stale/"
            mkdir -p "$DATA_DIR/_stale/$tag"
            mv "$stale" "$DATA_DIR/_stale/$tag/"
          fi

          sleep 1; touch "$MARKER"; sleep 1
          ( cd "$MOSAIC_DIR" && "$MOSAIC_BIN" -c "$REPO_ROOT/$scen/scenario_config.json" ) \
            > "$LOG_DIR/$tag.log" 2>&1
          rc=$?

          moved=$(collect_outputs "$scen" "$seed" "$dest")
          cp "$LOG_DIR/$tag.log" "$dest/console.log"
          cp "$scen/mapping/mapping_config.json" "$dest/mapping_config.used.json"

          if [[ $rc -ne 0 ]]; then
            echo "  FAILED rc=$rc (partial output in $dest, console in $LOG_DIR/$tag.log)"
            failed+=("$tag")
          elif [[ "$moved" -eq 0 ]]; then
            echo "  WARNING: run succeeded but no output files were found to collect"
            empty+=("$tag")
          else
            echo "  collected $moved file(s) -> $dest"
            touch "$STATE_DIR/$tag.done"
          fi
        done
      done
    done
  done
done

FINISHED=1
echo "Finished. ${#failed[@]} failed, ${#empty[@]} produced no collectable output."
printf '  failed: %s\n' "${failed[@]}"
printf '  empty:  %s\n' "${empty[@]}"
