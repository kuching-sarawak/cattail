#!/usr/bin/env python3
"""Manage the Cattail DNS records (A + TXT) in a Cloudflare zone.

Usage:
  dns.py ensure <name> <A-ip> <txt>   create/update exactly one A + one TXT
  dns.py show   <name>                print the current A/TXT records
  dns.py delete <name>                delete every A + TXT record for the name

Environment:
  CF_ZONE_ID            Cloudflare zone id (32 hex characters), required
  CF_ZONE_NAME          the zone's domain, e.g. example.com, required
  CATTAIL_DNS_SUBDOMAIN  optional subdomain the records live under, e.g. ts
  CF_API_CURLCFG        token file (default: .secrets/cf.curlcfg in the repo)

<name> must be exactly <label>.<subdomain>.<zone> when CATTAIL_DNS_SUBDOMAIN is
set, else <label>.<zone> (one label of lowercase letters, digits, hyphens);
anything else, or a missing/invalid zone setting, is refused with exit 2 before
any API call. A TXT argument wrapped in double quotes is unquoted first.
The API token is read at runtime from the token file (the curl config line
`header = "Authorization: Bearer <token>"`); it is sent only as an HTTP header
and never printed. Errors print only the HTTP status and Cloudflare's
errors[].message. Exits non-zero on any API error.
"""
import ipaddress
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

TTL = 300
DEFAULT_CURLCFG = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".secrets", "cf.curlcfg")
LABEL = r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"


class ApiError(Exception):
    pass


class UsageError(Exception):
    pass


def zone():
    """Return (zone_id, zone_name, subdomain or "") from the environment, or raise UsageError."""
    zone_id = os.environ.get("CF_ZONE_ID", "")
    zone_name = os.environ.get("CF_ZONE_NAME", "")
    sub = os.environ.get("CATTAIL_DNS_SUBDOMAIN", "")
    if not re.fullmatch(r"[0-9a-f]{32}", zone_id):
        raise UsageError("CF_ZONE_ID must be set to the Cloudflare zone id (32 hex characters)")
    if not re.fullmatch(rf"{LABEL}(?:\.{LABEL})+", zone_name):
        raise UsageError("CF_ZONE_NAME must be set to the zone's lowercase domain, e.g. example.com")
    if sub and not re.fullmatch(rf"{LABEL}(?:\.{LABEL})*", sub):
        raise UsageError("CATTAIL_DNS_SUBDOMAIN must be lowercase DNS labels, e.g. ts")
    return zone_id, zone_name, sub


def api_base():
    return f"https://api.cloudflare.com/client/v4/zones/{zone()[0]}/dns_records"


def curlcfg_path():
    return os.environ.get("CF_API_CURLCFG") or DEFAULT_CURLCFG


def load_token():
    path = curlcfg_path()
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                m = re.match(r'\s*header\s*=\s*"Authorization:\s*Bearer\s+([^"\s]+)"\s*$', line)
                if m:
                    return m.group(1)
    except OSError as exc:
        raise ApiError(f"cannot read token file {path}: {exc.strerror}") from None
    raise ApiError(f"no 'Authorization: Bearer' header line in {path}")


