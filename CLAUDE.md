# CLAUDE.md

Notes for coding agents working in this repository.

## What this is

Cattail: a NeoForge 1.21.1 mod (`mod/`, Java 21, package `net.kuching.cattail`) that bundles a
Go `tsnet` helper (`helper/`, module `github.com/kuching-sarawak/cattail/helper`), built once per
supported player platform (Windows x64, Linux x64, macOS on Apple silicon), so players join a
Minecraft server over the owner's tailnet without installing Tailscale. Start with
[README.md](README.md); the helper protocol is the doc comment in `main.go`.

## Where things are

| Path | Content |
|---|---|
| `helper/cmd/cattail-helper/main.go` | Helper entry point; its doc comment is the stdout/stdin protocol and exit codes |
| `helper/internal/node/` | tsnet node, hostname sanitising, log rotation, opt-in live test (`-tags integration`) |
| `helper/internal/relay/`, `helper/internal/proto/` | TCP/UDP relays, protocol lines |
| `mod/src/main/java/net/kuching/cattail/dns/RouteDecider.java` | Which servers are routed |
| `mod/src/main/java/net/kuching/cattail/helper/` | Helper extraction, supervision, probe run |
| `mod/src/main/java/net/kuching/cattail/helper/HelperTarget.java` | The one place that maps OS and arch to a bundled helper; mirrored by `HELPER_TARGETS` in `build.sh` (which `gen-helper-notices.sh` reads), `helperTargets` in `build.gradle` and `HELPER_ENTRIES` in `release-public.sh`: all four change together |
| `mod/src/main/java/net/kuching/cattail/mixin/` | Connect path, server list (the server-list icon and its tooltip), status line, the status JSON's `cattail` object (both sides) |
| `mod/src/main/java/net/kuching/cattail/config/` | Client config keys and the dedicated server's `cattail-server.toml` |
| `mod/src/main/java/net/kuching/cattail/serverlist/` | The status JSON `cattail` object, its sanitising, the tooltip composition |
| `mod/src/main/java/net/kuching/cattail/deploy/` | Deployment file parsing and the `inject` command |
| `mod/src/main/java/net/kuching/cattail/specs/` | `cattail:sysinfo` payload, the configuration-phase request and the server log line |
| `mod/src/main/java/net/kuching/cattail/voice/` | Server-side check of the bundled Plasmo Voice config |
| `scripts/build.sh` | The only way to build the shippable jars (full and `-novoice`, each with the three helpers) |
| `scripts/bump-version.sh` | The only way to change the version: `mod_version` in `mod/gradle.properties`, `update.json` and the jar names in `docs/listing.md` together, then `UpdateJsonTest` (self-test `bump-version-test.sh`) |
| `scripts/bump-tsnet.sh` | The way to update tsnet: `tailscale.com` in `helper/go.mod` and `go.sum` and `helper/THIRD_PARTY_LICENSES.md` together, under `GOTOOLCHAIN=local` (self-test `bump-tsnet-test.sh`) |
| `.github/workflows/tsnet-bump.yml`, `tsnet-release.yml`, `scripts/tsnet-release.sh` | Maintainers only, not in the public tree: weekly `bump-tsnet.sh` (plus the next patch version when `tsnet-release.sh plan` allows) and the full build, then the pull request `tsnet/<version>`; merging it releases that version through `tsnet-release.sh release` and `release-public` (`docs/build.md`, tsnet updates; self-test `tsnet-release-test.sh`) |
| `scripts/gen-helper-notices.sh` | Regenerates `helper/THIRD_PARTY_LICENSES.md` from `go.mod`; `build.sh` checks it |
| `scripts/check-public.sh` | Public-tree gate run by the lint, the export and the release: a denylist plus built-in rules (self-test `check-public-test.sh`) |
| `scripts/release-public.sh` | Maintainers only, not in the public tree: the only path to a public release (builds and gates the generic jars, opens the public draft pull request and draft release; `--publish` merges and publishes them, `--distribute` sends the published jars to the mod sites; run by the release workflow; self-test `release-public-test.sh`) |
| `scripts/distribute.py`, `distribute.conf` | Maintainers only, not in the public tree: the Modrinth and CurseForge uploads (`upload` is run only by `release-public.sh --distribute`; `create-project` is the owner's one-time Modrinth setup; project ids in the conf; self-test `distribute_test.py`) |
| `docs/listing.md` | Maintainers only, not in the public tree: the Modrinth page body (the tag's copy is synced on every distribute run) and the CurseForge description (pasted by hand); gated with `check-public.sh` by `release-public.sh --distribute` |
| `scripts/` | Toolchain, smoke tests, live tests, Cloudflare DNS helper, `readme-lint.sh` (lint for README.md and PLAYERS.md; self-test `readme-lint-test.sh`) |
| `policy/` | Example tailnet policy |

## Commands

```bash
source scripts/env.sh                      # every shell
cd helper && go build ./... && go vet ./... && staticcheck ./... && go test ./...
cd mod && ./gradlew test -PskipHelper
# -PskipHelper skips only the check that the bundled helpers exist (scripts/build.sh writes them; no
# test needs them). The 48 tests that run fake-helper.sh need Linux or macOS: macOS runs all 282,
# Linux 281 (one chflags test is macOS only)
scripts/smoke-client.sh -PskipHelper       # Linux: under Xvfb; macOS: on the desktop (a game window opens)
scripts/smoke-server.sh -PskipHelper       # --novoice (anywhere in the arguments) for the no-voice run; smoke-client.sh takes it too
scripts/e2e-update-notice.sh -PskipHelper  # live dev server (9.9.9) + dev client: the update notice; needs a display
scripts/e2e-specs.sh -PskipHelper          # live dev server + dev client: the specs line before the join, on the server thread, with the address; needs a display
scripts/e2e-server-list.sh -PskipHelper   # dev server: the cattail object in the status JSON, live reload of cattail-server.toml; no display needed; CATTAIL_E2E_EXTRA_MODS=<dir of jars> adds other mods (BCC, NCR) to prove coexistence
scripts/readme-lint.sh README.md PLAYERS.md # after editing either
cd mod && ./gradlew test -PskipHelper      # also after editing README.md: ModLogoTest reads it
scripts/bump-tsnet.sh [v<x.y.z>]          # bump tsnet (default: the latest release), then scripts/build.sh --allow-dirty and the forced-relay soak; exit 3 = needs a newer pinned Go
scripts/bump-tsnet-test.sh                 # after editing bump-tsnet.sh (needs env.sh; offline, a fixture module proxy)
scripts/tsnet-release.sh release <commit> --dry-run   # maintainers only: what merging a tsnet pull request at <commit> would release
scripts/tsnet-release-test.sh              # after editing tsnet-release.sh (a fixture repository and a gh stub)
scripts/bump-version.sh <version>          # new release: mod_version, update.json and the listing's jar names together, then UpdateJsonTest
scripts/build.sh --skip-tests              # generic jar; needs nothing in .secrets/
scripts/build.sh --allow-dirty             # from uncommitted changes; a clean tree is required otherwise
java -jar dist/cattail-<version>.jar inject .secrets/deployment.properties
scripts/build-mac.sh                       # macOS: toolchain install + build.sh in one go
scripts/release-public.sh v<mod_version> --dry-run --skip-build dist   # maintainers only, not in the public tree; macOS: dry run only
scripts/release-public.sh v<mod_version> --publish --dry-run           # maintainers only: prints the merge and publish commands; without --dry-run it merges and publishes (macOS too)
scripts/release-public.sh v<mod_version> --distribute --dry-run        # maintainers only: needs CATTAIL_MODRINTH_TOKEN / CATTAIL_CURSEFORGE_TOKEN in the environment for the enabled sites (exit 2 otherwise); a draft release is enough in a dry run; with no public release at all it prints a note and exits 0 without checking the tokens; reads the sites, uploads nothing
scripts/release-public-test.sh && python3 -I scripts/distribute_test.py   # after editing either script
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
  two generic jars (full and `-novoice`, each with the three helpers and nothing else under
  `helper/`) are the only jars that may be attached to a public release or uploaded to a mod
  site; a mod-site upload goes only through `release-public.sh --distribute`, which takes the
  jars from the published public release, never from `dist/`.
- Behaviour changes need tests; keep the helper protocol in `main.go`'s doc comment up to date.
- `README.md` is under test: `ModLogoTest` requires its logo `<img>` to be `mod/src/main/resources/cattail.png` scaled by a whole number, with `width` a multiple of 50.
