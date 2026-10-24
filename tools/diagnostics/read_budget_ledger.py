"""Read-only parser for the authoritative minute budget journal.

Decodes `files/pet-minute-budget-v2.bin` exactly as
`VirtualDisplay/app/src/main/java/com/ynk/virtualdisplay/automation/PersistentBudgetStore.kt`
writes it, and prints JSON. It NEVER writes to the device and never repairs a
malformed record: a bad magic, version, count or trailing byte is a hard error,
because silently falling back to an empty budget is what would let acceptance
claim minutes that were actually spent.

Usage:
    python -I tools/diagnostics/read_budget_ledger.py            # pull from device
    python -I tools/diagnostics/read_budget_ledger.py --file X   # parse a local copy
"""
import argparse
import io
import json
import struct
import subprocess
import sys
from pathlib import Path

MAGIC = "QQPET_BUDGET"
VERSION = 2
MAX_RECORDS = 1_000_000

# ReserveStatus in PlanBudgetLedger.kt. An unknown name is a hard error on purpose:
# the production reader does ReserveStatus.valueOf and fails closed too.
STATUSES = {
    "RESERVED_BEFORE_INPUT",
    "START_CONFIRMED",
    "COMPLETED",
    "UNCONFIRMED_RETAINED",
    "RECONCILED_STOPPED",
}

# CarePendingReconciliation.unresolved(): anything outside these two is pending and
# blocks admission of a new run.
RESOLVED = {"COMPLETED", "RECONCILED_STOPPED"}


class BudgetFormatError(Exception):
    """The record is not a well-formed v2 budget document."""


class _Reader:
    """Java DataInputStream semantics over an in-memory buffer."""

    def __init__(self, data: bytes):
        self._buf = io.BytesIO(data)
        self._size = len(data)

    def _take(self, n: int, what: str) -> bytes:
        chunk = self._buf.read(n)
        if len(chunk) != n:
            raise BudgetFormatError(f"BUDGET_TRUNCATED:{what}")
        return chunk

    def read_int(self, what: str) -> int:
        return struct.unpack(">i", self._take(4, what))[0]

    def read_boolean(self, what: str) -> bool:
        return self._take(1, what)[0] != 0

    def read_utf(self, what: str) -> str:
        # DataOutput.writeUTF: 2-byte length prefix, then modified UTF-8.
        length = struct.unpack(">H", self._take(2, what + ":length"))[0]
        raw = self._take(length, what)
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise BudgetFormatError(f"BUDGET_UTF_INVALID:{what}") from exc

    def at_end(self) -> bool:
        return self._buf.tell() == self._size

    def read_count(self, what: str) -> int:
        count = self.read_int(what)
        if not 0 <= count <= MAX_RECORDS:
            raise BudgetFormatError(f"BUDGET_RECORD_COUNT_INVALID:{what}={count}")
        return count


def used_for_day(legacy_minutes: int, attempts: dict, day: str) -> int:
    """Mirrors PlanBudgetLedger.used(state, day): legacy minutes for that day + keyed attempts of that day."""
    return legacy_minutes + sum(a["minutes"] for a in attempts.values() if a["day"] == day)


def legacy_for_day(day: str, stored_day: str, legacy_used: int, legacy_by_day: dict) -> int:
    if day == stored_day:
        return legacy_used
    return legacy_by_day.get(day, 0)


def device_today(serial: str) -> str:
    adb = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
    result = subprocess.run([str(adb), "-s", serial, "shell", "date", "+%Y-%m-%d"],
                            capture_output=True, timeout=30)
    if result.returncode:
        raise SystemExit("ADB_DATE_FAILED: " + result.stderr.decode("utf-8", "replace").strip())
    return result.stdout.decode("utf-8").strip()


