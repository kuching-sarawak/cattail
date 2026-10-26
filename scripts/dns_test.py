"""Offline unit tests for scripts/dns.py (no network).

Run: python3 -I -m unittest discover -s scripts -p dns_test.py
  (or: python3 -I scripts/dns_test.py; "-m unittest scripts/dns_test.py" fails under -I
  because -I keeps the working directory off sys.path)
"""
import contextlib
import io
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dns  # noqa: E402

ENV = {"CF_ZONE_ID": "0123456789abcdef0123456789abcdef", "CF_ZONE_NAME": "example.com",
       "CATTAIL_DNS_SUBDOMAIN": "ts"}
ENV_KEYS = ("CF_ZONE_ID", "CF_ZONE_NAME", "CATTAIL_DNS_SUBDOMAIN", "CF_API_CURLCFG")


class EnvTestCase(unittest.TestCase):
    """Runs every test with the neutral zone (example.com, subdomain ts) in the environment."""
    env = ENV

    def setUp(self):
        self._saved = {k: os.environ.get(k) for k in ENV_KEYS}
        for k in ENV_KEYS:
            os.environ.pop(k, None)
        os.environ.update(self.env)

    def tearDown(self):
        for k, v in self._saved.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v


def run_main_quiet(argv):
    stderr, sys.stderr = sys.stderr, open(os.devnull, "w")
    try:
        return dns.main(argv)
    finally:
        sys.stderr.close()
        sys.stderr = stderr


def no_api_calls(test, fn):
    """Run fn with api/load_token replaced by recorders; assert neither was called."""
    calls = []
    orig_api, orig_token = dns.api, dns.load_token
    dns.api = lambda *a, **k: calls.append(a)
    dns.load_token = lambda: calls.append("token") or "x"
    try:
        fn()
    finally:
        dns.api, dns.load_token = orig_api, orig_token
    test.assertEqual(calls, [])


class NameGuardTest(EnvTestCase):
    def test_accepts_single_label_under_ts(self):
        for n in ("test.ts.example.com", "mc.ts.example.com", "a.ts.example.com",
                  "my-server-2.ts.example.com"):
            self.assertEqual(dns.fqdn(n), n)

    def test_rejects_everything_else(self):
        bad = ["example.com", "ts.example.com", "ts", "test.ts", "test",
               "a.b.ts.example.com", "x.example.com", "mc-server-1.example.com",
               "TEST.ts.example.com", "test.ts.example.com.", "test.ts.example.com.evil.com",
               "-test.ts.example.com", "test-.ts.example.com", "te_st.ts.example.com",
               ".ts.example.com", "", " test.ts.example.com", "test.ts.example.com\n",
               "*.ts.example.com", "test.tsxexample.com"]
        for n in bad:
            with self.subTest(name=n), self.assertRaises(dns.UsageError):
                dns.fqdn(n)

    def test_main_refuses_before_api_with_exit_2(self):
        def run():
            for argv in (["dns.py", "delete", "example.com"], ["dns.py", "delete", "ts"],
                         ["dns.py", "show", "ts.example.com"],
                         ["dns.py", "ensure", "example.com", "100.1.2.3", "cattail=1"]):
                with self.subTest(argv=argv):
                    self.assertEqual(run_main_quiet(argv), 2)
        no_api_calls(self, run)

    def test_api_url_uses_zone_id_from_env(self):
        self.assertEqual(dns.api_base(), "https://api.cloudflare.com/client/v4/zones/"
                         "0123456789abcdef0123456789abcdef/dns_records")

    def test_token_file_from_env(self):
        os.environ["CF_API_CURLCFG"] = "/nonexistent/cf.curlcfg"
        self.assertEqual(dns.curlcfg_path(), "/nonexistent/cf.curlcfg")
        del os.environ["CF_API_CURLCFG"]
        self.assertTrue(dns.curlcfg_path().endswith(os.path.join(".secrets", "cf.curlcfg")))


class NoSubdomainTest(EnvTestCase):
    env = {"CF_ZONE_ID": "0123456789abcdef0123456789abcdef", "CF_ZONE_NAME": "example.com"}

    def test_accepts_label_directly_under_zone(self):
        for n in ("test.example.com", "my-server-2.example.com"):
            self.assertEqual(dns.fqdn(n), n)

    def test_rejects_subdomain_and_apex(self):
        for n in ("test.ts.example.com", "example.com", "a.b.example.com", "TEST.example.com",
                  "test.example.com.", "test.example.org"):
            with self.subTest(name=n), self.assertRaises(dns.UsageError):
                dns.fqdn(n)


