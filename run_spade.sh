#!/usr/bin/env bash
# run_spade.sh - run every SPADE scenario x class x seed x visibility x density
# through Eclipse MOSAIC, then collect each run's output into
#   data/<scenario>/<class>/<seed>/<density>-den_<visibility>-vis/
# Run from the repo root (the directory containing the scenario folders).
#
# Scenario folders are discovered automatically. Folders ending in "-test"
# use TEST_SEEDS; all other scenario folders use SEEDS.
#
# Usage:  ./run_spade.sh                 # build the jar, then run everything (skips completed runs)
#         DRY_RUN=1 ./run_spade.sh       # generate configs to a temp file, print plan, run nothing
#         ./run_spade.sh test            # run every scenario/class once at low density and first seed
#         ./run_spade.sh build           # compile application/ and copy the jar into every <scenario>/application/
#         SKIP_BUILD=1 ./run_spade.sh    # run without rebuilding the jar first
#         ./run_spade.sh clean           # restore configs, delete all .orig files and meta files

set -uo pipefail

MODE="${1:-run}"
REPO_ROOT="$(pwd)"
SCENARIO_ROOT="$REPO_ROOT/scenarios"
MOSAIC="${MOSAIC:-../eclipse-mosaic-25.2/mosaic.sh}"
DRY_RUN="${DRY_RUN:-0}"
SKIP_BUILD="${SKIP_BUILD:-0}"
MVN_DIR="application"
STATE_DIR=".run_state"
LOG_DIR="spade_mosaic_logs"
DATA_DIR="data"
MARKER="$STATE_DIR/start.marker"

SCENARIOS=()
shopt -s nullglob
for config in "$SCENARIO_ROOT"/*/scenario_config.json; do
  scenario_dir="${config%/scenario_config.json}"
  [[ -f "$scenario_dir/mapping/mapping_config.json" ]] || continue
  SCENARIOS+=("${scenario_dir##*/}")
