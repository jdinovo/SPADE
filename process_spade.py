#!/usr/bin/env python3
"""Convert raw MOSAIC vehicle logs into 10 Hz SPADE records."""

from __future__ import annotations

import argparse
import bisect
import csv
import hashlib
import json
import math
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent
INTERVAL_NS = 100_000_000
MESSAGE_TTL_SECONDS = 2.0
MESSAGE_TTL_NS = int(MESSAGE_TTL_SECONDS * 1_000_000_000)
FEATURES = [
    "vehicle_id", "route_id", "lane_id", "is_parked", "position", "heading",
    "speed", "throttle", "brake", "longitudinal_accel", "slope",
    "cam_vehicle_id", "cam_message_time", "cam_position", "cam_distance",
    "cam_vehicle_awareness", "tl_signal_id", "tl_incoming_lane", "tl_distance",
    "tl_angle_off_center", "tl_signal_red", "tl_signal_yellow", "tl_signal_green",
    "tl_camera_conf", "tl_cur_vis_score", "tl_conf_vis_weighted",
    "shared_vehicle_id", "shared_vehicle_pos", "shared_signal_perceptions",
    "map_message_time", "map_intersection_id", "map_lane_id", "map_lane_attributes",
    "map_lane_connections", "map_position", "spat_signal_id", "spat_message_time",
    "spat_signal_state", "spat_max_end_time", "spat_min_end_time",
]


class RunIdRemapper:
    """Create stable, run-scoped pseudonyms for entity identifiers."""

    def __init__(self, run_id: str) -> None:
        self.run_id = run_id.encode("utf-8")

    def map(self, entity_type: str, identifier: str) -> str:
        digest = hashlib.blake2b(digest_size=16, person=b"spade-id-v1")
        digest.update(self.run_id)
        digest.update(b"\0")
        digest.update(entity_type.encode("utf-8"))
        digest.update(b"\0")
        digest.update(identifier.encode("utf-8"))
        return f"{entity_type}_{digest.hexdigest()}"


def position(raw: str, longitude_first: bool = False) -> list[float]:
    parts = raw.split(":")
    if len(parts) != 2:
        raise ValueError(f"invalid position {raw!r}")
    first, second = map(float, parts)
    return [second, first] if longitude_first else [first, second]


def awareness(raw: str) -> dict[str, Any]:
    parts = raw.split(":")
    if len(parts) != 7:
        raise ValueError(f"expected seven CAM awareness fields, got {raw!r}")
    return {
        "vehicle_class": parts[0], "length": float(parts[1]), "width": float(parts[2]),
        "heading": float(parts[3]), "speed": float(parts[4]),
        "lane_index": int(parts[5]), "longitudinal_acceleration": float(parts[6]),
    }


def label_name(raw: str) -> str:
    labels = [part.strip().upper() for part in raw.split("|") if part.strip()]
    return "|".join("FALSE_STATE" if part == "TAMPER" else part for part in labels) or "BENIGN"


def load_labels(path: Path, attack_class: str) -> tuple[list[int], list[str]]:
    if not path.exists():
        raise ValueError(f"missing attack label file: {path}")
    entries = []
    with path.open(newline="", encoding="utf-8-sig") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames and {"simTimeNs", "activeAttacks"} <= set(reader.fieldnames):
            for line, row in enumerate(reader, 2):
                try:
                    entries.append((int(row["simTimeNs"]), label_name(row["activeAttacks"])))
                except (TypeError, ValueError) as error:
                    raise ValueError(f"{path}:{line}: invalid attack label row") from error
    if not entries and attack_class.upper() != "BENIGN":
        raise ValueError(f"{path} has no ground-truth labels for {attack_class}")
    entries.sort(key=lambda entry: entry[0])
    return [entry[0] for entry in entries], [entry[1] for entry in entries]


