# .secrets/

Local secrets for building and testing. Everything in this folder except this README is
git-ignored. Create files with `umask 077` (mode 600) and never print them.

| File | Required | Content | Used by |
|---|---|---|---|
| `deployment.properties` | for `--deploy` and live tests | The deployment file, see `deployment.properties.example` (`authkey`, `tag`, optional `authkey.expires` and `specs.prefix`). It holds the key, and so does every jar injected from it. | `scripts/build.sh --deploy`, live tests |
| `check-public.denylist` | for the export to the public repository | One real host name, domain or tailnet name per line, the private values that must never be published. Matched ASCII case-insensitively; blank lines and `#` comments are ignored (format: `scripts/check-public.example.txt`). Also stored, with the same text, as the `CHECK_PUBLIC_DENYLIST` Actions secret of the release workflow. | the export script and the release script, which pass it to `scripts/check-public.sh`; the release workflow writes it from the secret |
| `cf.curlcfg` | no | Cloudflare API token as a `curl -K` config line: `header = "Authorization: Bearer <token>"` | `scripts/dns.py` |

