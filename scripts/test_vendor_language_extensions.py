#!/usr/bin/env python3
"""Source verification does not invoke any package manager or network request."""

import json
import subprocess
import unittest

import vendor_language_extensions as vendor


class SourceBundleTests(unittest.TestCase):
    def test_real_archives_and_licenses(self):
        self.assertEqual(7, len(vendor.verify()))

    def test_all_installers_parse_and_refuse_local_execution(self):
        for name in vendor.COMMANDS:
            with self.subTest(name=name):
                script = vendor.BUNDLES / name / "bundle/install.sh"
                subprocess.run(["/bin/sh", "-n", str(script)], check=True)
                result = subprocess.run(["/bin/sh", str(script)], env={"PATH": ""}, capture_output=True, text=True)
                self.assertEqual(69, result.returncode)
                self.assertIn("Cloud CI build only", result.stderr)

    def test_fingerprint_is_order_independent_and_adapter_sensitive(self):
        entries = vendor.verify()
        self.assertEqual(vendor.source_set(entries), vendor.source_set(list(reversed(entries))))
        changed = [dict(entry) for entry in entries]
        changed[0]["adapterSha256"] = "0" * 64
        self.assertNotEqual(vendor.source_set(entries), vendor.source_set(changed))

    def test_catalog_has_only_descriptors_no_download_claim(self):
        catalog = json.loads((vendor.ASSETS / "catalog.json").read_text())
        self.assertEqual(vendor.PLATFORM, catalog["platform"])
        self.assertEqual("language-extensions-" + catalog["sourceSetSha256"], catalog["releaseTag"])
        for entry in catalog["extensions"]:
            self.assertEqual(set(entry), {"id", "command", "repository", "revision", "version", "sourceSha256", "adapterSha256"})


if __name__ == "__main__":
    unittest.main()