def load_vehicle_log(
    path: Path,
) -> tuple[dict[int, dict[str, Any]], list[tuple[int, str, dict[str, Any]]]]:
    events: list[tuple[int, str, dict[str, Any]]] = []
    vehicle_rows: dict[int, dict[str, Any]] = {}
    with path.open(newline="", encoding="utf-8-sig") as source:
        for line, row in enumerate(csv.reader(source), 1):
            if not row:
                continue
            kind = row[0]
            try:
                if kind == "VEHICLE_DATA":
                    if len(row) != 13:
                        raise ValueError("expected 13 fields")
                    time = int(row[1])
                    vehicle_rows[time] = {
                        "vehicle_id": row[2], "route_id": row[3], "lane_id": row[4],
                        "is_parked": row[5].lower() == "true", "position": position(row[6]),
                        "heading": float(row[7]), "speed": float(row[8]),
                        "throttle": float(row[9]), "brake": float(row[10]),
                        "longitudinal_accel": float(row[11]), "slope": float(row[12]),
                        "time": time,
                    }
                elif kind == "MESSAGE_RECEIVED":
                    if len(row) != 6:
                        raise ValueError("expected 6 fields")
                    events.append((int(row[1]), "MESSAGE", {
                        "message_type": row[2],
                        "message_id": row[3],
                        "is_malicious": row[4].lower() == "true",
                        "attack_type": row[5],
                    }))
                elif kind == "CAM_RECEIVED":
                    if len(row) != 7:
                        raise ValueError("expected 7 fields")
                    events.append((int(row[1]), "CAM", {
                        "vehicle_id": row[2], "message_time": int(row[3]),
                        "position": position(row[4], True), "distance": float(row[5]),
                        "vehicle_awareness": awareness(row[6]),
                    }))
                elif kind == "TL_PERCEPTION":
                    if len(row) != 12:
                        raise ValueError("expected 12 fields")
                    events.append((int(row[1]), "TL", {
                        "signal_id": row[2], "incoming_lane": row[3],
                        "distance": float(row[4]), "angle_off_center": float(row[5]),
                        "signal_red": int(row[6]), "signal_yellow": int(row[7]),
                        "signal_green": int(row[8]), "camera_conf": float(row[9]),
                        "cur_vis_score": float(row[10]), "conf_vis_weighted": float(row[11]),
                    }))
                elif kind == "SHARED_TL_PERCEPTION":
                    if len(row) != 14:
                        raise ValueError("expected 14 fields")
                    events.append((int(row[1]), "SHARED", {
                        "vehicle_id": row[2], "vehicle_pos": position(row[3]),
                        "signal_id": row[4], "incoming_lane": row[5],
                        "distance": float(row[6]), "angle_off_center": float(row[7]),
                        "signal_red": int(row[8]), "signal_yellow": int(row[9]),
                        "signal_green": int(row[10]), "camera_conf": float(row[11]),
                        "cur_vis_score": float(row[12]), "conf_vis_weighted": float(row[13]),
                    }))
                elif kind == "SPAT_RECEIVED":
                    if len(row) != 7:
                        raise ValueError("expected 7 fields")
                    events.append((int(row[1]), "SPAT", {
                        "signal_id": row[2], "message_time": int(row[3]),
                        "signal_state": row[4], "max_end_time": int(row[5]),
                        "min_end_time": int(row[6]),
                    }))
                elif kind == "MAP_LANE_RECEIVED":
                    if len(row) != 8:
                        raise ValueError("expected 8 fields")
                    events.append((int(row[1]), "MAP", {
                        "message_time": int(row[2]), "intersection_id": row[3],
                        "lane_id": row[4], "lane_attributes": row[5],
                        "lane_connections": row[6], "position": position(row[7]),
                    }))
            except (IndexError, ValueError) as error:
                raise ValueError(f"{path}:{line}: invalid {kind} row: {error}") from error
    events.sort(key=lambda event: event[0])
    return vehicle_rows, events


