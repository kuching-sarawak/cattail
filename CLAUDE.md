# CLAUDE.md

Notes for coding agents working in this repository.

## What this is

Cattail: a NeoForge 1.21.1 mod (`mod/`, Java 21, package `net.kuching.cattail`) that bundles a
Go `tsnet` helper (`helper/`, module `github.com/kuching-sarawak/cattail/helper`) so players
join a Minecraft server over the owner's tailnet without installing Tailscale. Start with
[README.md](README.md); the helper protocol is the doc comment in `main.go`.

## Where things are

| Path | Content |
|---|---|
| `helper/cmd/cattail-helper/main.go` | Helper entry point; its doc comment is the stdout/stdin protocol and exit codes |
| `helper/internal/node/` | tsnet node, hostname sanitising, log rotation, opt-in live test (`-tags integration`) |
| `helper/internal/relay/`, `helper/internal/proto/` | TCP/UDP relays, protocol lines |
| `mod/src/main/java/net/kuching/cattail/dns/RouteDecider.java` | Which servers are routed |
| `mod/src/main/java/net/kuching/cattail/helper/` | Helper extraction, supervision, probe run |
| `mod/src/main/java/net/kuching/cattail/mixin/` | Connect path, server list, status line |
| `mod/src/main/java/net/kuching/cattail/config/CattailConfig.java` | Client config keys |
| `mod/src/main/java/net/kuching/cattail/deploy/` | Deployment file parsing and the `inject` command |
| `mod/src/main/java/net/kuching/cattail/specs/` | `cattail:sysinfo` payload and server log line |
| `mod/src/main/java/net/kuching/cattail/voice/` | Server-side check of the bundled Plasmo Voice config |
| `scripts/build.sh` | The only way to build the shippable jars (full and `-novoice`) |
| `scripts/gen-helper-notices.sh` | Regenerates `helper/THIRD_PARTY_LICENSES.md` from `go.mod`; `build.sh` checks it |
| `scripts/check-public.sh` | Public-tree gate run by the lint, the export and the release: a denylist plus built-in rules (self-test `check-public-test.sh`) |
| `scripts/release-public.sh` | Maintainers only, not in the public tree: the only path to a public release (builds and gates the generic jar, opens the public draft pull request and draft release; run by the release workflow; self-test `release-public-test.sh`) |
| `scripts/` | Toolchain, smoke tests, live tests, Cloudflare DNS helper, `readme-lint.sh` (lint for README.md and PLAYERS.md; self-test `readme-lint-test.sh`) |
| `policy/` | Example tailnet policy |

## Commands

```bash
source scripts/env.sh                      # every shell
cd helper && go build ./... && go vet ./... && staticcheck ./... && go test ./...
cd mod && ./gradlew test -PskipHelper
# -PskipHelper skips the helper-dependent tests (48 of 207); only scripts/build.sh runs them all
scripts/smoke-client.sh -PskipHelper       # needs Xvfb
scripts/smoke-server.sh -PskipHelper       # add --novoice for the no-voice run; smoke-client.sh takes it too
scripts/e2e-update-notice.sh -PskipHelper  # live dev server (9.9.9) + dev client: the update notice; needs a display
scripts/readme-lint.sh README.md PLAYERS.md # after editing either
cd mod && ./gradlew test -PskipHelper      # also after editing README.md: ModLogoTest reads it
cd helper && go get tailscale.com@v<x.y.z> && go mod tidy && cd .. && scripts/gen-helper-notices.sh   # bump tsnet, then the Go line above
scripts/build.sh --skip-tests              # generic jar; needs nothing in .secrets/
scripts/build.sh --allow-dirty             # from uncommitted changes; a clean tree is required otherwise
java -jar dist/cattail-<version>.jar inject .secrets/deployment.properties
scripts/build-mac.sh                       # macOS: toolchain install + build.sh in one go
scripts/release-public.sh v<mod_version> --dry-run --skip-build dist   # maintainers only, not in the public tree; macOS: dry run only
```

All of `go vet`, `staticcheck` and `-Xlint:deprecation -Werror` are mandatory.

## Rules

- The key reaches the helper only through `TS_AUTHKEY` and reaches a jar only through
  `inject`. `deployment.properties` and injected jars are secrets; generic jars are not. Never
  read or print anything in `.secrets/`.
- `dist/` and `mod/src/main/resources/helper/` are build output; never commit them. An injected
  jar (`dist/cattail-<version>-<tag>.jar`, where `<version>` is the whole version in the generic
  jar's name, such as `0.1.0+mc1.21.1`) carries the key: it goes only to the server and its
  players, privately.
- No host names, Tailscale IPs, domains or tailnet names of a real deployment in code, tests
  or docs. Use placeholders and documentation addresses (`100.101.102.103`, `192.0.2.x`,
  `example.com`). `scripts/check-public.sh` enforces this: any other `100.x.y.z` address, any
  MagicDNS name (a tailnet domain under ts[.]net) and any Tailscale key (the `tskey` prefix plus
  a dash) other than the two `EXAMPLE` placeholders in that script fail the gate, as does the
  owner's denylist. Deployment values go in `.secrets/deployment.properties` or the
  environment; `build.conf` holds only the probe build's targets.
- Live tailnet tests are opt-in (`CATTAIL_TEST_TARGET`); do not run them unless asked.
- `scripts/release-public.sh` and its workflow are the only path to a public release, and the
  two generic jars (full and `-novoice`) are the only jars that may be attached to a public release.
- Behaviour changes need tests; keep the helper protocol in `main.go`'s doc comment up to date.
- `README.md` is under test: `ModLogoTest` requires its logo `<img>` to be `mod/src/main/resources/cattail.png` scaled by a whole number, with `width` a multiple of 50.
