# Build on macOS

One command builds the jar on a Mac (Apple Silicon or Intel). Nothing is installed
system-wide: the pinned Go and JDK go to `~/.local/toolchains`, staticcheck to `~/.local/go`.
No Homebrew, no Xcode Command Line Tools, no root.

## 1. Unpack

Unzip the source zip anywhere (double-click in Finder is fine) and open Terminal in that
folder:

```bash
cd ~/Downloads/cattail
```

If you only want the generic jar (no key, nothing secret; you inject it later as the README
describes), skip step 2 and leave out `--deploy` in step 3.

## 2. The deployment file

This is the only thing you type in: your Tailscale key and player tag, in
`.secrets/deployment.properties` (the README's "Which key" section says which key to use).
Create the file owner-only and type into it straight from Terminal:

```bash
mkdir -p .secrets
(umask 077; cat > .secrets/deployment.properties)
```

Type or paste these two lines with your own key and tag in place of the placeholders, press
Enter, then Ctrl-D:

```
authkey=tskey-auth-EXAMPLE
tag=tag:mc-player
```

The optional settings (`authkey.expires`, `specs.prefix`) are explained in
`deployment.properties.example`; add them as extra lines if you want them. To fix a mistake, run
the `cat` line again: it replaces the file.

Do not use `echo` (it lands in your shell history). The file is git-ignored. The build script
reads only its `tag` line, to name the jar; the jar's `inject` command reads the rest. Nothing
prints the key.

## 3. Build

```bash
scripts/build-mac.sh --deploy .secrets/deployment.properties
```

The first run downloads Go (about 70 MB), the JDK (about 190 MB), staticcheck, Gradle and
NeoForge, so allow ten minutes and keep the network on. Later runs reuse all of it.

What runs: `go vet`, `staticcheck`, `go test`, the Gradle build with the Java tests, the client
smoke tests, then the generic jars, then `inject` with your deployment file. The client smoke
tests start the game twice, with and without Plasmo Voice: each time a game window opens on
your desktop and stays until the test stops it, so leave it alone while the build runs.
`scripts/build-mac.sh --skip-tests --deploy .secrets/deployment.properties` skips the test
suites for a quick build.

Output (`<version>` is the mod and Minecraft version, for example `0.1.0+mc1.21.1`; `<tag>` is
your tag without `tag:`):

```
dist/cattail-<version>.jar                 generic: no key, not secret
dist/cattail-<version>-novoice.jar         generic, without the nested Plasmo Voice
dist/cattail-<version>-<tag>.jar           yours: carries the key
dist/cattail-<version>-novoice-<tag>.jar   yours, without Plasmo Voice: carries the key
dist/SHA256SUMS
```

Without a `.git` folder the helper version ends in `+nogit` (for example `0.1.0+nogit`), which
is fine for a test jar. The `-<tag>` jars hold your key: they are mode `600`; give one to your
players privately and run it on your server.

## 4. Next time

Same folder, same command. If you unpack a new zip, copy `.secrets/` over from the old folder
(or redo step 2).

## If something fails

- `checksum mismatch`: the download was corrupted or altered. Run again; if it repeats, do not
  use that toolchain.
- `inject refused the deployment file`: with exit 2, the line above it says what is wrong with
  the file (the setting and the rule it broke, never a value from it); fix
  `.secrets/deployment.properties` (run the `cat` line from step 2 again) and build again. With
  exit 1, a file could not be read or written; the line above names the file and the reason.
  Either way the generic jar was built.
- "macOS cannot verify the developer" never appears: the toolchain is unpacked from tarballs
  by `tar`, which adds no quarantine flag.
- A Gradle failure `missing bundled helper resource(s)` means the Go step did not write the
  helpers; read the first error above it.
