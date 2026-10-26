# Contributing

## Toolchain

Linux x86-64 or macOS. Install the pinned toolchain once, then load it in every shell:

```bash
bash scripts/install-toolchain.sh   # Go 1.27.3, Temurin JDK 21, staticcheck 2026.2.2
source scripts/env.sh
```

What the host needs (curl, tar and a SHA-256 tool) is in the header of
`scripts/install-toolchain.sh`; the headless client smoke test also needs Xvfb (Linux only).
Build flags are described in `scripts/build.sh`, the deployment file in
`deployment.properties.example`, and the probe build's targets (the only `build.conf` key) in
`build.conf.example`.

## Gates

Every change must pass these before it is committed:

```bash
cd helper && go build ./... && go vet ./... && staticcheck ./... && go test ./...
cd mod && ./gradlew test -PskipHelper
```

- `go vet` and `staticcheck` must be clean. `scripts/build.sh` also runs `go vet` with
  `GOOS=windows`, and with `--probe` it runs `go vet` and `staticcheck` with `-tags probe`;
  run those by hand when you touch platform- or probe-specific code.
- Java compiles with `-Xlint:deprecation -Werror`.
- After any change to `helper/go.mod`, run `scripts/gen-helper-notices.sh` and commit the
  regenerated `helper/THIRD_PARTY_LICENSES.md`; `scripts/build.sh` fails when it is stale.
- For changes to mixins or loading: `scripts/smoke-client.sh -PskipHelper` and
  `scripts/smoke-server.sh -PskipHelper`.
- When touching `scripts/check-public.sh`, run `scripts/check-public-test.sh`.
- When touching the release script (`scripts/release-public.sh`, maintainers only, not in the
  public tree), run `scripts/release-public-test.sh`; it needs `zip`.
- When touching `README.md` or `PLAYERS.md`, run `scripts/readme-lint.sh README.md PLAYERS.md`;
  when touching the lint, run `scripts/readme-lint-test.sh`.
- Changes to the helper's tailnet behaviour: run the opt-in live tests (`CATTAIL_TEST_TARGET`
  with `scripts/helper-live-test.sh` and `scripts/helper-udp-test.sh`, which read the key and
  tag from `.secrets/deployment.properties`) against your own tailnet if you can, and say in
  the pull request whether you did.

## Rules

- No secrets in the tree, in argv, or in output. The key reaches the helper only through
  `TS_AUTHKEY` and reaches a jar only through `inject`. `deployment.properties` and injected
  jars are secrets; generic jars are not. Never read or print anything in `.secrets/`
  (git-ignored).
- No deployment-specific values (host names, Tailscale IPs, domains, tailnet names) in code,
  tests or docs. Use placeholders like `<your-server-hostname>`, documentation addresses
  (`192.0.2.x`, `198.51.100.x`, `203.0.113.x`, `100.101.102.103`) and `example.com`.
  Settings belong in the deployment file or the environment; `build.conf` holds only the probe
  build's targets.
- Keep mixins non-invasive: `@WrapOperation` / `@Inject`, never `@Redirect`, and pass
  non-Cattail traffic through untouched.
- Updating `tailscale.com` is a deliberate change with a soak test over a forced relay.

## Commits

Small commits with a conventional prefix and an optional scope, imperative mood:

```
feat(dns): zone and subdomain from environment
fix(helper): close the UDP relay on idle
docs: public documentation set
test: live tailnet tests opt-in
```

Common prefixes: `feat`, `fix`, `refactor`, `test`, `docs`, `build`.
