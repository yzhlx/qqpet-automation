#!/usr/bin/env python3
"""Read-only release gate for the QQ pet automation app ("蟹啦").

It never installs, taps or clears anything. It only
  * asks adb for the installed versionCode/versionName,
  * compares them with `git rev-list --count HEAD` (the app's versionCode),
  * reads files/pet_mvp.log through `run-as` and judges every session since --since.

Usage (see docs/RELEASE-ACCEPTANCE.md):
    python tools/release_acceptance.py --since "2026-11-07 05:00"
    python tools/release_acceptance.py --log pet_mvp.log --since "2026-11-07 05:00" --skip-install-check

Exit code 0 = every gate row is 通过 or 未定 that the checklist allows; 1 = at least one 不通过.
"""
import argparse
import json
import re
import subprocess
import sys
from datetime import datetime

PKG = "com.ynk.virtualdisplay"
PASS, FAIL, UNDECIDED = "通过", "不通过", "未定"
RAW_CODE = re.compile(r"\b[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+\b")  # e.g. HEALTH_STALE, RETURN_HOME_UNRECOVERED


def ts(s):
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S.%f")


def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, **kw)


def read_device_log(adb, device):
    cmd = [adb] + (["-s", device] if device else []) + ["exec-out", "run-as", PKG, "cat", "files/pet_mvp.log"]
    p = run(cmd)
    if p.returncode != 0 or not p.stdout:
        sys.exit("读不到手机日志（adb 未连接、设备未授权，或 App 未安装）: " + p.stderr.decode("utf-8", "replace")[:200])
    return p.stdout.decode("utf-8", errors="replace")


def parse_rows(text):
    rows = []
    for line in text.splitlines():
        try:
            rows.append(json.loads(line))
        except ValueError:
            pass
    return rows


def split_sessions(rows):
    """A session ends at its SESSION_STOP row; it starts at the first row after the previous stop."""
    sessions, current = [], []
    for r in rows:
        is_session_row = r.get("state") == "SESSION"
        res = str(r.get("result"))
        if is_session_row and not res.startswith("SESSION_STOP "):
            # REJECTED / RECONCILED / STOP_REQUEST rows belong to the session they are around.
            current.append(r)
            continue
        current.append(r)
        if is_session_row and res.startswith("SESSION_STOP "):
            sessions.append(current)
            current = []
    return sessions


def summarise(seg):
    res_stop = str(seg[-1].get("result"))[len("SESSION_STOP "):]
    start = ts(seg[0]["timestamp"])
    end = ts(seg[-1]["timestamp"])
    actions = [str(r.get("action", "")) for r in seg]

    def first_at(suffix):
        for r in seg:
            if str(r.get("action", "")).endswith(suffix):
                return (ts(r["timestamp"]) - start).total_seconds()
        return None

    counts = dict(re.findall(r"(work|care|study|adventure)=([\d/]+)", res_stop))
    verdict_pass = res_stop.startswith("PLAN_PASS")
    manual_stop = any(str(r.get("result")).startswith("SESSION_STOP_REQUEST") for r in seg)
    rejected = [str(r.get("result")) for r in seg if str(r.get("result")).startswith("SESSION_START_REJECTED")]
    return {
        "end": seg[-1]["timestamp"][:19],
        "seconds": int((end - start).total_seconds()),
        "stop": res_stop,
        "pass": verdict_pass,
        "manual_stop": manual_stop,
        "counts": counts,
        "rejected": rejected,
        "care_first_step_s": first_at("plan-care-before"),
        "adventure_started_s": first_at("adventure-started"),
        # the plan runs care -> study -> adventure, so the session ends right after the walk home
        "adventure_total_s": (None if first_at("adventure-start") is None
                              else int((end - start).total_seconds() - first_at("adventure-start"))),
        "n_actions": len(actions),
    }


def n(counts, key):
    v = counts.get(key, "0")
    return int(v.split("/")[0])


def install_rows(adb, device, repo):
    rows = []
    out = run([adb] + (["-s", device] if device else []) + ["shell", "dumpsys", "package", PKG])
    text = out.stdout.decode("utf-8", "replace")
    code = re.search(r"versionCode=(\d+)", text)
    name = re.search(r"versionName=(\S+)", text)
    head = run(["git", "-C", repo, "rev-list", "--count", "HEAD"]).stdout.decode().strip()
    dirty = run(["git", "-C", repo, "status", "--porcelain"]).stdout.decode().strip()
    if not code:
        rows.append(("手机装的是当前 HEAD 的构建", FAIL, "dumpsys 里读不到 versionCode"))
    elif code.group(1) != head:
        rows.append(("手机装的是当前 HEAD 的构建", FAIL,
                     f"手机 versionCode={code.group(1)} (versionName {name.group(1) if name else '?'})，HEAD 提交数={head}"))
    else:
        rows.append(("手机装的是当前 HEAD 的构建", PASS, f"versionCode={head}"))
    rows.append(("工作树干净（验收对象就是这个提交）", FAIL if dirty else PASS,
                 f"{len(dirty.splitlines())} 个未提交改动" if dirty else "git status 干净"))
    return rows


