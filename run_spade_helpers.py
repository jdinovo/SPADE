#!/usr/bin/env python3
"""Python helpers for run_spade.sh."""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path


def generate_mapping(args: argparse.Namespace) -> None:
    scenario = Path(args.scenario)
    base = scenario / "mapping"
    with (base / "mapping_config.json.orig").open() as source:
        config = json.load(source)
    route_schedule_path = base / "route_schedule.json"
    with route_schedule_path.open() as source:
        route_schedule = json.load(source)
    if route_schedule.get("schema_version") != 2:
        raise SystemExit(f"ERROR: unsupported route schedule schema in {route_schedule_path}")
    routes = list(route_schedule["route_distances_m"])
    target_node = route_schedule["target_node"]
    density_schedule = route_schedule["densities"][args.density]
    selected_routes = density_schedule["routes"]
    total_vehicles = density_schedule["vehicle_count"]
    if len(selected_routes) != total_vehicles:
        raise SystemExit(f"ERROR: invalid {args.density} route list in {route_schedule_path}")
    route_delays = {
        route_id: max(
            0.0, float(distance) - args.logging_radius,
        ) / args.speed_mps
        for route_id, distance in route_schedule["route_distances_m"].items()
    }

    standalone_rsu = args.application_class in {
        "org.example.attacks.ReplaySpatApp",
        "org.example.attacks.SybilSpatApp",
        "org.example.attacks.ImpersonationSpatApp",
    }

    calibrated = {}
    calibration_mapping = Path(args.calibration_dir) / "mapping_config.used.json"
    calibration_logs = list(
        (Path(args.calibration_dir) / "csv").glob("*_veh_output.csv"),
    )
    if not (calibration_mapping.is_file() and calibration_logs):
        calibration_mapping = Path(args.low_calibration_dir) / "mapping_config.used.json"
        calibration_logs = list(
            (Path(args.low_calibration_dir) / "csv").glob("*_veh_output.csv"),
        )
    if calibration_mapping.is_file() and calibration_logs:
        with calibration_mapping.open() as source:
            baseline_starts = {}
            for vehicle in json.load(source).get("vehicles", []):
                route_id = str(vehicle["route"])
                baseline_starts[route_id] = min(
                    baseline_starts.get(route_id, float("inf")),
                    float(vehicle["startingTime"]),
                )
        first_log_time = {}
        for path in calibration_logs:
            with path.open(newline="") as source:
                for row in csv.reader(source):
                    if row and row[0] == "VEHICLE_DATA" and len(row) == 13:
                        route_id = row[3]
                        first_log_time[route_id] = min(
                            first_log_time.get(route_id, float("inf")),
                            int(row[1]) / 1_000_000_000,
                        )
        for route_id in routes:
            if route_id in first_log_time and route_id in baseline_starts:
                calibrated[route_id] = max(
                    0.0, first_log_time[route_id] - baseline_starts[route_id],
                )
                route_delays[route_id] = calibrated[route_id]

    rsu_count = vehicle_count = 0
    for prototype in config.get("prototypes", []):
        applications = prototype.get("applications", [])
        for index, application in enumerate(applications):
            if re.fullmatch(r"org\.example\.(attacks\.)?\w+SpatApp", application):
                if not standalone_rsu:
                    applications[index] = args.application_class
                rsu_count += 1
                continue
            match = re.fullmatch(
                r'(org\.example\.VehicleApp\(\s*"[^"]*")[^)]*\)', application,
            )
            if match:
                applications[index] = f"{match.group(1)}, {args.visibility})"
                vehicle_count += 1
    if rsu_count != 1 or vehicle_count != 1:
        raise SystemExit(
            f"ERROR: expected 1 RSU app and 1 VehicleApp entry, found "
            f"{rsu_count}/{vehicle_count} in {base}"
        )

    if standalone_rsu:
        traffic_lights = config.get("trafficLights", [])
        if not traffic_lights:
            raise SystemExit(f"ERROR: no traffic light is configured in {base}")
        traffic_light_id = str(traffic_lights[0].get("tlGroupId", ""))
        network_files = list((scenario / "sumo").glob("*.net.xml"))
        if len(network_files) != 1:
            raise SystemExit(
                f"ERROR: expected one SUMO network in {scenario}/sumo, "
                f"found {len(network_files)}"
            )
        network = ET.parse(network_files[0]).getroot()
        junction = next(
            (node for node in network.findall("junction")
             if node.get("id") == traffic_light_id),
            None,
        )
        if junction is None and traffic_light_id.startswith("GS_"):
            junction_id = traffic_light_id.removeprefix("GS_")
            junction = next(
                (node for node in network.findall("junction")
                 if node.get("id") == junction_id),
                None,
            )
        location = network.find("location")
        if location is None:
            raise SystemExit(
                f"ERROR: missing SUMO projection metadata in {network_files[0]}"
            )
        if junction is None:
            bounds = list(map(float, location.get("convBoundary", "").split(",")))
            traffic_light_junctions = [
                node for node in network.findall("junction")
                if node.get("type") == "traffic_light"
            ]
            if len(bounds) != 4 or not traffic_light_junctions:
                raise SystemExit(
                    f"ERROR: cannot locate a traffic-light junction in {network_files[0]}"
                )
            center_x = (bounds[0] + bounds[2]) / 2.0
            center_y = (bounds[1] + bounds[3]) / 2.0
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
        except ImportError as error:
            raise SystemExit(
                "ERROR: standalone attack RSUs require pyproj; "
                "install it with 'python3 -m pip install pyproj'"
            ) from error
        longitude, latitude = Transformer.from_crs(
            projection, "EPSG:4326", always_xy=True,
        ).transform(easting, northing)
        config.setdefault("prototypes", []).append({
            "name": "AttackRsu",
            "applications": [args.application_class],
        })
        config.setdefault("rsus", []).append({
            "name": "AttackRsu",
            "position": {"latitude": latitude, "longitude": longitude},
        })

    arrival_estimates = {
        route_id: route_delays[route_id] for route_id in selected_routes
    }
    target_arrival = max(route_delays.values())
    vehicles = []
    for index, route_id in enumerate(selected_routes):
        vehicle_arrival = target_arrival + args.arrival_window * index / total_vehicles
        starting_time = vehicle_arrival - arrival_estimates[route_id]
        if starting_time < -0.05:
            raise SystemExit(f"ERROR: negative departure time for route {route_id}")
        vehicles.append({
            "startingTime": round(max(0.0, starting_time), 1),
            "targetFlow": 60,
            "maxNumberVehicles": 1,
            "route": route_id,
            "types": [{"name": "Car"}],
        })
    config["vehicles"] = vehicles
    duration = math.ceil(
        target_arrival + args.arrival_window + args.clearance,
    )

    with Path(args.output).open("w") as destination:
        json.dump(config, destination, indent=4)
    print(
        f"vehicles={total_vehicles} entries={len(vehicles)} "
        f"arrival_window={args.arrival_window:g}s "
        f"target={target_node} "
        f"route_eta_range={min(arrival_estimates.values()):.1f}-"
        f"{max(arrival_estimates.values()):.1f}s "
        f"calibrated_routes={len(calibrated)} "
        f"duration={duration}s routes_available={len(routes)}"
    )