done
shopt -u nullglob
if [[ ${#SCENARIOS[@]} -eq 0 ]]; then
  echo "ERROR: no scenario folders found under $SCENARIO_ROOT" >&2
  exit 1
fi

SEEDS=(956060311 441439018 326601683 950894839) # training/validation scenarios
TEST_SEEDS=(561603393)                # "-test" scenarios (seed unseen in training)
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

# Smoke-test one run for every scenario/class combination, using the first
# seed for each scenario type and the first configured visibility.
if [[ "$MODE" == "test" ]]; then
  SEEDS=("${SEEDS[0]}")
  TEST_SEEDS=("${TEST_SEEDS[0]}")
  VISIBILITIES=("${VISIBILITIES[0]}")
  DENSITIES=(low)
fi

# ---------------------------------------------------------------------------
# restore / cleanup helpers
# ---------------------------------------------------------------------------
# Put every config back and re-enable visualization.
restore_configs() {
  for s in "${SCENARIOS[@]}"; do
    for f in mapping/mapping_config.json scenario_config.json; do
      [[ -f "$SCENARIO_ROOT/$s/$f.orig" ]] && cp "$SCENARIO_ROOT/$s/$f.orig" "$SCENARIO_ROOT/$s/$f"
    done
    [[ -f "$SCENARIO_ROOT/$s/sumo/sumo_config.json.disabled" ]] && \
      mv -f "$SCENARIO_ROOT/$s/sumo/sumo_config.json.disabled" "$SCENARIO_ROOT/$s/sumo/sumo_config.json"
  done
}

# Delete the .orig backups (only call after restore_configs).
remove_orig() {
  for s in "${SCENARIOS[@]}"; do
    rm -f "$SCENARIO_ROOT/$s/mapping/mapping_config.json.orig" "$SCENARIO_ROOT/$s/scenario_config.json.orig"
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
# jar build
# ---------------------------------------------------------------------------
# build_jar: compile the Java project and copy the jar into every scenario's application dir
build_jar() {
  command -v mvn >/dev/null || { echo "ERROR: mvn not found"; return 1; }
  [[ -f "$REPO_ROOT/$MVN_DIR/pom.xml" ]] || { echo "ERROR: $MVN_DIR/pom.xml missing"; return 1; }

  echo "Building $MVN_DIR ..."
  ( cd "$REPO_ROOT/$MVN_DIR" && mvn clean package ) \
    || { echo "ERROR: maven build failed"; return 1; }

  local jars=() j
  for j in "$REPO_ROOT/$MVN_DIR"/target/*.jar; do
    [[ -f "$j" ]] || continue
    [[ "$(basename "$j")" == original-* ]] && continue   # shade plugin's unshaded copy
    jars+=("$j")
  done
  if [[ ${#jars[@]} -ne 1 ]]; then
    echo "ERROR: expected exactly one jar in $MVN_DIR/target/, found ${#jars[@]}:"
    printf '  %s\n' "${jars[@]}"
    return 1
  fi

  local s
  for s in "${SCENARIOS[@]}"; do
    [[ -d "$SCENARIO_ROOT/$s/application" ]] || { echo "ERROR: $SCENARIO_ROOT/$s/application missing"; return 1; }
    cp -f "${jars[0]}" "$SCENARIO_ROOT/$s/application/"
  done
  echo "Copied $(basename "${jars[0]}") to ${#SCENARIOS[@]} scenario application directories."
}

if [[ "$MODE" == "build" ]]; then
  build_jar
  exit $?
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

if [[ "$DRY_RUN" == "1" ]]; then
  echo "Dry run: skipping jar build."
elif [[ "$SKIP_BUILD" != "1" ]]; then
  build_jar || exit 1          # fail before any config is modified
fi

seeds_for() {
  if [[ "$1" == *-test ]]; then echo "${TEST_SEEDS[@]}"; else echo "${SEEDS[@]}"; fi
}

# If a previous run crashed before restoring, put things back first so the
# new backups are taken from the true originals.
restore_configs

# Back up the original configs once
for s in "${SCENARIOS[@]}"; do
  scenario_path="$SCENARIO_ROOT/$s"
  for f in mapping/mapping_config.json scenario_config.json; do
    [[ -f "$scenario_path/$f" ]] || { echo "ERROR: $scenario_path/$f missing"; exit 1; }
    [[ -f "$scenario_path/$f.orig" ]] || cp "$scenario_path/$f" "$scenario_path/$f.orig"
  done
  [[ -f "$scenario_path/mapping/routes.txt" ]] || { echo "ERROR: $scenario_path/mapping/routes.txt missing"; exit 1; }
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
    [[ -f "$SCENARIO_ROOT/$s/sumo/sumo_config.json" ]] && \
      mv -f "$SCENARIO_ROOT/$s/sumo/sumo_config.json" "$SCENARIO_ROOT/$s/sumo/sumo_config.json.disabled"
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
import glob
import xml.etree.ElementTree as ET
scen, cls, vis, dens, seed, out = sys.argv[1:7]
base = f"{scen}/mapping"

with open(f"{base}/mapping_config.json.orig") as f:
    cfg = json.load(f)
standalone_rsu = cls in {
    "org.example.attacks.ReplaySpatApp",
    "org.example.attacks.SybilSpatApp",
    "org.example.attacks.ImpersonationSpatApp",
}

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
            if not standalone_rsu:
                apps[i] = cls
            n_rsu += 1
            continue
        m = re.fullmatch(r'(org\.example\.VehicleApp\(\s*"[^"]*")[^)]*\)', a)
        if m:
            apps[i] = f"{m.group(1)}, {vis})"
            n_veh += 1
if n_rsu != 1 or n_veh != 1:
    sys.exit(f"ERROR: expected 1 RSU app and 1 VehicleApp entry, found {n_rsu}/{n_veh} in {base}")

if standalone_rsu:
    traffic_lights = cfg.get("trafficLights", [])
    if not traffic_lights:
        sys.exit(f"ERROR: no traffic light is configured in {base}")
    tl_id = str(traffic_lights[0].get("tlGroupId", ""))
    net_files = glob.glob(f"{scen}/sumo/*.net.xml")
    if len(net_files) != 1:
        sys.exit(f"ERROR: expected one SUMO network in {scen}/sumo, found {len(net_files)}")
    net = ET.parse(net_files[0]).getroot()
    junction = next((node for node in net.findall("junction") if node.get("id") == tl_id), None)
    if junction is None and tl_id.startswith("GS_"):
        junction_id = tl_id.removeprefix("GS_")
        junction = next(
            (node for node in net.findall("junction") if node.get("id") == junction_id),
            None,
        )
    location = net.find("location")
    if location is None:
        sys.exit(f"ERROR: missing SUMO projection metadata in {net_files[0]}")
    if junction is None:
        bounds = list(map(float, location.get("convBoundary", "").split(",")))
        traffic_light_junctions = [
            node for node in net.findall("junction")
            if node.get("type") == "traffic_light"
        ]
        if len(bounds) != 4 or not traffic_light_junctions:
            sys.exit(f"ERROR: cannot locate a traffic-light junction in {net_files[0]}")
        center_x, center_y = (bounds[0] + bounds[2]) / 2.0, (bounds[1] + bounds[3]) / 2.0
        junction = min(
            traffic_light_junctions,
            key=lambda node: (float(node.get("x")) - center_x) ** 2
                             + (float(node.get("y")) - center_y) ** 2,
        )
    projection = location.get("projParameter", "")
    offset_x, offset_y = map(float, location.get("netOffset", "0,0").split(","))
    easting = float(junction.get("x")) - offset_x
    northing = float(junction.get("y")) - offset_y

    try:
        from pyproj import Transformer
    except ImportError:
        sys.exit("ERROR: standalone attack RSUs require pyproj; install it with 'python3 -m pip install pyproj'")
    longitude, latitude = Transformer.from_crs(
        projection, "EPSG:4326", always_xy=True
    ).transform(easting, northing)

    cfg.setdefault("prototypes", []).append({
        "name": "AttackRsu",
        "applications": [cls],
    })
    cfg.setdefault("rsus", []).append({
        "name": "AttackRsu",
        "position": {
            "latitude": latitude,
            "longitude": longitude,
        },
    })

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
  scenario_path="$SCENARIO_ROOT/$scen"
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
            summary=$(gen_mapping "$scenario_path" "${CLASSES[$label]}" "$vis" "$dens" "$seed" "$STATE_DIR/dryrun_mapping.json") \
              || { echo "  GENERATION FAILED"; echo "$summary"; exit 1; }
            echo "  would run: $summary (visibility $vis) -> $dest"
            continue
          fi

          summary=$(gen_mapping "$scenario_path" "${CLASSES[$label]}" "$vis" "$dens" "$seed" "$scenario_path/mapping/mapping_config.json") \
            || { echo "$summary"; exit 1; }
          set_seed "$scenario_path" "$seed" || exit 1
          echo "  $summary (visibility $vis)"

          stale="$scenario_path/application/logs_${seed}"
          if [[ -d "$stale" ]]; then
            echo "  WARNING: stale $stale found, moving to $DATA_DIR/_stale/"
            mkdir -p "$DATA_DIR/_stale/$tag"
            mv "$stale" "$DATA_DIR/_stale/$tag/"
          fi

          sleep 1; touch "$MARKER"; sleep 1
          ( cd "$MOSAIC_DIR" && "$MOSAIC_BIN" -c "$scenario_path/scenario_config.json" ) \
            > "$LOG_DIR/$tag.log" 2>&1
          rc=$?

          moved=$(collect_outputs "$scenario_path" "$seed" "$dest")
          cp "$LOG_DIR/$tag.log" "$dest/console.log"
          cp "$scenario_path/mapping/mapping_config.json" "$dest/mapping_config.used.json"

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