def gate_rows(sessions, min_pass):
    rows = []
    judged = [s for s in sessions if not s["manual_stop"]]
    passes = [s for s in judged if s["pass"]]
    fails = [s for s in judged if not s["pass"]]
    manual = [s for s in sessions if s["manual_stop"]]

    rows.append((f"完整方案 PLAN_PASS ≥ {min_pass} 次", PASS if len(passes) >= min_pass else FAIL,
                 f"{len(passes)} 次通过：" + "; ".join(f"{s['end'][5:16]} {s['seconds']}s" for s in passes)))
    rows.append(("没有非预期失败会话（手动停止除外）", FAIL if fails else PASS,
                 "; ".join(f"{s['end'][5:16]} {s['stop'][:60]}" for s in fails) or "0 个"))
    rows.append(("护理真实执行过（care≥1）", PASS if any(n(s["counts"], "care") >= 1 for s in passes) else FAIL,
                 f"{sum(n(s['counts'], 'care') for s in passes)} 次"))
    rows.append(("冒险真实执行过并回房（adventure≥1）",
                 PASS if any(n(s["counts"], "adventure") >= 1 for s in passes) else FAIL,
                 f"{sum(n(s['counts'], 'adventure') for s in passes)} 次"))
    study = sum(n(s["counts"], "study") for s in passes)
    rows.append(("学习真实上过课（study≥1）", PASS if study >= 1 else UNDECIDED,
                 f"{study} 节" if study else "本区间没有上课（可能当天次数已满）：请在公告里写「学习未实机覆盖」"))
    rows.append(("手动停止被日志记录（SESSION_STOP_REQUEST）", PASS if manual else UNDECIDED,
                 f"{len(manual)} 次" if manual else "本区间没有做手动停止用例"))
    rej = [s for s in sessions if s["rejected"]]
    rows.append(("开始没有被 PENDING_RUN_UNRESOLVED 拒绝", UNDECIDED if rej else PASS,
                 f"{sum(len(s['rejected']) for s in rej)} 次被拒后自动对账（已知现象，需在公告里如实写）" if rej else "0 次"))
    slow = [s for s in passes if s["care_first_step_s"] and s["care_first_step_s"] > 120]
    rows.append(("预检到护理首步 ≤ 120s", FAIL if slow else PASS,
                 "; ".join(f"{s['end'][5:16]} {int(s['care_first_step_s'])}s" for s in slow)
                 or "最慢 %ds" % max([int(s["care_first_step_s"]) for s in passes if s["care_first_step_s"]] or [0])))
    long_adv = [s for s in passes if s["adventure_total_s"] and s["adventure_total_s"] > 150]
    adv = [s["adventure_total_s"] for s in passes if s["adventure_total_s"]]
    rows.append(("冒险出发到会话结束 ≤ 150s（附近走走实测约 60s）", FAIL if long_adv else PASS if adv else UNDECIDED,
                 "; ".join(f"{s['end'][5:16]} {s['adventure_total_s']}s" for s in long_adv)
                 or (f"最慢 {max(adv)}s" if adv else "区间内没有冒险")))
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--since", required=True, help='只判这个时间之后结束的会话，手机时间，如 "2026-11-07 05:00"')
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--device", default=None)
    ap.add_argument("--repo", default=".", help="用来取 HEAD 提交数的 git 仓库")
    ap.add_argument("--log", help="离线日志文件（不连手机）")
    ap.add_argument("--ignore", default="",
                    help="故意制造的破坏性用例所在会话的结束时间 HH:MM，逗号分隔（它们不计入「非预期失败」，但会列出）")
    ap.add_argument("--min-pass", type=int, default=3)
    ap.add_argument("--skip-install-check", action="store_true")
    a = ap.parse_args()

    text = open(a.log, encoding="utf-8", errors="replace").read() if a.log else read_device_log(a.adb, a.device)
    sessions = [summarise(s) for s in split_sessions(parse_rows(text))]
    sessions = [s for s in sessions if s["end"] >= a.since]
    ignored = {x.strip() for x in a.ignore.split(",") if x.strip()}
    for s in sessions:
        s["intentional"] = s["end"][11:16] in ignored
        if s["intentional"]:
            s["manual_stop"] = True  # judged like a deliberate stop: listed, never counted as an unexpected failure

    table = []
    if not a.skip_install_check:
        table += install_rows(a.adb, a.device, a.repo)
    table += gate_rows(sessions, a.min_pass)

    print(f"区间：{a.since} 之后结束的会话共 {len(sessions)} 个")
    for s in sessions:
        tag = "故意" if s.get("intentional") else "手动停止" if s["manual_stop"] else ("PASS" if s["pass"] else "FAIL")
        print(f"  {s['end'][5:]} {tag:<4} {s['seconds']:>4}s  {s['stop'][:70]}")
    print()
    width = max(len(r[0]) for r in table)
    for name, verdict, evidence in table:
        print(f"[{verdict}] {name.ljust(width)}  {evidence}")
    bad = [r for r in table if r[1] == FAIL]
    print()
    print("结论：" + ("不通过，不要推送（%d 项不通过）" % len(bad) if bad else
                    "门禁通过（未定项请在更新公告里如实写明）" if any(r[1] == UNDECIDED for r in table) else "门禁通过"))
    print("注意：以上只覆盖日志可判的部分，界面项（首页文案、无原始代码、停止按钮）仍要按清单人工看一眼并截图。")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