def balance_vehicle_logs(args: argparse.Namespace) -> None:
    current_csv = Path(args.current_csv)
    low_csv = Path(args.low_csv)
    run_dir = Path(args.run_dir)

    def record_counts(directory: Path) -> dict[Path, int]:
        counts = {}
        for path in sorted(directory.glob("veh_*_veh_output.csv")):
            first_time = last_time = None
            with path.open(newline="", encoding="utf-8-sig") as source:
                for line, row in enumerate(csv.reader(source), 1):
                    if row and row[0] == "VEHICLE_DATA":
                        if len(row) != 13:
                            raise ValueError(
                                f"{path}:{line}: expected 13 VEHICLE_DATA fields"
                            )
                        try:
                            timestamp = int(row[1])
                        except ValueError as error:
                            raise ValueError(
                                f"{path}:{line}: invalid vehicle timestamp"
                            ) from error
                        first_time = timestamp if first_time is None else min(first_time, timestamp)
                        last_time = timestamp if last_time is None else max(last_time, timestamp)
            if first_time is not None and last_time is not None:
                counts[path] = (last_time - first_time) // 100_000_000 + 1
        return counts

    baseline = record_counts(low_csv)
    if not baseline:
        raise SystemExit(f"ERROR: no vehicle records in low-density reference {low_csv}")
    target_records = sum(baseline.values())
    available = record_counts(current_csv)
    if not available:
        raise SystemExit(f"ERROR: no vehicle records available to balance in {current_csv}")

    rng = random.Random(f"{args.seed}|{args.density}")
    items = list(available.items())
    rng.shuffle(items)
    limit = target_records + max(count for _, count in items)
    states = {0: ()}
    for path, count in items:
        for total, selected in list(states.items()):
            new_total = total + count
            if new_total <= limit and new_total not in states:
                states[new_total] = selected + (path,)

    candidate_totals = [total for total in states if total > 0]
    best_total = min(candidate_totals, key=lambda total: abs(total - target_records))
    selected = set(states[best_total])
    omitted = [path for path in available if path not in selected]

    archive = run_dir / "unselected_vehicle_logs"
    if omitted:
        archive.mkdir(parents=True, exist_ok=True)
        for path in omitted:
            shutil.move(str(path), str(archive / path.name))

    summary = {
        "method": "seeded whole-vehicle subset closest to low-density record count",
        "seed": args.seed,
        "density": args.density,
        "target_records": target_records,
        "available_records": sum(available.values()),
        "retained_records": sum(available[path] for path in selected),
        "retained_vehicles": len(selected),
        "available_vehicles": len(available),
        "unselected_vehicles": [
            path.stem.removesuffix("_veh_output") for path in omitted
        ],
    }
    (run_dir / "record_balance.json").write_text(
        json.dumps(summary, indent=2) + "\n",
    )
    print(
        f"record balance: target={target_records} "
        f"available={sum(available.values())} "
        f"retained={sum(available[path] for path in selected)} "
        f"vehicles={len(selected)}/{len(available)}"
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    mapping = commands.add_parser("generate-mapping")
    mapping.add_argument("scenario")
    mapping.add_argument("application_class")
    mapping.add_argument("visibility", type=float)
    mapping.add_argument("density", choices=("low", "medium", "high"))
    mapping.add_argument("output")
    mapping.add_argument("arrival_window", type=float)
    mapping.add_argument("logging_radius", type=float)
    mapping.add_argument("speed_mps", type=float)
    mapping.add_argument("clearance", type=float)
    mapping.add_argument("calibration_dir")
    mapping.add_argument("low_calibration_dir")
    mapping.set_defaults(handler=generate_mapping)

    balancing = commands.add_parser("balance-vehicle-logs")
    balancing.add_argument("current_csv")
    balancing.add_argument("low_csv")
    balancing.add_argument("run_dir")
    balancing.add_argument("seed")
    balancing.add_argument("density", choices=("medium", "high"))
    balancing.set_defaults(handler=balance_vehicle_logs)

    args = parser.parse_args()
    args.handler(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
