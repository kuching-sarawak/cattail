# Third-party notices

Cattail is licensed under the MIT License (see `META-INF/licenses/LICENSE` in the jar or `LICENSE`
in the repository). This file covers the Plasmo Voice mod nested in the Cattail jar, which is
distributed under its own license as listed below, and points to the licences of the Go modules
linked into the helper.

## Cattail helper (Go modules)

`cattail-helper.exe`, nested in the jar, is a statically linked Go binary built from
`tailscale.com` and the modules it depends on (BSD, MIT and Apache-2.0 licences; no
copyleft). Their full licence texts and the Go runtime licence are in
`META-INF/licenses/THIRD_PARTY_LICENSES.md` in the jar (`helper/THIRD_PARTY_LICENSES.md` in the
repository), generated from `helper/go.mod` by `scripts/gen-helper-notices.sh`.

## Plasmo Voice 2.1.17

- Copyright: the Plasmo Voice authors
- License: GNU Lesser General Public License v3.0 (LGPL-3.0), which supplements the GNU General
  Public License v3.0 (GPL-3.0)
- Source: https://github.com/plasmoapp/plasmo-voice/tree/2.1.17
- Bundled as an unmodified upstream release inside the standard Cattail jar under
  `META-INF/jarjar/`. The `-novoice` jar does not nest Plasmo Voice and carries neither this
  section's license texts nor anything else of it. To use
  a different build, place a standalone Plasmo Voice jar whose version is within [2.1.17, 2.2) in
  the `mods` folder; NeoForge then loads that jar instead of the nested copy. A standalone jar
  outside that range makes NeoForge refuse to start, because Cattail declares that version range.
- License texts in the jar: `META-INF/licenses/LGPL-3.0.txt` and `META-INF/licenses/GPL-3.0.txt`.
  This notice is in the jar at `META-INF/licenses/THIRD_PARTY_NOTICES.md`.