def api(token, method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            status, raw = resp.status, resp.read()
    except urllib.error.HTTPError as exc:
        status, raw = exc.code, exc.read()
    except urllib.error.URLError as exc:
        raise ApiError(f"{method} failed: {exc.reason}") from None
    try:
        doc = json.loads(raw)
    except ValueError:
        raise ApiError(f"{method} HTTP {status}: non-JSON response") from None
    if status >= 300 or not doc.get("success"):
        msgs = "; ".join(str(e.get("message", "")) for e in doc.get("errors") or []) or "unknown error"
        raise ApiError(f"{method} HTTP {status}: {msgs}")
    return doc.get("result")


# Only <label>.<subdomain>.<zone> (or <label>.<zone> without a subdomain) may ever be
# touched: one non-empty label of lowercase letters, digits and hyphens (no
# leading/trailing hyphen). Checked before any API call.
def fqdn(name):
    """Return the validated full name, or raise UsageError for anything else."""
    _, zone_name, sub = zone()
    suffix = f"{sub}.{zone_name}" if sub else zone_name
    if not isinstance(name, str) or not re.fullmatch(rf"{LABEL}\.{re.escape(suffix)}", name):
        raise UsageError(f"refusing name {name!r}: only <label>.{suffix} is allowed "
                         "(one label of lowercase letters, digits, hyphens; full name required)")
    return name


def list_records(token, name, rtype):
    q = urllib.parse.urlencode({"name": name, "type": rtype, "per_page": 100})
    return api(token, "GET", f"{api_base()}?{q}") or []


def txt_value(content):
    """Cloudflare may return TXT content with surrounding quotes; compare unquoted."""
    c = content or ""
    if len(c) >= 2 and c[0] == '"' and c[-1] == '"':
        c = c[1:-1]
    return c


def matches(rec, name, rtype):
    """Defence in depth: act only on records whose name and type are exactly the requested ones."""
    return rec.get("name") == name and rec.get("type") == rtype


def ensure_one(token, name, rtype, content):
    if rtype == "TXT":
        content = txt_value(content)
    existing = [r for r in list_records(token, name, rtype) if matches(r, name, rtype)]
    want = {"type": rtype, "name": name, "content": content, "ttl": TTL}
    if rtype == "A":
        want["proxied"] = False
    if not existing:
        rec = api(token, "POST", api_base(), want)
        print(f"{rtype:3} {name}: created -> {content} (id {rec['id']})")
        return
    first, extras = existing[0], existing[1:]
    same_content = (txt_value(first["content"]) == content) if rtype == "TXT" else first["content"] == content
    same = same_content and first.get("ttl") == TTL and (rtype != "A" or first.get("proxied") is False)
    if same:
        print(f"{rtype:3} {name}: unchanged ({content})")
    else:
        api(token, "PATCH", f"{api_base()}/{first['id']}", want)
        print(f"{rtype:3} {name}: updated {first['content']!r} ttl={first.get('ttl')} "
              f"proxied={first.get('proxied')} -> {content!r} ttl={TTL}"
              + (" proxied=False" if rtype == "A" else ""))
    for rec in extras:
        api(token, "DELETE", f"{api_base()}/{rec['id']}")
        print(f"{rtype:3} {name}: deleted extra record {rec['content']!r} (id {rec['id']})")


def cmd_ensure(token, name, ip, txt):
    try:
        if ipaddress.ip_address(ip).version != 4:
            raise ValueError
    except ValueError:
        raise ApiError(f"not an IPv4 address: {ip}") from None
    ensure_one(token, name, "A", ip)
    ensure_one(token, name, "TXT", txt)


def cmd_show(token, name):
    found = False
    for rtype in ("A", "TXT"):
        for rec in list_records(token, name, rtype):
            found = True
            extra = f" proxied={rec.get('proxied')}" if rtype == "A" else ""
            print(f"{rtype:3} {name} {rec['content']} ttl={rec.get('ttl')}{extra}")
    if not found:
        print(f"no A/TXT records for {name}")


def cmd_delete(token, name):
    found = False
    for rtype in ("A", "TXT"):
        for rec in list_records(token, name, rtype):
            if not matches(rec, name, rtype):
                print(f"{rtype:3} {name}: skipped unexpected record {rec.get('type')} "
                      f"{rec.get('name')} (id {rec.get('id')})")
                continue
            found = True
            api(token, "DELETE", f"{api_base()}/{rec['id']}")
            print(f"{rtype:3} {name}: deleted {rec['content']!r} (id {rec['id']})")
    if not found:
        print(f"no A/TXT records for {name}; nothing deleted")


def main(argv):
    usage = "usage: dns.py ensure <name> <A-ip> <txt> | show <name> | delete <name>"
    if len(argv) < 2:
        print(usage, file=sys.stderr)
        return 2
    cmd, args = argv[1], argv[2:]
    expected = {"ensure": 3, "show": 1, "delete": 1}
    if cmd not in expected or len(args) != expected[cmd]:
        print(usage, file=sys.stderr)
        return 2
    try:
        name = fqdn(args[0])
    except UsageError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    try:
        token = load_token()
        if cmd == "ensure":
            cmd_ensure(token, name, args[1], args[2])
        elif cmd == "show":
            cmd_show(token, name)
        else:
            cmd_delete(token, name)
    except ApiError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
