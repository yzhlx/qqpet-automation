"""Read-only summary of persisted plan runs and the admission pending gate.

Mirrors `PlanRunPendingGate.pendingRunIds` / `blockingRunId` from
`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PlanDeviceExecution.kt`
and reports whether a new PLAN run would currently be admitted. Never writes to
the device; a record that cannot be decoded is reported as an error rather than
skipped, because `PlanRunPendingGate` itself throws `RUN_STORE_RECORD_MISSING`.

Usage:
    python -I tools/diagnostics/read_plan_runs.py                 # device
    python -I tools/diagnostics/read_plan_runs.py --dir <local>   # pulled copy
"""
import argparse
import json
import subprocess
import sys
from pathlib import Path

TERMINAL = {"COMPLETED", "STOPPED", "FAILED"}
# PlanRunPendingGate treats these as non-terminal, i.e. blocking.
NON_TERMINAL = {"CREATED", "PREFLIGHT", "RUNNING", "UNCONFIRMED"}


def adb_cat(serial: str, app: str, path: str) -> bytes:
    adb = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
    result = subprocess.run(
        [str(adb), "-s", serial, "exec-out", "run-as", app, "cat", path],
        capture_output=True, timeout=60,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace").strip())
    return result.stdout


def adb_list(serial: str, app: str, directory: str) -> list:
    adb = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
    result = subprocess.run(
        [str(adb), "-s", serial, "exec-out", "run-as", app, "ls", directory],
        capture_output=True, timeout=60,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", "replace").strip())
    names = [n for n in result.stdout.decode("utf-8", "replace").split() if n.endswith(".json")]
    return sorted(names)


def summarize(document: dict) -> dict:
    snapshot = document.get("snapshot") or {}
    attempts = document.get("attempts") or []
    state = snapshot.get("state")
    pending = [a for a in attempts if a.get("inputPending")]
    return {
        "runId": snapshot.get("runId"),
        "state": state,
        "stopReason": snapshot.get("stopReason"),
        "counts": snapshot.get("counts"),
        "origin": (snapshot.get("request") or {}).get("origin"),
        "displayId": (snapshot.get("request") or {}).get("displayId"),
        "profile": ((snapshot.get("request") or {}).get("planSnapshot") or {}).get("executionProfile"),
        "planSteps": [
            {"type": s.get("type"), "state": s.get("state"), "reason": s.get("reason")}
            for s in snapshot.get("steps", [])
        ],
        "attemptCount": len(attempts),
        "pendingInputAttempts": [a.get("attemptId") for a in pending],
        # PlanRunPendingGate: non-terminal state OR any pending input blocks admission.
        "blocking": state in NON_TERMINAL or bool(pending),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="DKS9K23420020242")
    parser.add_argument("--app", default="com.ynk.virtualdisplay")
    parser.add_argument("--dir", help="Read a locally pulled plan-runs directory instead of the device")
    args = parser.parse_args()

    runs, errors = [], []
    if args.dir:
        names = sorted(p.name for p in Path(args.dir).glob("*.json"))
        for name in names:
            raw = (Path(args.dir) / name).read_bytes()
            try:
                runs.append(summarize(json.loads(raw.decode("utf-8"))))
            except (json.JSONDecodeError, KeyError) as exc:
                errors.append(f"{name}: {exc}")
    else:
        for name in adb_list(args.serial, args.app, "files/plan-runs"):
            run_id = name[: -len(".json")]
            try:
                raw = adb_cat(args.serial, args.app, f"files/plan-runs/{name}")
                runs.append(summarize(json.loads(raw.decode("utf-8"))))
            except (RuntimeError, json.JSONDecodeError, KeyError) as exc:
                # Fail closed like RUN_STORE_RECORD_MISSING: an unreadable record is not "no blocker".
                errors.append(f"{run_id}: {exc}")

    blocking = [r["runId"] for r in runs if r["blocking"]]
    print(json.dumps({
        "runCount": len(runs),
        "runs": runs,
        "blockingRunIds": blocking,
        # blockingRunId() returns the first by filename order; None means admission is not blocked.
        "blockingRunId": blocking[0] if blocking else None,
        "decodeErrors": errors,
        "admissionBlockedByPlanRuns": bool(blocking) or bool(errors),
    }, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