def parse(data: bytes, today: str = None) -> dict:
    if not data.startswith(b"\x00\x0cQQPET_BUDGET"):
        raise BudgetFormatError("INVALID_BUDGET_DOCUMENT: missing QQPET_BUDGET magic")
    reader = _Reader(data)
    if reader.read_utf("magic") != MAGIC:
        raise BudgetFormatError("BUDGET_FORMAT_UNSUPPORTED: magic mismatch")
    version = reader.read_int("version")
    if version != VERSION:
        raise BudgetFormatError(f"BUDGET_FORMAT_UNSUPPORTED: version={version}")

    day = reader.read_utf("day")
    legacy_used_minutes = reader.read_int("legacyUsedMinutes")
    if legacy_used_minutes < 0:
        raise BudgetFormatError(f"LEGACY_BUDGET_INVALID:{legacy_used_minutes}")

    legacy_by_day = {}
    for _ in range(reader.read_count("legacyByDay.size")):
        key = reader.read_utf("legacyByDay.key")
        minutes = reader.read_int("legacyByDay.value")
        if key in legacy_by_day:
            raise BudgetFormatError(f"BUDGET_DUPLICATE_DAY:{key}")
        if minutes < 0:
            raise BudgetFormatError(f"LEGACY_BUDGET_DAY_INVALID:{key}={minutes}")
        legacy_by_day[key] = minutes
    if day in legacy_by_day:
        raise BudgetFormatError(f"LEGACY_BUDGET_DAY_INVALID: current day {day} also in legacyByDay")

    attempts = {}
    for _ in range(reader.read_count("attempts.size")):
        attempt_id = reader.read_utf("attempt.attemptId")
        attempt_day = reader.read_utf("attempt.day")
        minutes = reader.read_int("attempt.minutes")
        status = reader.read_utf("attempt.status")
        has_reason = reader.read_boolean("attempt.hasReason")
        reason = reader.read_utf("attempt.reason") if has_reason else None
        limit = reader.read_int("attempt.limitMinutes")
        if attempt_id in attempts:
            raise BudgetFormatError(f"BUDGET_DUPLICATE_ATTEMPT:{attempt_id}")
        if status not in STATUSES:
            # Fail closed like ReserveStatus.valueOf: never guess an unknown status.
            raise BudgetFormatError(f"BUDGET_STATUS_UNKNOWN:{status}")
        if minutes < 0 or not 1 <= limit <= 480:
            raise BudgetFormatError(f"BUDGET_MINUTES_INVALID:{attempt_id}")
        attempts[attempt_id] = {
            "attemptId": attempt_id,
            "day": attempt_day,
            "minutes": minutes,
            "status": status,
            "reason": reason,
            "limitMinutes": limit,
            "unresolved": status not in RESOLVED,
        }

    if not reader.at_end():
        raise BudgetFormatError("BUDGET_TRAILING_DATA")

    used_today = (used_for_day(legacy_for_day(today, day, legacy_used_minutes, legacy_by_day), attempts, today)
                  if today else None)
    return {
        "day": day,
        "legacyUsedMinutes": legacy_used_minutes,
        "legacyByDay": legacy_by_day,
        "attemptCount": len(attempts),
        "attempts": attempts,
        # Mirrors PlanBudgetLedger.used(state, day): legacy minutes only for the stored day (or its
        # legacyByDay entry), plus every keyed attempt whose own day matches. The stored `day` may lag
        # the device clock, so report both the stored day and the device's current day.
        "today": today,
        "usedMinutesOnStoredDay": used_for_day(legacy_for_day(day, day, legacy_used_minutes, legacy_by_day), attempts, day),
        "usedMinutesOnDeviceDay": used_today,
        "unresolvedAttemptIds": sorted(k for k, a in attempts.items() if a["unresolved"]),
    }


def pull(serial: str, app: str) -> bytes:
    adb = Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"
    result = subprocess.run(
        [str(adb), "-s", serial, "exec-out", "run-as", app, "cat",
         "files/pet-minute-budget-v2.bin"],
        capture_output=True, timeout=60,
    )
    if result.returncode:
        raise SystemExit("ADB_PULL_FAILED: " + result.stderr.decode("utf-8", "replace").strip())
    return result.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="DKS9K23420020242")
    parser.add_argument("--app", default="com.ynk.virtualdisplay")
    parser.add_argument("--file", help="Parse a local copy instead of pulling from the device")
    parser.add_argument("--show-attempts", action="store_true",
                        help="Include every attempt record, not just the unresolved ones")
    args = parser.parse_args()

    data = Path(args.file).read_bytes() if args.file else pull(args.serial, args.app)
    try:
        today = device_today(args.serial)
    except (SystemExit, OSError, subprocess.TimeoutExpired):
        today = None  # --file without a reachable device: report stored-day usage only
    try:
        state = parse(data, today)
    except BudgetFormatError as exc:
        # Fail closed: a malformed journal must never be reported as an empty budget.
        print(json.dumps({"error": str(exc), "bytes": len(data)}, ensure_ascii=False))
        return 2

    if not args.show_attempts:
        state["attempts"] = {
            k: v for k, v in state["attempts"].items() if v["unresolved"]
        }
        state["attemptsNote"] = "unresolved only; pass --show-attempts for all"
    print(json.dumps({"bytes": len(data), **state}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
