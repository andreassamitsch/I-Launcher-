import unittest
from auto_publish_development import check_results, matches, required_checks, MANUAL_PATHS


class GateTest(unittest.TestCase):
    def test_home_requires_navigation(self):
        self.assertEqual(required_checks({"app/src/main/java/com/andreassamitsch/ilauncher/ui/home/HomeScreen.kt"}), {"Android CI", "TV Visual Smoke"})

    def test_profile_requires_capture(self):
        self.assertEqual(required_checks({"app/src/main/baseline-prof.txt"}), {"Android CI", "TV Baseline Profile Capture"})

    def test_settings_requires_touch(self):
        self.assertEqual(required_checks({"app/src/main/java/com/andreassamitsch/ilauncher/ui/settings/SettingsScreen.kt"}), {"Android CI", "TV Visual Smoke", "Settings Touch Smoke"})

    def test_workflow_requires_manual_review(self):
        self.assertTrue(matches({".github/workflows/publish-download.yml"}, MANUAL_PATHS))

    def test_missing_is_not_success(self):
        self.assertEqual(check_results([], {"Android CI"})[0], {"Android CI"})

    def test_latest_retry_must_pass(self):
        runs = [
            {"name": "Android CI", "id": 1, "status": "completed", "conclusion": "failure"},
            {"name": "Android CI", "id": 1, "run_attempt": 2, "status": "completed", "conclusion": "success"},
            {"name": "TV Visual Smoke", "id": 2, "status": "in_progress", "conclusion": None},
        ]
        self.assertEqual(check_results(runs, {"Android CI", "TV Visual Smoke"}), (set(), set(), {"TV Visual Smoke"}))

    def test_failure_blocks(self):
        runs = [{"name": "Android CI", "id": 1, "status": "completed", "conclusion": "failure"}]
        self.assertEqual(check_results(runs, {"Android CI"})[1], {"Android CI"})


if __name__ == "__main__":
    unittest.main()
