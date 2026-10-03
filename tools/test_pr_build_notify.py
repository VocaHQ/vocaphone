"""Pin PR Build notify so compile failures cannot look like a successful skip."""

import json
import subprocess
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent

# (want, compile, package, secret_skipped, expected_failed)
LEG_CASES = [
    # Intentional skip: prepare.want_*=false leaves compile and package skipped.
    (False, "skipped", "skipped", False, False),
    (False, "failure", "skipped", False, False),
    (False, "success", "success", False, False),
    # Wanted legs that produced artifacts.
    (True, "success", "success", False, False),
    # iOS ad-hoc secrets missing: sign job succeeds and writes skipped=true.
    (True, "success", "success", True, False),
    # Compile failure: package jobs skip because they do not use always().
    (True, "failure", "skipped", False, True),
    (True, "cancelled", "skipped", False, True),
    (True, "failure", "failure", False, True),
    (True, "cancelled", "cancelled", False, True),
    # Package/sign failure after a successful compile.
    (True, "success", "failure", False, True),
    (True, "success", "cancelled", False, True),
    # Wanted but neither compile nor package succeeded (unexpected skip).
    (True, "skipped", "skipped", False, True),
    (True, "success", "skipped", False, True),
    # Secrets metadata must not hide a real compile/package failure.
    (True, "failure", "skipped", True, True),
    (True, "success", "failure", True, True),
]


def notify_job(workflow):
    return workflow["jobs"]["notify"]


def notify_script(workflow):
    job = notify_job(workflow)
    step = next(step for step in job["steps"] if step.get("name") == "Post result comment")
    return step, step["with"]["script"]


def classification_js(script):
    start = script.index("function isBroken")
    end = script.index("const androidMeta")
    return script[start:end]


class PrBuildNotifyTests(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.safe_load((ROOT / ".github/workflows/pr-build.yml").read_text())
        self.job = notify_job(self.workflow)
        self.step, self.script = notify_script(self.workflow)

    def test_notify_waits_for_compile_jobs(self):
        self.assertEqual(
            set(self.job["needs"]),
            {"prepare", "android-build", "android", "ios-build", "ios"},
        )

    def test_notify_still_runs_after_skipped_or_failed_legs(self):
        condition = " ".join(self.job["if"].split())
        self.assertIn("always()", condition)
        self.assertIn("needs.prepare.result == 'success'", condition)
        self.assertIn("needs.prepare.outputs.allowed == 'true'", condition)

    def test_notify_reads_compile_results(self):
        env = self.step["env"]
        self.assertEqual(env["ANDROID_BUILD_RESULT"], "${{ needs['android-build'].result }}")
        self.assertEqual(env["IOS_BUILD_RESULT"], "${{ needs['ios-build'].result }}")
        self.assertEqual(env["ANDROID_RESULT"], "${{ needs.android.result }}")
        self.assertEqual(env["IOS_RESULT"], "${{ needs.ios.result }}")
        self.assertIn("ANDROID_BUILD_RESULT", self.script)
        self.assertIn("IOS_BUILD_RESULT", self.script)

    def test_failed_comment_and_job_status(self):
        self.assertIn("PR Build failed", self.script)
        self.assertIn("PR Build ready", self.script)
        self.assertIn("core.setFailed", self.script)
        failed_branch = self.script.split("if (failed)")[1]
        self.assertIn("PR Build failed", failed_branch)
        self.assertNotIn("PR Build ready", failed_branch.split("} else {")[0])

    def test_wanted_leg_matrix(self):
        harness = classification_js(self.script) + """
const cases = """ + json.dumps(LEG_CASES) + """;
for (const [want, compile, pack, secretSkipped, expected] of cases) {
  const got = wantedLegFailed(want, compile, pack, secretSkipped);
  if (got !== expected) {
    console.error(JSON.stringify({want, compile, pack, secretSkipped, expected, got}));
    process.exit(1);
  }
}
"""
        result = subprocess.run(
            ["node", "--input-type=commonjs", "-e", harness],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr or result.stdout)

    def test_old_package_only_check_misses_compile_failure(self):
        """The #315 notify bug: package is skipped, so result === 'failure' is false."""
        want, package_result = True, "skipped"
        old_failed = want and package_result == "failure"
        self.assertFalse(old_failed)
        harness = classification_js(self.script) + """
const failed = wantedLegFailed(true, 'failure', 'skipped', false);
if (!failed) process.exit(1);
"""
        subprocess.run(["node", "--input-type=commonjs", "-e", harness], check=True)


if __name__ == "__main__":
    unittest.main()