def shared_features(
    reports: list[dict[str, Any]],
) -> tuple[list[str], list[list[float]], list[dict[str, Any]], int]:
    peers: dict[str, list[float]] = {}
    grouped: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for report in reports:
        peers.setdefault(report["vehicle_id"], report["vehicle_pos"])
        grouped[(report["signal_id"], report["incoming_lane"])].append(report)
    perceptions = []
    for (signal_id, lane), rows in sorted(grouped.items()):
        counts = {
            "RED": sum(item["signal_red"] for item in rows),
            "YELLOW": sum(item["signal_yellow"] for item in rows),
            "GREEN": sum(item["signal_green"] for item in rows),
        }
        phase = max(("RED", "YELLOW", "GREEN"), key=lambda item: counts[item])

        def mean(key: str) -> float:
            return sum(item[key] for item in rows) / len(rows)

        perceptions.append({
            "signal_id": signal_id, "incoming_lane": lane,
            "distance": mean("distance"), "angle_off_center": mean("angle_off_center"),
            "signal_state": phase, "signal_red": int(phase == "RED"),
            "signal_yellow": int(phase == "YELLOW"), "signal_green": int(phase == "GREEN"),
            "camera_conf": mean("camera_conf"), "cur_vis_score": mean("cur_vis_score"),
            "conf_vis_weighted": mean("conf_vis_weighted"), "report_count": len(rows),
        })
    return list(peers), list(peers.values()), perceptions, len(peers)


def remap_lane_connections(raw: str, identifiers: RunIdRemapper) -> str:
    connections = []
    for connection in raw.split(";"):
        signal_id, separator, lane_id = connection.partition(":")
        if separator:
            connection = f"{identifiers.map('light', signal_id)}:{lane_id}"
        connections.append(connection)
    return ";".join(connections)


def build_record(
    run_id: str,
    timestamp: int,
    vehicle: dict[str, Any],
    current: dict[str, Any],
    label: str,
    map_snapshot: list[dict[str, Any]],
    identifiers: RunIdRemapper,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "run_id": run_id,
        "simulation_time_ns": timestamp,
        "label": label,
        "received_messages": current["received_messages"],
        **{key: value for key, value in vehicle.items() if key != "time"},
    }
    row["vehicle_id"] = identifiers.map("vehicle", vehicle["vehicle_id"])

    cams = current["cams"]
    row.update({
        "cam_vehicle_id": [identifiers.map("vehicle", item["vehicle_id"]) for item in cams],
        "cam_message_time": [item["message_time"] for item in cams],
        "cam_position": [item["position"] for item in cams],
        "cam_distance": [item["distance"] for item in cams],
        "cam_vehicle_awareness": [item["vehicle_awareness"] for item in cams],
    })

    tl = current.get("tl")
    for column, field in zip(FEATURES[16:26], (
        "signal_id", "incoming_lane", "distance", "angle_off_center", "signal_red",
        "signal_yellow", "signal_green", "camera_conf", "cur_vis_score",
        "conf_vis_weighted",
    )):
        row[column] = tl[field] if tl else ""
    if tl:
        row["tl_signal_id"] = identifiers.map("light", tl["signal_id"])

    peer_ids, peer_positions, perceptions, peer_count = shared_features(current["shared"])
    peer_ids = [identifiers.map("vehicle", peer_id) for peer_id in peer_ids]
    for perception in perceptions:
        perception["signal_id"] = identifiers.map("light", perception["signal_id"])
    row.update({
        "peer_count": peer_count,
        "shared_vehicle_id": peer_ids if peer_count else 0,
        "shared_vehicle_pos": peer_positions if peer_count else 0,
        "shared_signal_perceptions": perceptions if peer_count else 0,
    })

    for column, field in zip(FEATURES[29:35], (
        "message_time", "intersection_id", "lane_id", "lane_attributes",
        "lane_connections", "position",
    )):
        if field == "intersection_id":
            row[column] = [identifiers.map("rsu", item[field]) for item in map_snapshot]
        elif field == "lane_connections":
            row[column] = [remap_lane_connections(item[field], identifiers) for item in map_snapshot]
        else:
            row[column] = [item[field] for item in map_snapshot]
    for column, field in zip(FEATURES[35:40], (
        "signal_id", "message_time", "signal_state", "max_end_time", "min_end_time",
    )):
        row[column] = [item[field] for item in current["spats"]]
    row["spat_signal_id"] = [
        identifiers.map("light", item["signal_id"]) for item in current["spats"]
    ]
    return row


