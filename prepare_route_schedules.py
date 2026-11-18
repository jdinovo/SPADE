#!/usr/bin/env python3
"""Precompute route distances and deterministic density route lists."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import sqlite3
from pathlib import Path


SCENARIO_ROOT = Path(__file__).resolve().parent / "scenarios"
OUTPUT_NAME = "route_schedule.json"
DENSITY_COUNTS = {
    "low": (6, 8),
    "medium": (13, 17),
    "high": (27, 33),
}


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_info(scenario: Path) -> dict[str, str]:
    routes_file = scenario / "mapping" / "routes.txt"
    databases = list((scenario / "application").glob("*.db"))
    if len(databases) != 1:
        raise ValueError(
            f"expected one route database in {scenario}/application, "
            f"found {len(databases)}"
        )
    database_path = databases[0]
    return {
        "routes_sha256": file_hash(routes_file),
        "database": database_path.name,
        "database_sha256": file_hash(database_path),
    }


def select_routes(
    routes: list[dict[str, str | float]], count: int, rng: random.Random,
) -> list[dict[str, str | float]]:
    by_approach: dict[str, dict[str, list[dict[str, str | float]]]] = {}
    for route in routes:
        by_approach.setdefault(route["approach"], {}).setdefault(
            route["start_node"], [],
        ).append(route)

    approaches = list(by_approach)
    rng.shuffle(approaches)
    for start_groups in by_approach.values():
        for candidates in start_groups.values():
            rng.shuffle(candidates)

    selected = []
    selected_starts = {approach: set() for approach in approaches}
    selected_ids = set()
    while len(selected) < count:
        added = False
        for approach in approaches:
            candidates_by_start = {
                start: [
                    route for route in candidates
                    if route["route_id"] not in selected_ids
                ]
                for start, candidates in by_approach[approach].items()
            }
            candidates_by_start = {
                start: candidates
                for start, candidates in candidates_by_start.items()
                if candidates
            }
            if not candidates_by_start:
                continue
            unique_starts = [
                start for start in candidates_by_start
                if start not in selected_starts[approach]
            ]
            start = rng.choice(unique_starts or list(candidates_by_start))
            route = rng.choice(candidates_by_start[start])
            selected.append(route)
            selected_starts[approach].add(start)
            selected_ids.add(route["route_id"])
            added = True
            if len(selected) == count:
                break
        if not added:
            break

    remaining = [route for route in routes if route["route_id"] not in selected_ids]
    rng.shuffle(remaining)
    selected.extend(remaining[:count - len(selected)])
    if len(selected) != count:
        raise ValueError(f"could not select {count} distinct routes from {len(routes)}")
    return selected


def scenario_schedule(scenario: Path) -> dict:
    mapping = scenario / "mapping"
    routes_file = mapping / "routes.txt"
    routes_text = routes_file.read_text()
    listed_routes = list(dict.fromkeys(
        line.strip()
        for line in routes_text.splitlines()
        if re.fullmatch(r"\d+", line.strip())
    ))
    if not listed_routes:
        raise ValueError(f"no route IDs found in {routes_file}")

    targets = re.findall(r"node_id\s*=\s*'([^']+)'", routes_text)
    if len(targets) != 1:
        raise ValueError(f"expected one target intersection in {routes_file}")

    sources = source_info(scenario)
    database_path = scenario / "application" / sources["database"]

    routes = []
    with sqlite3.connect(database_path) as database:
        rows = database.execute("""
            WITH target_routes AS (
                SELECT Route.id, MIN(Route.sequence_number) AS target_sequence
                FROM Route
                JOIN ConnectionConsistsOf
                  ON ConnectionConsistsOf.connection_id = Route.connection_id
                WHERE ConnectionConsistsOf.node_id = ?
                GROUP BY Route.id
            )
            SELECT Route.id, Route.sequence_number, Connection.id,
                   Connection.length, ConnectionConsistsOf.node_id
            FROM Route
            JOIN target_routes ON target_routes.id = Route.id
                AND Route.sequence_number <= target_routes.target_sequence
            JOIN Connection ON Connection.id = Route.connection_id
            JOIN ConnectionConsistsOf
              ON ConnectionConsistsOf.connection_id = Connection.id
            ORDER BY Route.id, Route.sequence_number,
                     ConnectionConsistsOf.sequence_number
        """, (targets[0],))

        def append_route(route_id, connections):
            if not connections:
                return
            target_index = next(
                (index for index, connection in enumerate(connections)
                 if targets[0] in connection[3]),
                None,
            )
            if target_index is None:
                return
            distance = sum(connection[2] for connection in connections[:target_index])
            target_connection = connections[target_index]
            fraction = target_connection[3].index(targets[0]) / max(
                1, len(target_connection[3]) - 1,
            )
            distance += target_connection[2] * fraction
            approach_index = max(0, target_index - 1)
            routes.append({
                "route_id": route_id,
                "start_node": connections[0][3][0],
                "approach": connections[approach_index][1],
                "distance_m": distance,
            })

        current_route = None
        connections = []
        for route_id, route_sequence, connection_id, length, node_id in rows:
            route_id = str(route_id)
            if route_id != current_route:
                append_route(current_route, connections)
                current_route = route_id
                connections = []
            if not connections or connections[-1][0] != route_sequence:
                connections.append([route_sequence, connection_id, float(length), []])
            connections[-1][3].append(node_id)
        append_route(current_route, connections)

    if not routes:
        raise ValueError(f"no database routes pass target intersection {targets[0]}")

    distances = {route["route_id"]: route["distance_m"] for route in routes}
    density_routes = {}
    for density, count_range in DENSITY_COUNTS.items():
        rng = random.Random(f"{scenario.resolve()}|{density}")
        count = rng.randint(*count_range)
        selected = select_routes(routes, count, rng)
        density_routes[density] = {
            "vehicle_count": count,
            "routes": [route["route_id"] for route in selected],
            "start_nodes": [route["start_node"] for route in selected],
            "approaches": [route["approach"] for route in selected],
        }

    return {
        "schema_version": 2,
        "scenario": scenario.name,
        "target_node": targets[0],
        "sources": sources,
        "route_distances_m": distances,
        "route_start_nodes": {route["route_id"]: route["start_node"] for route in routes},
        "route_approaches": {route["route_id"]: route["approach"] for route in routes},
        "densities": density_routes,
    }


def selected_scenarios(names: list[str]) -> list[Path]:
    if names:
        scenarios = [SCENARIO_ROOT / name for name in names]
    else:
        scenarios = sorted(
            path.parent for path in SCENARIO_ROOT.glob("*/scenario_config.json")
            if (path.parent / "mapping" / "routes.txt").is_file()
        )
    for scenario in scenarios:
        if not (scenario / "mapping" / "routes.txt").is_file():
            raise ValueError(f"scenario route file not found: {scenario}/mapping/routes.txt")
    return scenarios


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check", action="store_true",
        help="validate existing schedules without rewriting them",
    )
    parser.add_argument("scenarios", nargs="*", help="scenario names; default: all")
    args = parser.parse_args()

    try:
        for scenario in selected_scenarios(args.scenarios):
            output = scenario / "mapping" / OUTPUT_NAME
            if args.check:
                if not output.is_file():
                    raise ValueError(
                        f"missing {output}; run python3 prepare_route_schedules.py"
                    )
                current = json.loads(output.read_text())
                if current.get("schema_version") != 2 \
                        or current.get("scenario") != scenario.name \
                        or current.get("sources") != source_info(scenario):
                    raise ValueError(
                        f"stale route schedule {output}; rerun "
                        "python3 prepare_route_schedules.py"
                    )
                print(f"valid {output}")
            else:
                expected = scenario_schedule(scenario)
                output.write_text(json.dumps(expected, indent=2) + "\n")
                print(
                    f"wrote {output}: "
                    + ", ".join(
                        f"{density}={data['vehicle_count']} routes"
                        for density, data in expected["densities"].items()
                    )
                )
    except (OSError, sqlite3.Error, ValueError, json.JSONDecodeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
