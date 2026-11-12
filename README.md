# SPADE

## Requirements

- Java 17
- Maven 4.0.0
- Eclipse MOSAIC 25.2
- SUMO 1.28.0
- Python 3 with `pyproj` (needed for standalone attack RSU placement)

*Ensure Eclipse MOSAIC folder is placed next to the SPADE project folder as shown*
```
├── SPADE
│   ...
│   ├── mosaic
│   ├── README.md
│   └── run_spade.sh
├── eclipse-mosaic-25.2
│   ...
│   └── mosaic.sh
```

MOSAIC scenarios are each within their respective folders. Java application code is contained within the `mosaic` folder.

## Execution

1. Execute permissions `chmod +x ./run_spade.sh`

2. Build and distribute application jar `./run_spade.sh build`

3. Generation script dry run `DRY_RUN=1 ./run_spade.sh`

4. Run a smoke test with one high-density run per scenario and attack/benign class, using the first seed: `./run_spade.sh test`

5. Run the full dataset generation `./run_spade.sh`

6. Clean up script metadata files `./run_spade.sh clean`

## Processing generated data

After generating runs under `data/`, convert their raw MOSAIC logs into SPADE
records with:

```sh
python3 process_spade.py
```

By default, the script reads `data/` and writes per-vehicle JSON files to
`processed/spade_records/`. Use `--input` and `--output` to change these paths.

- Records are generated at 10 Hz with vehicle, perception, and received-message data.
- Vehicle, traffic-light, and intersection/RSU identifiers are replaced with deterministic pseudonyms scoped to each run. Reprocessing the same run produces the same identifiers, while identifiers differ across runs; references in CAM, shared-perception, SPaT, and MAP data stay consistent.
- The latest CAM and shared-perception snapshot per transmitting vehicle is retained for 2 seconds; older snapshots are removed. Override this with `--message-ttl-seconds`.
- Vehicle CSV logging starts within 100 m of the nearest MAP intersection and continues until the vehicle has passed it and is more than 100 m away.
- Attack schedules emit one attack tick in every five-second block with a randomized run phase; the processor checks for a 20% attack split (default tolerance: 5 percentage points).
- Replay, Sybil, and Impersonation use standalone RSUs; other attacks replace the traffic-light app.
- Received attack messages are labeled individually; attack intervals are also recorded.
