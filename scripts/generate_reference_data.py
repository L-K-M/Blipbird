#!/usr/bin/env python3
"""Generate Blipbird's bundled reference dataset (PLAN.md §4.3).

Downloads pinned-URL open datasets, filters them to what the app needs, and
writes compact CSV assets plus a data-sources.lock.json recording provenance
(URL, sha256, fetch date, license, row counts). Release builds consume the
committed assets; rerun this script to refresh them.

Sources:
  - OurAirports airports.csv        (Public Domain)  airport identity/coords
  - mwgg/Airports airports.json     (MIT)            IANA timezone per ICAO
  - OpenTravelData optd_airlines.csv (CC BY 4.0)     airline names/codes

Note: PLAN.md prefers a timezone-boundary-builder spatial join for tz
provenance; mwgg/Airports is used for v0.1 and recorded as an open decision.
"""
import csv
import hashlib
import io
import json
import os
import sys
import urllib.request
from datetime import date

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "reference")

SOURCES = {
    "ourairports": {
        "url": "https://davidmegginson.github.io/ourairports-data/airports.csv",
        "license": "Public Domain (Unlicense)",
    },
    "mwgg_airports": {
        "url": "https://raw.githubusercontent.com/mwgg/Airports/master/airports.json",
        "license": "MIT",
    },
    "optd_airlines": {
        "url": "https://raw.githubusercontent.com/opentraveldata/opentraveldata/master/opentraveldata/optd_airlines.csv",
        "license": "CC BY 4.0 (changes: filtered to active carriers with IATA or ICAO codes)",
    },
}


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "Blipbird-refdata/0.1 (+https://github.com/L-K-M/Blipbird)"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    lock = {"generated": date.today().isoformat(), "sources": {}}

    raw = {}
    for key, s in SOURCES.items():
        data = fetch(s["url"])
        raw[key] = data
        lock["sources"][key] = {
            "url": s["url"],
            "license": s["license"],
            "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
        }
        print(f"fetched {key}: {len(data):,} bytes")

    # Timezones keyed by ICAO
    tz_by_icao = {}
    for icao, rec in json.loads(raw["mwgg_airports"].decode("utf-8")).items():
        tz = rec.get("tz")
        if tz:
            tz_by_icao[icao.upper()] = tz

    # Airports: keep IATA-coded, non-closed airports
    airports_out = []
    reader = csv.DictReader(io.StringIO(raw["ourairports"].decode("utf-8")))
    for row in reader:
        iata = (row.get("iata_code") or "").strip().upper()
        typ = row.get("type") or ""
        if len(iata) != 3 or typ == "closed_airport":
            continue
        icao = ((row.get("icao_code") or "").strip() or (row.get("gps_code") or "").strip()).upper()
        tz = tz_by_icao.get(icao, "")
        airports_out.append([
            icao, iata,
            (row.get("name") or "").strip(),
            (row.get("municipality") or "").strip(),
            (row.get("iso_country") or "").strip(),
            row.get("latitude_deg") or "",
            row.get("longitude_deg") or "",
            tz,
        ])
    airports_out.sort(key=lambda r: r[1])

    with open(os.path.join(OUT, "airports.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["icao", "iata", "name", "city", "country", "lat", "lon", "tz"])
        w.writerows(airports_out)
    lock["sources"]["ourairports"]["rows_kept"] = len(airports_out)
    tz_missing = sum(1 for r in airports_out if not r[7])
    lock["sources"]["mwgg_airports"]["airports_without_tz"] = tz_missing

    # Airlines: OPTD, active rows (validity_to empty), with at least one code
    airlines_out = []
    reader = csv.DictReader(io.StringIO(raw["optd_airlines"].decode("utf-8")), delimiter="^")
    for row in reader:
        if (row.get("validity_to") or "").strip():
            continue  # defunct
        icao = (row.get("3char_code") or "").strip().upper()
        iata = (row.get("2char_code") or "").strip().upper()
        name = (row.get("name") or "").strip()
        if not name or (len(icao) != 3 and len(iata) != 2):
            continue
        airlines_out.append([icao, iata, name])
    airlines_out.sort(key=lambda r: (r[1] or "ZZ", r[0]))

    with open(os.path.join(OUT, "airlines.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["icao", "iata", "name"])
        w.writerows(airlines_out)
    lock["sources"]["optd_airlines"]["rows_kept"] = len(airlines_out)

    with open(os.path.join(OUT, "data-sources.lock.json"), "w", encoding="utf-8") as f:
        json.dump(lock, f, indent=2)

    print(f"airports: {len(airports_out)} rows ({tz_missing} without tz)")
    print(f"airlines: {len(airlines_out)} rows")


if __name__ == "__main__":
    sys.exit(main())
