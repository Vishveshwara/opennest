import tempfile
import unittest
from pathlib import Path

from verify_backend import read_reports


class ReportGateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def report(self, file="TEST-suite.xml", name="required.Suite", **counts):
        attributes = {"tests": 1, "failures": 0, "errors": 0, "skipped": 0, **counts}
        fields = " ".join(f'{key}="{value}"' for key, value in attributes.items())
        (self.directory / file).write_text(f'<testsuite name="{name}" {fields}/>')

    def test_accepts_a_completed_suite_and_preserves_actual_counts(self):
        self.report(tests=23)
        result = read_reports(self.directory, {"required.Suite"})
        self.assertEqual(result["required.Suite"], {"tests": 23, "failures": 0, "errors": 0, "skipped": 0})

    def test_rejects_an_empty_report_directory(self):
        with self.assertRaisesRegex(ValueError, "Missing test suites"):
            read_reports(self.directory, {"required.Suite"})

    def test_rejects_missing_required_suite_even_if_other_tests_pass(self):
        self.report(name="other.Suite", tests=114)
        with self.assertRaisesRegex(ValueError, "Missing test suites"):
            read_reports(self.directory, {"required.Suite"})

    def test_rejects_failed_errored_and_skipped_suites(self):
        for condition in ("failures", "errors", "skipped"):
            with self.subTest(condition=condition):
                self.report(**{condition: 1})
                with self.assertRaisesRegex(ValueError, "Unsuccessful suite"):
                    read_reports(self.directory, {"required.Suite"})

    def test_rejects_a_suite_with_no_executed_tests(self):
        self.report(tests=0)
        with self.assertRaisesRegex(ValueError, "No tests executed"):
            read_reports(self.directory, {"required.Suite"})

    def test_rejects_duplicate_suite_reports(self):
        self.report()
        self.report(file="TEST-duplicate.xml")
        with self.assertRaisesRegex(ValueError, "Duplicate test suite report"):
            read_reports(self.directory, {"required.Suite"})


if __name__ == "__main__":
    unittest.main()