def process_scenario(
    scenario: Path, input_root: Path, output_root: Path,
    message_ttl_ns: int = MESSAGE_TTL_NS,
) -> tuple[int, int, int]:
    attack_class = scenario.parent.parent.parent.name
    label_times, label_values = load_labels(scenario / "attack_labels.csv", attack_class)
    run_id = scenario.relative_to(input_root).parent.as_posix()
    identifiers = RunIdRemapper(run_id)
    logs = sorted(scenario.glob("*_veh_output.csv"))
    if not logs:
        raise ValueError(f"no vehicle output CSV files found in {scenario}")

    run_output = output_root.joinpath(*scenario.relative_to(input_root).parent.parts)
    run_output.mkdir(parents=True, exist_ok=True)
    count = 0
    benign_count = 0
    attack_count = 0
    written_vehicles: set[str] = set()
    for log in logs:
        vehicles, events = load_vehicle_log(log)
        vehicle_times = sorted(vehicles)
        if not vehicle_times:
            continue
        vehicle_ids = {vehicle["vehicle_id"] for vehicle in vehicles.values()}
        if len(vehicle_ids) != 1:
            raise ValueError(f"{log} contains records for multiple vehicles")
        vehicle_id = vehicle_ids.pop()
        if Path(vehicle_id).name != vehicle_id or vehicle_id in {"", ".", ".."}:
            raise ValueError(f"invalid vehicle ID {vehicle_id!r} in {log}")
        if vehicle_id in written_vehicles:
            raise ValueError(f"duplicate vehicle ID {vehicle_id!r} in {scenario}")
        written_vehicles.add(vehicle_id)

        cam_latest: dict[str, dict[str, Any]] = {}
        shared_latest: dict[str, dict[str, Any]] = {}
        spat_latest: dict[str, dict[str, Any]] = {}
        map_latest: dict[tuple[str, str], dict[str, Any]] = {}
        tl_latest = None
        vehicle_index = 0
        event_index = 0
        tick = vehicle_times[0]
        last_tick = vehicle_times[-1]
        current_vehicle = vehicles[vehicle_times[0]]
        records: list[dict[str, Any]] = []

        while tick <= last_tick:
            tick_messages = []
            while vehicle_index < len(vehicle_times) and vehicle_times[vehicle_index] <= tick:
                current_vehicle = vehicles[vehicle_times[vehicle_index]]
                vehicle_index += 1
            shared_events: dict[tuple[int, str], dict[tuple[str, str], dict[str, Any]]] = {}
            while event_index < len(events) and events[event_index][0] <= tick:
                event_time, kind, message = events[event_index]
                event_index += 1
                if kind == "MESSAGE":
                    tick_messages.append(message)
                elif kind == "CAM":
                    cam_latest[message["vehicle_id"]] = {
                        **message, "_received_at_ns": event_time,
                    }
                elif kind == "TL":
                    tl_latest = message
                elif kind == "SHARED":
                    sender = message["vehicle_id"]
                    report_key = (message["signal_id"], message["incoming_lane"])
                    shared_events.setdefault((event_time, sender), {})[report_key] = message
                elif kind == "SPAT":
                    spat_latest[message["signal_id"]] = message
                elif kind == "MAP":
                    key = (message["intersection_id"], message["lane_id"])
                    map_latest[key] = message

            time = tick
            for (received_at, sender), reports in shared_events.items():
                shared_latest[sender] = {
                    "received_at_ns": received_at,
                    "reports": reports,
                }
            cam_latest = {
                sender: message for sender, message in cam_latest.items()
                if time - message["_received_at_ns"] <= message_ttl_ns
            }
            shared_latest = {
                sender: snapshot for sender, snapshot in shared_latest.items()
                if time - snapshot["received_at_ns"] <= message_ttl_ns
            }
            label_index = bisect.bisect_right(label_times, time) - 1
            label = label_values[label_index] if label_index >= 0 else "BENIGN"
            if label == "BENIGN":
                benign_count += 1
            else:
                attack_count += 1
            current = {
                "cams": [
                    {key: value for key, value in cam_latest[sender].items()
                     if key != "_received_at_ns"}
                    for sender in sorted(cam_latest)
                ],
                "tl": tl_latest,
                "shared": [
                    report
                    for sender in sorted(shared_latest)
                    for _, report in sorted(shared_latest[sender]["reports"].items())
                ],
                "spats": [spat_latest[key] for key in sorted(spat_latest)],
                "received_messages": tick_messages,
            }
            map_snapshot = [map_latest[key] for key in sorted(map_latest)]
            records.append(build_record(
                run_id, time, current_vehicle, current, label, map_snapshot, identifiers,
            ))
            count += 1
            tick += INTERVAL_NS

        output_file = run_output / f"{identifiers.map('vehicle', vehicle_id)}.json"
        with output_file.open("w", encoding="utf-8") as destination:
            json.dump(records, destination, separators=(",", ":"), ensure_ascii=True)
            destination.write("\n")
    return count, benign_count, attack_count


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Build 10 Hz, vehicle-centered SPADE JSON records from MOSAIC CSV logs."
    )
    parser.add_argument("--input", type=Path, default=ROOT / "data",
                        help="root directory containing generated scenario runs")
    parser.add_argument("--output", type=Path, default=ROOT / "processed" / "spade_records",
                        help="output directory for per-run, per-vehicle JSON files")
    parser.add_argument(
        "--split-tolerance", type=float, default=0.05, metavar="FRACTION",
        help="allowed deviation from 20%% attack records in attack runs (default: 0.05)",
    )
    parser.add_argument(
        "--strict-split", action="store_true",
        help="return a failure status if any run is outside the split tolerance",
    )
    parser.add_argument(
        "--message-ttl-seconds", type=float, default=MESSAGE_TTL_SECONDS, metavar="SECONDS",
        help=f"discard CAM and shared-perception snapshots older than this (default: {MESSAGE_TTL_SECONDS:g}s)",
    )
    args = parser.parse_args()
    input_root = args.input.expanduser().resolve()
    output = args.output.expanduser().resolve()
    if not input_root.is_dir():
        parser.error(f"input directory does not exist: {input_root}")
    scenarios = sorted(path.parent for path in input_root.rglob("attack_labels.csv"))
    if not scenarios:
        parser.error(f"no attack_labels.csv files found under {input_root}")
    if not math.isfinite(args.split_tolerance) or not 0 <= args.split_tolerance < 0.2:
        parser.error("--split-tolerance must be a finite value in [0, 0.2)")
    if not math.isfinite(args.message_ttl_seconds) or args.message_ttl_seconds < 0:
        parser.error("--message-ttl-seconds must be a finite non-negative value")
    if output == input_root or input_root in output.parents:
        parser.error("output directory must be outside the input directory")
    if output.exists() and not output.is_dir():
        parser.error(f"output path is not a directory: {output}")
    output.mkdir(parents=True, exist_ok=True)

    total = 0
    split_failures = 0
    message_ttl_ns = int(args.message_ttl_seconds * 1_000_000_000)
    try:
        for scenario in scenarios:
            written, benign, attack = process_scenario(
                scenario, input_root, output, message_ttl_ns,
            )
            total += written
            attack_share = attack / written if written else 0.0
            is_benign_run = scenario.parent.parent.parent.name.upper() == "BENIGN"
            if is_benign_run:
                in_range = written > 0 and attack == 0
            else:
                in_range = written > 0 and abs(attack_share - 0.2) <= args.split_tolerance
            print(
                f"{scenario}: {written} records; benign={benign}, attack={attack} "
                f"({attack_share:.1%} attack)",
                file=sys.stderr,
            )
            if not in_range:
                split_failures += 1
                expected = "0% attack for BENIGN runs" if is_benign_run else (
                    f"20% +/- {args.split_tolerance:.0%} attack"
                )
                print(f"warning: expected {expected}; observed {attack_share:.1%}", file=sys.stderr)
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(f"Wrote {total} records to {output}", file=sys.stderr)
    if args.strict_split and split_failures:
        print(f"error: {split_failures} scenario(s) failed the label-split check", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
