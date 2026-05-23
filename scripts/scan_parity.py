#!/usr/bin/env python3
"""
Compare our in-repo self-scan output against SonarQube Cloud's issue list
for the same commit, and report the delta.

What gets compared:
  * total counts, per-source
  * rule-level cardinality — for each rule key, "self" count vs "cloud" count
  * issue-level intersection / symmetric difference, keyed on
    (ruleKey, file, line); per-issue messages are not compared because
    SonarQube Cloud may post-process them

Inputs:
  --self-scan PATH       Path to our scan.json (from `bin/sonar --save`)
  --project-key KEY      SonarQube Cloud projectKey
  --organization SLUG    SonarQube Cloud organisation slug
  --host URL             SonarQube Cloud host (default: https://sonarcloud.io)
  --branch NAME          Optional: branch name (analyzed via `branch` filter)
  --pull-request NUMBER  Optional: PR number (analyzed via `pullRequest` filter)

The SONAR_TOKEN env var is read for the SonarQube Cloud HTTP Basic auth.

Output: a Markdown report written to stdout (and to $GITHUB_STEP_SUMMARY when
that env var is set). Exit 0 always — this is an observational tool, not a
gate. CI workflows can layer their own gate on top by parsing the JSON
artifact this script writes to --out (defaulting to .sonar-predictor/parity.json).

No third-party Python dependencies: stdlib only (urllib, json, base64).
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.parse
import urllib.request
from collections import Counter
from typing import Any


SOURCE_SELF = "self"
SOURCE_CLOUD = "cloud"


def load_self_scan(path: str) -> list[dict[str, Any]]:
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    issues: list[dict[str, Any]] = []
    for file_block in doc.get("files", []) or []:
        file_path = file_block.get("file") or ""
        for issue in file_block.get("issues", []) or []:
            issues.append(
                {
                    "ruleKey": issue.get("ruleKey"),
                    "file": _normalize_path(file_path),
                    "line": issue.get("startLine") or 0,
                    "severity": issue.get("severity"),
                    "type": issue.get("type"),
                    "source": SOURCE_SELF,
                }
            )
    return issues


def _normalize_path(p: str) -> str:
    """Strip leading './' and any absolute-prefix so self and cloud paths match."""
    p = p.replace("\\", "/").lstrip("./")
    # Cloud component looks like `<projectKey>:src/main/...`; strip the colon-prefix.
    if ":" in p:
        p = p.split(":", 1)[-1]
    return p


def fetch_cloud_issues(
    host: str,
    project_key: str,
    organization: str,
    token: str,
    branch: str | None,
    pull_request: str | None,
) -> list[dict[str, Any]]:
    """Pull every OPEN issue for the project (or PR) from the SonarQube Cloud HTTP API."""
    auth = base64.b64encode(f"{token}:".encode()).decode()
    headers = {"Authorization": f"Basic {auth}", "Accept": "application/json"}

    out: list[dict[str, Any]] = []
    page = 1
    page_size = 500
    while True:
        params: dict[str, Any] = {
            "componentKeys": project_key,
            "organization": organization,
            "ps": page_size,
            "p": page,
            "resolved": "false",
        }
        if pull_request:
            params["pullRequest"] = pull_request
        elif branch:
            params["branch"] = branch
        url = f"{host}/api/issues/search?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = json.load(resp)

        for issue in payload.get("issues", []) or []:
            component = issue.get("component", "")
            out.append(
                {
                    "ruleKey": issue.get("rule"),
                    "file": _normalize_path(component),
                    "line": issue.get("line") or 0,
                    "severity": issue.get("severity"),
                    "type": issue.get("type"),
                    "source": SOURCE_CLOUD,
                }
            )

        total = payload.get("total", 0)
        if page * page_size >= total or not payload.get("issues"):
            break
        page += 1
        if page > 20:  # 10_000-issue safety cap
            break
    return out


def _key(issue: dict[str, Any]) -> tuple[str, str, int]:
    return (issue.get("ruleKey") or "", issue.get("file") or "", int(issue.get("line") or 0))


def compare(self_issues: list[dict[str, Any]], cloud_issues: list[dict[str, Any]]) -> dict[str, Any]:
    self_by_key = {_key(i): i for i in self_issues}
    cloud_by_key = {_key(i): i for i in cloud_issues}

    self_keys = set(self_by_key)
    cloud_keys = set(cloud_by_key)
    both = self_keys & cloud_keys
    only_self = self_keys - cloud_keys
    only_cloud = cloud_keys - self_keys

    return {
        "counts": {
            "self": len(self_issues),
            "cloud": len(cloud_issues),
            "common": len(both),
            "only_self": len(only_self),
            "only_cloud": len(only_cloud),
        },
        "per_rule": _rule_breakdown(self_issues, cloud_issues),
        "only_self_samples": [self_by_key[k] for k in sorted(only_self)][:10],
        "only_cloud_samples": [cloud_by_key[k] for k in sorted(only_cloud)][:10],
    }


def _rule_breakdown(self_issues: list[dict[str, Any]], cloud_issues: list[dict[str, Any]]) -> list[dict[str, Any]]:
    self_counts = Counter(i["ruleKey"] for i in self_issues)
    cloud_counts = Counter(i["ruleKey"] for i in cloud_issues)
    all_rules = sorted(set(self_counts) | set(cloud_counts))
    rows = []
    for rule in all_rules:
        s = self_counts.get(rule, 0)
        c = cloud_counts.get(rule, 0)
        rows.append({"rule": rule, "self": s, "cloud": c, "delta": s - c})
    rows.sort(key=lambda r: (-abs(r["delta"]), -max(r["self"], r["cloud"])))
    return rows


def render_markdown(report: dict[str, Any]) -> str:
    c = report["counts"]
    lines = ["## Scan parity — self-scan ↔ SonarQube Cloud", ""]
    lines += [
        "| Metric | Value |",
        "| --- | --- |",
        f"| Self-scan total | {c['self']} |",
        f"| SonarQube Cloud total | {c['cloud']} |",
        f"| In both (same rule, same file, same line) | {c['common']} |",
        f"| Only in self-scan | {c['only_self']} |",
        f"| Only in SonarQube Cloud | {c['only_cloud']} |",
        "",
        "Parity score: "
        f"**{_parity_pct(c):.1f}%** "
        f"(common ÷ union)",
        "",
    ]

    rule_rows = [r for r in report["per_rule"] if r["delta"] != 0]
    if rule_rows:
        lines += [
            "### Rules with count drift",
            "",
            "Positive delta → our self-scan reports more than SonarQube Cloud. "
            "Negative → SonarQube Cloud reports more.",
            "",
            "| Rule | Self | Cloud | Δ |",
            "| --- | ---: | ---: | ---: |",
        ]
        for r in rule_rows[:30]:
            lines.append(f"| `{r['rule']}` | {r['self']} | {r['cloud']} | {r['delta']:+d} |")
        if len(rule_rows) > 30:
            lines.append(f"| _… {len(rule_rows) - 30} more rules with drift, see parity.json artifact_ | | | |")
        lines.append("")
    else:
        lines += ["### Rules with count drift", "", "_None — every rule has matching counts on both sides._", ""]

    if report["only_self_samples"]:
        lines += ["### Sample issues only in self-scan (up to 10)", ""]
        for i in report["only_self_samples"]:
            lines.append(f"- `{i['ruleKey']}` {i['file']}:{i['line']}")
        lines.append("")
    if report["only_cloud_samples"]:
        lines += ["### Sample issues only in SonarQube Cloud (up to 10)", ""]
        for i in report["only_cloud_samples"]:
            lines.append(f"- `{i['ruleKey']}` {i['file']}:{i['line']}")
        lines.append("")

    return "\n".join(lines)


def _parity_pct(c: dict[str, int]) -> float:
    union = c["self"] + c["cloud"] - c["common"]
    if union == 0:
        return 100.0
    return 100.0 * c["common"] / union


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-scan", required=True)
    parser.add_argument("--project-key", required=True)
    parser.add_argument("--organization", required=True)
    parser.add_argument("--host", default="https://sonarcloud.io")
    parser.add_argument("--branch")
    parser.add_argument("--pull-request")
    parser.add_argument("--out", default=".sonar-predictor/parity.json")
    args = parser.parse_args(argv)

    token = os.environ.get("SONAR_TOKEN")
    if not token:
        print("error: SONAR_TOKEN env var is required", file=sys.stderr)
        return 2

    self_issues = load_self_scan(args.self_scan)
    cloud_issues = fetch_cloud_issues(
        args.host,
        args.project_key,
        args.organization,
        token,
        args.branch,
        args.pull_request,
    )
    report = compare(self_issues, cloud_issues)

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)

    md = render_markdown(report)
    print(md)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as f:
            f.write(md)
            f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
