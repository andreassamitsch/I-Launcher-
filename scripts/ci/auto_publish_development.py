"""Trusted-base release gate; never execute code from a PR before merging it."""
import fnmatch
import json
import os
import subprocess
import time

BASE = "release/live-tv-audio-selection"
LABEL = "auto-publish-development"
ROOT = "app/src/main/java/com/andreassamitsch/ilauncher/"
REQUIRED = {
    "Android CI": ("*",),
    "TV Visual Smoke": (
        ROOT + "ui/**", "app/src/debug/**", "app/build.gradle.kts",
        "app/proguard-development.pro", "scripts/ci/tv-visual-smoke.sh",
    ),
    "Settings Touch Smoke": (
        ROOT + "ui/settings/**", ROOT + "ui/components/TouchControls.kt",
        "app/src/debug/**",
    ),
    "TV Discovery Visual Smoke": (
        ROOT + "ui/discover/**", ROOT + "ui/GoogleTvTopNavigation.kt",
        "app/src/debug/java/com/andreassamitsch/ilauncher/ui/preview/DiscoveryPreviewActivity.kt",
        "app/src/debug/AndroidManifest.xml",
    ),
    "TV Baseline Profile Capture": (
        "app/src/main/baseline-prof.txt", "scripts/ci/tv-baseline-profile.sh",
    ),
}
PUBLISH_PATHS = ("app/**", "build.gradle.kts", "settings.gradle.kts", "gradle.properties")
MANUAL_PATHS = (
    ".github/**", "scripts/ci/auto_publish_development.py",
    "scripts/ci/test_auto_publish_development.py",
)


def matches(paths, patterns):
    return any(fnmatch.fnmatchcase(path, pattern) for path in paths for pattern in patterns)


def required_checks(paths):
    return {name for name, patterns in REQUIRED.items() if matches(paths, patterns)}


def check_results(runs, expected):
    """Require the latest run of each expected workflow; also respect other observed PR checks."""
    latest = {}
    for run in runs:
        name = run["name"]
        order = (run["id"], run.get("run_attempt", 1))
        if name not in latest or order > latest[name][0]:
            latest[name] = (order, run)
    missing = expected - latest.keys()
    failed = {
        name for name, (_, run) in latest.items()
        if run["status"] == "completed" and run["conclusion"] != "success"
    }
    pending = {
        name for name, (_, run) in latest.items() if run["status"] != "completed"
    }
    return missing, failed, pending


def api(path, method="GET", body=None, paginate=False):
    cmd = ["gh", "api", "--method", method]
    if paginate:
        cmd += ["--paginate", "--slurp"]
    cmd.append(path)
    if body is not None:
        cmd += ["--input", "-"]
    result = subprocess.run(
        cmd, input=json.dumps(body) if body is not None else None,
        text=True, check=True, stdout=subprocess.PIPE,
    )
    return json.loads(result.stdout) if result.stdout.strip() else None


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    owner = repo.split("/")[0]
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as handle:
        event = json.load(handle)
    pr = event["pull_request"]
    number, head, base = pr["number"], pr["head"]["sha"], pr["base"]["sha"]
    endpoint = f"repos/{repo}/pulls/{number}"

    def fresh_pr():
        current = api(endpoint)
        if current["state"] != "open" or current["draft"]:
            raise RuntimeError("PR is no longer an open, ready pull request")
        if (current["user"]["login"] != owner or
                current["head"]["repo"]["full_name"] != repo or
                current["base"]["ref"] != BASE or
                current["head"]["sha"] != head or
                current["base"]["sha"] != base or
                LABEL not in {item["name"] for item in current["labels"]}):
            raise RuntimeError("PR identity, label, head or base changed; no merge")
        actual_base = api(f"repos/{repo}/git/ref/heads/{BASE}")["object"]["sha"]
        if actual_base != base:
            raise RuntimeError("Release branch advanced; refresh and retest the PR")
        return current

    current = fresh_pr()
    pages = api(endpoint + "/files?per_page=100", paginate=True)
    files = [item for page in pages for item in page]
    if len(files) != current["changed_files"] or len(files) > 300:
        raise RuntimeError("Incomplete or unusually large PR file list; manual review required")
    paths = {item["filename"] for item in files}
    paths.update(item["previous_filename"] for item in files if item.get("previous_filename"))
    if matches(paths, MANUAL_PATHS):
        raise RuntimeError("CI/publisher changes require manual review")
    expected = required_checks(paths)
    if not expected:
        raise RuntimeError("No required checks determined")
    print("Required checks:", ", ".join(sorted(expected)), flush=True)
    deadline = time.monotonic() + 40 * 60
    while time.monotonic() < deadline:
        current = fresh_pr()
        pages = api(f"repos/{repo}/actions/runs?event=pull_request&head_sha={head}&per_page=100", paginate=True)
        runs = [
            run for page in pages for run in page["workflow_runs"]
            if run["head_sha"] == head and run["event"] == "pull_request"
            and any(p["number"] == number for p in run.get("pull_requests", []))
        ]
        missing, failed, pending = check_results(runs, expected)
        print(f"Missing={sorted(missing)} Failed={sorted(failed)} Pending={sorted(pending)}", flush=True)
        if failed:
            raise RuntimeError("Required/observed PR checks failed; no merge")
        if not missing and not pending and current.get("mergeable") is True:
            break
        time.sleep(30)
    else:
        raise RuntimeError("Checks never reached a successful and mergeable state")
    fresh_pr()
    merged = api(endpoint + "/merge", method="PUT", body={
        "sha": head, "merge_method": "squash",
    })
    if not merged.get("merged"):
        raise RuntimeError("GitHub refused merge")
    sha = merged["sha"]
    print(f"Merged PR #{number}: {sha}", flush=True)
    if matches(paths, PUBLISH_PATHS):
        actual_base = api(f"repos/{repo}/git/ref/heads/{BASE}")["object"]["sha"]
        if actual_base != sha:
            raise RuntimeError("Release branch advanced after merge; inspect publisher before retry")
        # A GITHUB_TOKEN merge does not emit a new push-triggered Actions run.
        api(f"repos/{repo}/actions/workflows/publish-download.yml/dispatches",
            method="POST", body={"ref": BASE})
        print("Signed downloader workflow dispatched (publication not yet verified).", flush=True)
    else:
        print("No APK-affecting changes: publication not needed.", flush=True)


if __name__ == "__main__":
    main()
