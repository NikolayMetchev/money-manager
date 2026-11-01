#!/usr/bin/env python3
"""Startup benchmark for the desktop app, mm-jvm and native mm (#1254).

Each run gets a home of its own (HOME, USERPROFILE, APPDATA, LOCALAPPDATA, and -Duser.home for the JVM,
which otherwise reads the password database rather than HOME) holding a fresh copy of the benchmark
database as its last database, so a run never sees the real ~/.moneymanager and never sees another run's
changes. Results go to stdout, or --out, as JSON lines, one per (runtime, scenario, variant, metric):

    {"sha", "commit_date", "date", "os", "arch", "label", "runtime", "scenario", "variant", "metric",
     "median_ms", "min_ms", "max_ms", "runs"}

Variants: "cache" runs a launcher as shipped (with its AOT cache, when it has one); "nocache" adds
-XX:AOTMode=off. Native mm has no JVM, so it runs once, as "native".

The desktop app reports its milestones through the startup probe (MM_STARTUP_PROBE) and closes itself; on
Linux it needs a display (DISPLAY, e.g. Xvfb).
"""

import argparse
import datetime
import json
import os
import platform
import shutil
import statistics
import subprocess
import sys
import tempfile
import time

BENCH_ACCOUNT = "Alex Current Account"
VARIANT_FLAGS = {"cache": [], "nocache": ["-XX:AOTMode=off"]}
DESKTOP_TIMEOUT_S = 300
CLI_TIMEOUT_S = 120


def isolated_env(home, java_flags):
    env = dict(os.environ)
    for name in ("HOME", "USERPROFILE"):
        env[name] = home
    if os.name == "nt":
        env["APPDATA"] = os.path.join(home, "AppData", "Roaming")
        env["LOCALAPPDATA"] = os.path.join(home, "AppData", "Local")
        os.makedirs(env["APPDATA"], exist_ok=True)
        os.makedirs(env["LOCALAPPDATA"], exist_ok=True)
    env["JAVA_TOOL_OPTIONS"] = " ".join([f"-Duser.home={home}"] + java_flags)
    for name in ("MM_STARTUP_PROBE", "MM_STARTUP_PROBE_ACCOUNT", "MM_VAULT_PASSWORD", "MM_REMOTE_PASSWORD"):
        env.pop(name, None)
    return env


def make_home(root, db):
    home = tempfile.mkdtemp(prefix="home-", dir=root)
    data = os.path.join(home, ".moneymanager")
    os.makedirs(data)
    copy = os.path.join(data, "bench.db")
    shutil.copyfile(db, copy)
    with open(os.path.join(data, "settings.json"), "w", encoding="utf-8") as f:
        json.dump({"lastDatabase": copy}, f)
    return home


def time_cli(launcher, args, env, cwd):
    start = time.perf_counter()
    result = subprocess.run(
        [launcher, *args],
        env=env,
        cwd=cwd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=CLI_TIMEOUT_S,
    )
    elapsed = (time.perf_counter() - start) * 1000
    if result.returncode != 0:
        raise RuntimeError(f"{launcher} {' '.join(args)} exited {result.returncode}:\n{result.stderr.decode(errors='replace')}")
    return {"wall": elapsed}


def time_desktop(launcher, account, env, cwd):
    probe = os.path.join(cwd, "probe.json")
    env = dict(env, MM_STARTUP_PROBE=probe)
    if account:
        env["MM_STARTUP_PROBE_ACCOUNT"] = account
    start = time.perf_counter()
    result = subprocess.run(
        [launcher],
        env=env,
        cwd=cwd,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=DESKTOP_TIMEOUT_S,
    )
    elapsed = (time.perf_counter() - start) * 1000
    if not os.path.exists(probe):
        raise RuntimeError(f"{launcher} exited {result.returncode} without writing the probe:\n{result.stdout.decode(errors='replace')[-4000:]}")
    with open(probe, encoding="utf-8") as f:
        metrics = {name: float(ms) for name, ms in json.load(f).items()}
    metrics["wall"] = elapsed
    return metrics