class EnvRequirementTest(EnvTestCase):
    def test_missing_or_bad_zone_settings_exit_2_before_api(self):
        cases = [("CF_ZONE_ID", None), ("CF_ZONE_NAME", None), ("CF_ZONE_ID", ""),
                 ("CF_ZONE_NAME", ""), ("CF_ZONE_ID", "not-a-zone-id"),
                 ("CF_ZONE_NAME", "Example.COM"), ("CF_ZONE_NAME", "com"),
                 ("CATTAIL_DNS_SUBDOMAIN", "-ts"), ("CATTAIL_DNS_SUBDOMAIN", "t_s")]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                saved = os.environ.get(key)
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value
                try:
                    with self.assertRaises(dns.UsageError):
                        dns.zone()
                    no_api_calls(self, lambda: self.assertEqual(
                        run_main_quiet(["dns.py", "show", "test.ts.example.com"]), 2))
                finally:
                    if saved is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = saved

    def test_empty_subdomain_means_none(self):
        os.environ["CATTAIL_DNS_SUBDOMAIN"] = ""
        self.assertEqual(dns.fqdn("test.example.com"), "test.example.com")


class TxtValueTest(EnvTestCase):
    def test_strips_one_pair_of_surrounding_quotes(self):
        self.assertEqual(dns.txt_value('"cattail=1; target=100.101.102.103"'),
                         "cattail=1; target=100.101.102.103")

    def test_unquoted_unchanged(self):
        self.assertEqual(dns.txt_value("cattail=1; target=100.101.102.103"),
                         "cattail=1; target=100.101.102.103")

    def test_edge_cases(self):
        self.assertEqual(dns.txt_value(None), "")
        self.assertEqual(dns.txt_value(""), "")
        self.assertEqual(dns.txt_value('"'), '"')
        self.assertEqual(dns.txt_value('""'), "")
        self.assertEqual(dns.txt_value('"a'), '"a')

    def test_quoted_argument_compares_equal_in_ensure(self):
        """A quoted TXT argument must report unchanged against the stored unquoted value."""
        stored = {"id": "1", "name": "test.ts.example.com", "type": "TXT",
                  "content": "cattail=1; target=100.101.102.103", "ttl": 300}
        writes = []
        orig_list, orig_api = dns.list_records, dns.api
        dns.list_records = lambda t, n, r: [stored]
        dns.api = lambda *a, **k: writes.append(a)
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                dns.ensure_one("x", "test.ts.example.com", "TXT", '"cattail=1; target=100.101.102.103"')
        finally:
            dns.list_records, dns.api = orig_list, orig_api
        self.assertEqual(writes, [])
        self.assertIn("unchanged", out.getvalue())


class MatchesTest(EnvTestCase):
    def test_name_and_type_must_match(self):
        rec = {"name": "test.ts.example.com", "type": "A"}
        self.assertTrue(dns.matches(rec, "test.ts.example.com", "A"))
        self.assertFalse(dns.matches(rec, "test.ts.example.com", "TXT"))
        self.assertFalse(dns.matches(rec, "mc.ts.example.com", "A"))
        self.assertFalse(dns.matches({}, "test.ts.example.com", "A"))

    def test_delete_skips_mismatched_records(self):
        recs = {"A": [{"id": "good", "name": "test.ts.example.com", "type": "A", "content": "x"},
                      {"id": "apex", "name": "example.com", "type": "A", "content": "y"}],
                "TXT": [{"id": "wrongtype", "name": "test.ts.example.com", "type": "CNAME",
                         "content": "z"}]}
        deleted = []
        orig_list, orig_api = dns.list_records, dns.api
        dns.list_records = lambda t, n, r: recs[r]
        dns.api = lambda t, m, url, body=None: deleted.append(url.rsplit("/", 1)[1])
        stdout, sys.stdout = sys.stdout, open(os.devnull, "w")
        try:
            dns.cmd_delete("x", "test.ts.example.com")
        finally:
            sys.stdout.close()
            sys.stdout = stdout
            dns.list_records, dns.api = orig_list, orig_api
        self.assertEqual(deleted, ["good"])


if __name__ == "__main__":
    unittest.main()