def scenarios(args):
    """(runtime, scenario, variants, database, measure(env, cwd) -> {metric: ms})."""
    out = []
    dbs = [("bench", args.bench_db)] + ([("empty", args.empty_db)] if args.empty_db else [])
    for db_name, db in dbs:
        account_args = ["--account", f"^{BENCH_ACCOUNT}$"]
        cli = [("accounts", ["--format", "json", "accounts"])]
        if db_name == "bench":
            cli.append(("transactions", ["--format", "json", "transactions", *account_args]))
        for runtime, launcher, variants in (("mm-jvm", args.mm_jvm, args.variants), ("mm", args.mm, ["native"])):
            if not launcher:
                continue
            for scenario, cli_args in cli:
                out.append((runtime, f"{scenario}-{db_name}", variants, db, lambda env, cwd, l=launcher, a=cli_args: time_cli(l, a, env, cwd)))
        if args.desktop:
            account = BENCH_ACCOUNT if db_name == "bench" else None
            out.append(("desktop", f"startup-{db_name}", args.variants, db, lambda env, cwd, a=account: time_desktop(args.desktop, a, env, cwd)))
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--bench-db", required=True, help="the benchmark database (//tools/bench-db:bench_db)")
    parser.add_argument("--empty-db", help="the empty database (//tools/bench-db:empty_db), for the floor")
    parser.add_argument("--desktop", help="the desktop app's launcher")
    parser.add_argument("--mm-jvm", help="mm-jvm's launcher")
    parser.add_argument("--mm", help="native mm")
    parser.add_argument("--runs", type=int, default=5, help="measured runs per scenario, after one discarded warm-up")
    parser.add_argument("--variants", default="cache,nocache", help="JVM variants, from: " + ", ".join(VARIANT_FLAGS))
    parser.add_argument("--sha", default=os.environ.get("GITHUB_SHA", ""))
    parser.add_argument("--commit-date", default="", help="when the commit was made (ISO 8601), to order the history")
    parser.add_argument("--label", default="", help="free text recorded with every result (e.g. the runner)")
    parser.add_argument("--out", help="append the JSON lines here instead of printing them")
    args = parser.parse_args()
    args.variants = [v for v in args.variants.split(",") if v]
    for variant in args.variants:
        if variant not in VARIANT_FLAGS:
            parser.error(f"unknown variant {variant}")

    date = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    os_name = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}.get(platform.system(), platform.system().lower())
    arch = platform.machine().lower()
    records = []
    root = tempfile.mkdtemp(prefix="mm-startup-bench-")
    try:
        for runtime, scenario, variants, db, measure in scenarios(args):
            for variant in variants:
                samples = {}
                for run in range(args.runs + 1):
                    home = make_home(root, db)
                    metrics = measure(isolated_env(home, VARIANT_FLAGS.get(variant, [])), home)
                    shutil.rmtree(home, ignore_errors=True)
                    if run == 0:
                        continue
                    for metric, ms in metrics.items():
                        samples.setdefault(metric, []).append(ms)
                for metric, values in samples.items():
                    record = {
                        "sha": args.sha,
                        "commit_date": args.commit_date,
                        "date": date,
                        "os": os_name,
                        "arch": arch,
                        "label": args.label,
                        "runtime": runtime,
                        "scenario": scenario,
                        "variant": variant,
                        "metric": metric,
                        "median_ms": round(statistics.median(values), 1),
                        "min_ms": round(min(values), 1),
                        "max_ms": round(max(values), 1),
                        "runs": len(values),
                    }
                    records.append(record)
                    print(f"{runtime:8} {scenario:20} {variant:8} {metric:18} median {record['median_ms']:8.0f} ms  "
                          f"(min {record['min_ms']:.0f}, max {record['max_ms']:.0f})", file=sys.stderr)
    finally:
        shutil.rmtree(root, ignore_errors=True)
    lines = "".join(json.dumps(r, sort_keys=True) + "\n" for r in records)
    if args.out:
        with open(args.out, "a", encoding="utf-8") as f:
            f.write(lines)
    else:
        sys.stdout.write(lines)


if __name__ == "__main__":
    main()
