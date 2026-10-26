<p align="center"><img src=".github/cattail-logo.png" width="100" alt="Cattail logo"></p>

*Players join your Minecraft server over your Tailscale network without installing Tailscale.*

[![Latest release](https://img.shields.io/github/v/release/kuching-sarawak/cattail)](https://github.com/kuching-sarawak/cattail/releases) [![Minecraft 1.21.1](https://img.shields.io/badge/Minecraft-1.21.1-brightgreen)](https://github.com/kuching-sarawak/cattail/releases) [![NeoForge 21.1](https://img.shields.io/badge/NeoForge-21.1-orange)](https://github.com/kuching-sarawak/cattail/releases) [![License](https://img.shields.io/github/license/kuching-sarawak/cattail)](LICENSE)

# Cattail

## What Cattail does

**For you, the server owner.** Your Minecraft server is on your Tailscale network (your
tailnet). You set Cattail up once: a few lines in your tailnet policy, a Tailscale key, two DNS
records, and one command that writes the key into the Cattail jar. Then you send that jar to your
players.

**For your players.** A player puts one jar in the `mods` folder and adds your server by its
name, as with any server. The server list shows a `Tailscale Tunnel` label next to your server.
Proximity voice chat (Plasmo Voice) is built into the standard jar; a no-voice jar without it is
on every release too. Players need no Tailscale account, no Tailscale
app and no admin rights. The one exception is an optional Windows Firewall prompt, which asks for
an administrator. Players need a Windows x64 PC (see [Supported today](#supported-today)).

**On the server, the jar is optional.** If your server is already on your tailnet, players join
without any change on the server: the tunnel is on the player's side, and the server sees an
ordinary connection from a tailnet address. Put the jar in the server's `mods` folder only if you
want what it adds there: the server half of proximity voice, and the start-up checks and log
lines (version, key expiry, voice config, player specs). If you do not care about voice or the
logs, the jar only needs to be distributed to your players.

Cattail is not affiliated with Tailscale Inc., Mojang or Microsoft.

## Supported today

| Side | Supported | Notes |
|---|---|---|
| Client | NeoForge 21.1.x on Minecraft 1.21.1, Java 21 | The mod manifest pins `[1.21.1]` |
| Client OS | Windows x64 | Other OSes: the mod loads, shows `Cattail supports Windows only`, and the game connects directly, which needs that player's own Tailscale |
| Server | NeoForge 1.21.1 dedicated server with the Cattail jar | Full: joins, voice, start-up checks, specs log |
| Server (other) | Any 1.21.1 server with Tailscale on the host | Joins should work since the server only sees TCP from a Tailscale address. Untested. No voice without a Plasmo Voice 2.1.x server |
| Voice | Plasmo Voice 2.1.17, bundled in the standard jar | Standalone Plasmo in `[2.1.17, 2.2)` is accepted; outside that range NeoForge refuses to start. The `-novoice` jar bundles nothing and has no voice |
| `inject` | Java 21 or later, any OS | A JRE is enough |

More Minecraft versions and more client platforms are planned as a separate effort. There are no
dates.

## Contents

- [How it works](#how-it-works)
- [Before you start](#before-you-start)
- [Set up, step by step](#set-up-step-by-step)
- [Which key: auth key or OAuth client](#which-key-auth-key-or-oauth-client)
- [Configuration reference](#configuration-reference)
- [Security: what is in the jar](#security-what-is-in-the-jar)
- [Operations](#operations)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [Support](#support)
- [Contributing](#contributing)
- [Acknowledgements](#acknowledgements)
- [License](#license)

## How it works

```mermaid
flowchart LR
  G[Player's game] --> H[Bundled helper<br>a tsnet node on your tailnet]
  H --> T((Your tailnet))
  T --> S[Your server<br>100.101.102.103:25565]
```

The Cattail jar carries a small program, the helper. It is built on tsnet, Tailscale's library
for running a Tailscale node inside a program, so the player installs no Tailscale app and no
driver. The player's game starts the helper when the player joins a Cattail server, or earlier,
when the Multiplayer list opens and already holds one. It stops the helper when the game quits.
Each running game is one ephemeral node on your tailnet, named `mc-<player name>` and carrying
your player tag.

Your public DNS points your server's name at its Tailscale address (an A record) and marks the
name as a Cattail server (a TXT record `cattail=1`). The mod routes only servers that have both;
every other server connects as usual. Your server sees an ordinary Minecraft connection from a
tailnet address.

How the mod and the helper talk to each other is described at the top of
[helper/cmd/cattail-helper/main.go](helper/cmd/cattail-helper/main.go).

## Before you start

### To set up a server

All of these are required.

- A Tailscale account with the Owner, Admin or Network admin role on the tailnet your server is
  on. These roles can edit the tailnet policy and create keys. An IT admin can create keys but
  cannot edit the policy.[^roles]
- A Tailscale plan with room for your players. The free Personal plan works. Its limits are up
  to 6 users, unlimited user devices, up to 50 tagged resources, and 1,000 ephemeral-node minutes
  per month.[^pricing] Tailscale says it is not enforcing the tagged-resource and
  ephemeral-resource limits yet.
  - Players are not users of your tailnet: they never log in to Tailscale.
  - Every player session is one ephemeral node for as long as the game runs. 1,000 minutes is
    about 16 hours of play per month, counted over all players together, so a tailnet with
    regular players will exceed it.
- Tailscale installed and logged in on the server host, and the host's Tailscale IPv4 address (it
  starts with `100.`).
- A DNS name you control, at a DNS provider where you can add A and TXT records.
- Java on the machine where you run `inject` (the version is in
  [Supported today](#supported-today); a JRE is enough). Any OS works, and the server host is
  fine. If you have none, install a JRE from [adoptium.net](https://adoptium.net/temurin/releases/).
- A NeoForge dedicated server on the versions in [Supported today](#supported-today), running
  on Java 21. Other servers may work for joins only; see the table.

### To build from source

Optional. The jar from the Releases page is all you need to set up a server.

- Linux x86-64 or macOS (Apple Silicon or Intel). The build scripts do not run on Windows.
- `curl`, `tar` and a SHA-256 tool (`sha256sum` on Linux, `shasum` on macOS).
- `git`, recommended: in a git checkout the build stamps the helper with the current commit.
- The pinned toolchain (Go, a Temurin JDK and staticcheck), installed per user by
  `scripts/install-toolchain.sh`. It needs no root.
- On Linux, Xvfb for the headless client smoke test (Debian and Ubuntu packages `xvfb` and
  `libgl1-mesa-dri`). macOS skips that test.
- Network access and some time. The first run downloads the toolchain, Gradle, NeoForge and
  Plasmo Voice: allow about ten minutes. Later builds reuse the downloads.

The steps are in [Building from source](#building-from-source). On a Mac,
[BUILD-MACOS.md](BUILD-MACOS.md) does the whole build in one command.

[^roles]: Tailscale, [User roles](https://tailscale.com/kb/1138/user-roles), checked 2026-10-21.
[^pricing]: Tailscale, [Pricing](https://tailscale.com/pricing), checked 2026-10-21.

## Set up, step by step

Do these in order. Allow about 30 minutes, plus the time your DNS records take to spread. The
examples use placeholders: `mc.example.com` for your server's name, `100.101.102.103` for its
Tailscale address, `25565` for its game port and `tag:mc-player` for the player tag. Use your own
values instead.

### 1. Install Tailscale on the server host

Install Tailscale on the machine that runs your Minecraft server and log in to your tailnet.
Then note two values: the host's Tailscale IPv4 address and the server's game port.

<details><summary>Details</summary>

Any normal Tailscale install works. On Linux:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up
```

`tailscale up` prints a URL. Open it and log in. On Windows and macOS, use the installer from
[tailscale.com/download](https://tailscale.com/download).

Find the Tailscale IPv4 address on the host:

```bash
tailscale ip -4
```

It prints one address, such as `100.101.102.103`. The Machines page of the admin console lists
the same address.

The game port is `server-port` in the server's `server.properties`. The default is `25565`.

If the host runs a firewall, allow the game port for TCP (the game) and UDP (voice) on the
Tailscale interface: `tailscale0` on Linux, the Tailscale adapter on Windows. If the server runs
in a container, publish the game port for UDP as well as TCP (for example `25565/udp`), or voice
will not reach it.

</details>

### 2. Edit the tailnet policy

The tailnet policy decides what player nodes may reach. Give the player tag the game port on
your server and nothing else. Merge [policy/policy.example.hujson](policy/policy.example.hujson)
into your policy as [policy/README.md](policy/README.md) explains. **If your policy still has
Tailscale's default allow-all rule, replace it:** that rule lets every player node reach every
device on your tailnet.

<details><summary>Details</summary>

In the admin console, open **Access controls**. Save a copy of your current policy before you
change it.

Add these parts, with your own tag, address and port:

```hujson
"tagOwners": {
  "tag:mc-player": ["autogroup:admin"],
},
"hosts": {
  "minecraft": "100.101.102.103",
},
"grants": [
  {"src": ["tag:mc-player"], "dst": ["minecraft"], "ip": ["tcp:25565", "udp:25565"]},
],
```

- `tagOwners` creates the player tag. The tag must exist here before you can create a key that
  carries it.
- `hosts` gives the server a name for the grant. Any name works.
- The grant lets player nodes reach the game port: TCP for the game, UDP for voice. If your TXT
  record will set `voice=<port>`, grant `udp:<port>` instead. With `voice=0`, leave UDP out (see
  step 4).
- No rule may name the player tag, `*` or `autogroup:tagged` as a destination. Then nothing can
  open a connection to a player node.

Then check your other rules. If your policy still has the default allow-all rule
(`"src": ["*"], "dst": ["*"]`, as a grant or as an `acls` entry), remove it: `*` includes player
nodes. The same goes for any rule whose `src` is `*` or `autogroup:tagged`. In place of the
allow-all rule, add this member-to-member grant so that your own devices still reach each other:

```hujson
{"src": ["autogroup:member"], "dst": ["autogroup:member"], "ip": ["*"]},
```

Use it only to replace an allow-all rule. A policy without one does not need it, and adding it
there would let every member's device reach every other member's device, more than your policy
allows now.

Copy the `tests` block from the example too, and add the other ports your server runs (SSH, web
panels, databases) to its `deny` list. Save. Tailscale runs the tests on every save and refuses
a policy that fails one.

If the server itself is a tagged device, `autogroup:member` does not cover it. Step 7 of
[policy/README.md](policy/README.md) explains what to add.

</details>

### 3. Create the key

The key lets each player's helper join your tailnet as a player node. There are two kinds: an
auth key, which expires after at most 90 days, and an OAuth client secret, which never expires.
[Which key](#which-key-auth-key-or-oauth-client) compares them and has a click-by-click guide for
each. If you are not sure, create an [auth key](#auth-key) for 90 days and set a reminder for
the date. Copy the key when Tailscale shows it: it is shown once. Write it straight into
`deployment.properties` as the Details of [step 5](#5-inject-your-key-into-the-jar) show, and
never into chat or email.

<details><summary>Details</summary>

| Setting | Auth key | OAuth client |
|---|---|---|
| Admin console page | Keys | Trust credentials |
| Options | **Reusable**, **Ephemeral**, **Pre-approved**, **Tags**: `tag:mc-player` | **Write** for the **Auth Keys** scope (`auth_keys`), tag `tag:mc-player` |
| Expiry | 1 to 90 days; note the date | None |
| In `deployment.properties` | `authkey=tskey-auth-EXAMPLE` and `authkey.expires=<the date>` | `authkey=tskey-client-EXAMPLE` |

With an auth key, players cannot join after the expiry date until you create a new key, inject
it (step 5) and send them the new jar. Set a reminder.

Keep the key out of chat, tickets and shell command lines. It goes only into
`deployment.properties` (step 5).

</details>

### 4. Create the DNS records

Add two records for the name your players will type: an A record that points at the server's
Tailscale address, and a TXT record that marks the name as a Cattail server. Any DNS provider
works. Do not proxy the A record.

<details><summary>Details</summary>

| Type | Name | Content | TTL |
|---|---|---|---|
| A | `mc.example.com` | `100.101.102.103` | 300 |
| TXT | `mc.example.com` | `cattail=1; target=100.101.102.103` | 300 |

The TXT content is a list of `key=value` fields separated by `;`:

- `cattail=1` marks the name as a Cattail server. It is required.
- `target=` repeats the Tailscale address. Some home routers drop DNS answers in the Tailscale
  range (`100.64.0.0/10`) as protection against DNS rebinding. The mod then takes the address
  from `target=`. Keep it equal to the A record.
- `voice=` is optional. Leave it out when the voice server listens on the game port, which is
  Plasmo Voice's default. Set it to send voice to another UDP port, or to `0` to turn voice off.

| TXT content | Voice |
|---|---|
| `cattail=1; target=100.101.102.103` | On, at the game port (the default) |
| `cattail=1; target=100.101.102.103; voice=24454` | On, at UDP port 24454, where your voice server listens; grant `udp:24454` in step 2 |
| `cattail=1; target=100.101.102.103; voice=0` | Off |

The A record must point at the Tailscale address itself. If your provider offers a proxy (such as
Cloudflare's orange cloud), turn it off for this record ("DNS only"). A proxy address is not in
the Tailscale range, and the mod would not route the name. A Tailscale address cannot be reached
from the public internet, so the record lets no one in.

Check both records on Linux or macOS:

```bash
dig +short mc.example.com A
dig +short mc.example.com TXT
```

Expected output, one line per command:

```text
100.101.102.103
"cattail=1; target=100.101.102.103"
```

On Windows, in PowerShell or Command Prompt:

```powershell
nslookup mc.example.com
nslookup -type=TXT mc.example.com
```

The first answer lists `100.101.102.103`, the second `cattail=1; target=100.101.102.103`.

If the A check prints nothing, your own resolver may filter answers in the Tailscale range. Ask a
public resolver instead: `dig +short @1.1.1.1 mc.example.com A` on Linux or macOS,
`nslookup mc.example.com 1.1.1.1` on Windows.

Players must type the name, not the address: an IP address is never routed. The mod remembers its
decision for each name until the game quits, so players restart the game after a DNS change.

</details>

### 5. Inject your key into the jar

Download a generic jar and `SHA256SUMS` from
[Releases](https://github.com/kuching-sarawak/cattail/releases) and check the jar against it.
Every release has two generic jars: `cattail-<version>.jar` with proximity voice built in, and
`cattail-<version>-novoice.jar`, the same mod without Plasmo Voice, for owners who do not want
voice (it is about half the size). Pick one; the steps below are the same for both. A generic
jar holds no key and is not secret. Write your key and tag into a private file,
`deployment.properties`, and run the jar's `inject` command. It writes a copy of the jar that
carries your key. That copy is the jar your server and your players run.

<details><summary>Details</summary>

Below, `<version>` is the version part of the file name you downloaded, as in
`cattail-<version>.jar`. With the no-voice jar, read `cattail-<version>-novoice.jar` wherever
`cattail-<version>.jar` appears, and the injected copy is `cattail-<version>-novoice-<tag>.jar`.

**Check the download.** In the folder that holds both files, on Linux:

```bash
sha256sum -c SHA256SUMS
```

On macOS:

```bash
shasum -a 256 -c SHA256SUMS
```

Both print `cattail-<version>.jar: OK`. On Windows, in PowerShell:

```powershell
(Get-FileHash .\cattail-<version>.jar -Algorithm SHA256).Hash
Get-Content .\SHA256SUMS
```

The two hashes must match. PowerShell prints its hash in capitals.

**Write `deployment.properties`.** It holds your key, so only you may read it. On Linux and
macOS, create it owner-only and type into it straight from the terminal:

```bash
(umask 077; cat > deployment.properties)
```

Paste the lines, press Enter, then Ctrl-D. With an auth key:

```properties
authkey=tskey-auth-EXAMPLE
authkey.expires=2026-12-31
tag=tag:mc-player
```

With an OAuth client secret, leave out `authkey.expires`:

```properties
authkey=tskey-client-EXAMPLE
tag=tag:mc-player
```

`authkey.expires` is optional: set it to the expiry date the Keys page showed. `tag` must be the
tag your key carries. [deployment.properties.example](deployment.properties.example) shows the
format with comments, and [Configuration reference](#configuration-reference) lists every key.

To fix a mistake, run the `cat` line again: it replaces the file. Do not use `echo`, which puts
the key in your shell history.

On Windows there is no `umask`. Create the file with Notepad in a folder that only your account
uses, such as one in your user profile, and not in a shared or synced folder. Save it as
`deployment.properties` with **Save as type** set to **All files**, so that Notepad does not add
`.txt`.

**Inject.** In the same folder, on any OS:

```bash
java -jar cattail-<version>.jar inject deployment.properties
```

`inject` checks the file before it writes anything. It then writes the copy next to the generic
jar. The copy's name is the generic jar's name plus your tag without `tag:`. With
`tag=tag:mc-player` that is `cattail-<version>-mc-player.jar`, and inject prints:

```text
Wrote /path/to/cattail-<version>-mc-player.jar for tag:mc-player
```

On Linux and macOS the copy is owner-only (mode 600). On Windows it gets the folder's usual
permissions.

`inject` refuses, and writes nothing, when:

- the file breaks a rule (the message names the setting and the rule, never the value);
- `authkey.expires` is a date that has passed;
- the output file already exists, unless you name it with `--out <file>`.

</details>

### 6. Install the jar on the server

Copy the injected jar into the server's `mods` folder and restart the server. The server runs the
same jar as your players. It never uses the key, but the jar holds it, so keep the server's copy
private too. Then check the server log.

<details><summary>Details</summary>

**This step is optional** ([What Cattail does](#what-cattail-does)). Without the jar on the
server, players still join, the mod's one network channel being optional, but there is no voice
unless the server already runs a standalone Plasmo Voice in the supported range, and none of the
log lines below. That is the `Server (other)` row of [Supported today](#supported-today): joins
only, and untested.

**Who can log in.** Anyone who holds the jar can reach the game port. Before you restart, keep
`online-mode=true` and turn on the whitelist in `server.properties` (`white-list=true`,
`enforce-whitelist=true`). Then add each player with `/whitelist add <name>`.

**The jar.** Remove any older Cattail jar from `mods` first. On Linux and macOS the injected jar
is owner-only. If the server runs as another user, give that user read access without making the
file readable for everyone, for example:

```bash
sudo chown <server user> mods/cattail-<version>-mc-player.jar
```

On Windows, keep the server folder where only the account that runs the server can read it, not
in a shared or synced folder.

After the restart, the server log shows:

```text
Cattail <version> loaded
```

These lines need your attention:

| Line | Meaning |
|---|---|
| `Cattail: this jar has not been set up by a server owner (no deployment file)` | The server runs the generic jar. Install the injected one. |
| `Cattail: deployment file rejected (<key>)` | The jar's deployment file was rejected. Inject again and install the new jar. |
| `Cattail: the server key expires in <n> day(s); inject a new deployment.properties` | `authkey.expires` is seven days away or less. |
| `Cattail: the server key expired on <date>; players cannot join until a new jar is injected` | `authkey.expires` has passed. |

**Voice.** On the first tick after start, Cattail checks the bundled Plasmo Voice config
(`config/plasmovoice/server/config.toml`) and logs lines that start with `Cattail voice:`. The
good case is:

```text
Cattail voice: Plasmo Voice config checked, no problems
```

With the no-voice jar there is no config to check, and the line is
`Cattail voice: Plasmo Voice is not loaded (the no-voice jar, or no Plasmo Voice in mods); voice is off for tailnet players`
instead. Any other `Cattail voice:` line says what it found, and most also say how to fix it. A separate
Plasmo Voice jar in `mods` whose version is outside the range in
[Supported today](#supported-today) stops the server from starting: remove it.

</details>

### 7. Send the jar to your players

Send each player the injected jar by a private link, together with [PLAYERS.md](PLAYERS.md), the
page that walks them through installing it. `PLAYERS.md` contains a `<your-server-hostname>`
placeholder: send an edited copy with your server's name filled in, or send the name together
with the page. The jar works as a key to your server, so never post it anywhere public.

<details><summary>Details</summary>

- To make the edited copy, download [PLAYERS.md](PLAYERS.md), replace every
  `<your-server-hostname>` with your server's name (such as `mc.example.com`), and delete the
  note for server owners at the top.
- A private link is one that only your players get: a direct message, or a file share limited to
  the people you invite. Not a public channel, a mod site or a public modpack.
- Players need a Windows x64 PC and a NeoForge instance on the versions in
  [Supported today](#supported-today).
- Keep every player and the server on the same Cattail version. Voice works only when the Plasmo
  Voice in the player's jar matches the server's.
- The first time a player opens Multiplayer with your server in the list, or joins it, Windows
  Firewall asks about `cattail-helper.exe`. Allowing it needs an administrator. A player who
  clicks **Cancel** still connects, over a slower relayed path.

</details>

### 8. Check from a player's PC

On a Windows PC (yours or a player's) with the injected jar in `mods`, start the game, open Multiplayer and add your
server by its name. The server's row shows `Tailscale Tunnel` next to the ping bars. When you
join, the connect screen shows `Starting Tailscale link…`, then
`Connected via Tailscale (100.101.102.103)`. Talk to another player in the world to check voice.

<details><summary>Details</summary>

- `Tailscale Tunnel` is green while the link is up and grey while it starts or is idle. Hovering
  over it shows `Via Tailscale · 100.101.102.103`.
- In the admin console, the Machines page lists a node `mc-<player name>` with `tag:mc-player`
  while the game runs. On the server, `tailscale status` lists it as a peer. When the game quits
  normally, the helper logs the node out and Tailscale removes it at once. After a crash or a lost
  network, the node stays on the Machines page as offline for 30 to 60 minutes
  ([Stale player nodes](#stale-player-nodes)).
- The server log shows the login from a tailnet address:
  `<player>[/100.x.y.z:<port>] logged in with entity id …`.
- Voice: players near each other in the world hear each other. The talk key and the microphone
  settings are in the Plasmo Voice menu.
- If the connect screen shows no Cattail line at all, the game did not see your DNS records. If
  it shows another line, or voice stays silent, see [Troubleshooting](#troubleshooting).

</details>

## Which key: auth key or OAuth client

The key in the jar lets each player's helper join your tailnet. Tailscale offers two kinds that
work. Each carries your player tag, which must first be defined in the policy's `tagOwners`
(step 2).[^tags] Both are shared credentials: every player's jar carries the same key. Anyone who
extracts it gets what your policy grants the player tag, and nothing more. Rotating either kind
is the same re-inject (see [Operations](#operations)). If you are not sure, use an auth key and
set `authkey.expires`.

| | Auth key | OAuth client |
|---|---|---|
| Where you create it | Keys page of the admin console | Trust credentials page of the admin console |
| Prefix | `tskey-auth-` | `tskey-client-` |
| Expires | 1 to 90 days after you create it; 90 if you set nothing | Never; the secret stays valid until you revoke the client |
| What players see when it lapses | `Server key expired, ask the server owner` if you set `authkey.expires`; otherwise `Tailscale link failed: <reason>` | Nothing lapses |
| Renewal | At least every 90 days: a new key, a re-inject, and the new jar for every player and the server | None |
| If a jar leaks | The holder can start ephemeral nodes with your tag until the key expires or you revoke it | The holder can create nodes with your tag, and mint their own auth keys for it (also for nodes that are not ephemeral). Revoke the client, then revoke keys you did not create on the Keys page and delete stray nodes on the Machines page |
| Where you revoke it | Keys page, **Revoke**; nodes that already joined stay until you delete them on the Machines page | Trust credentials page, **Revoke** |

> [!IMPORTANT]
> An auth key stops working after at most 90 days. From then on, no player can join until you
> create a new key, inject it and send the new jar to every player. Set `authkey.expires` so that
> the server log warns you seven days ahead, and put the date in your calendar.

### Auth key

Created as below, an auth key can register only ephemeral, pre-approved nodes that carry your
player tag.[^authkeys]

Pros:

- Time-boxed exposure: a leaked jar stops working when the key expires, at most 90 days after you
  created it.
- It creates only ephemeral nodes, which Tailscale removes after they go offline.
- One place to revoke it: the Keys page.
- After `authkey.expires`, players see a clear `Server key expired, ask the server owner` instead
  of a raw error.

Cons:

- A hard deadline at least every 90 days: create a new key, re-inject, and send the new jar to
  every player and to the server.
- If you miss the deadline, every player is locked out until you do it.

<details><summary>Create an auth key, click by click</summary>

1. Sign in to the admin console as an Owner, Admin, IT admin or Network admin.
2. Open the **Keys** page: <https://console.tailscale.com/admin/settings/keys>.
3. Select **Generate auth key**.
4. Fill in the form:
   - A description, such as `Cattail players`.
   - **Reusable**: on. Every player uses the same key.
   - The expiry: 1 to 90 days. Note the date.
   - **Ephemeral**: on.
   - **Pre-approved**: on.
   - **Tags**: on, with `tag:mc-player` selected. The tag must already be in `tagOwners`
     (step 2).
5. Select **Generate key**.
6. Copy the key. It starts with `tskey-auth-` and is shown once.
7. Put it in `deployment.properties` as `authkey`, and its expiry date as `authkey.expires`
   (step 5).

To revoke the key, select **Revoke** next to it in the table on the Keys page. Revoking stops new
nodes from joining with it, but it does not remove nodes that already joined. Delete those on the
Machines page to cut them off at once. Ephemeral player nodes also go away by themselves once
their game quits.

</details>

### OAuth client

An OAuth client secret does not expire: it stays valid until you revoke the client.[^trust]
Before Cattail hands the secret to the helper, it appends `?ephemeral=true&preauthorized=true`,
so the helper mints a fresh ephemeral, pre-approved key from it on every start.[^oauth] Paste the
secret exactly as Tailscale shows it.

Pros:

- No renewal clock: the jar keeps working until you revoke the client.
- No lockout from a forgotten date.

Cons:

- A leaked jar stays valid until you notice and revoke the client. Your only control is to
  revoke the client, then revoke any keys minted with it and delete the nodes they joined.
- With the secret, a holder can also mint their own auth keys for your tag, including keys for
  nodes that are not ephemeral. Tailscale does not say that revoking the client cancels keys it
  already minted, and nodes made with them stay on your Machines page until you delete them.

<details><summary>Create an OAuth client, click by click</summary>

1. Sign in to the admin console as an Owner, Admin, IT admin or Network admin.
2. Open the **Trust credentials** page:
   <https://console.tailscale.com/admin/settings/trust-credentials>.
3. Select **Credential**, then **OAuth**.
4. Choose **Write** for the **Auth Keys** scope (`auth_keys`). Leave every other scope off.
5. Select the tag `tag:mc-player`. This scope needs at least one tag. The tag must already be in
   `tagOwners` (step 2).
6. Select **Generate credential**.
7. On the **Credential created** page, copy the client secret. It starts with `tskey-client-`
   and is shown once.
8. Select **Done**.
9. Put the secret in `deployment.properties` as `authkey`, and leave out `authkey.expires`
   (step 5).

To revoke the client, find it on the Trust credentials page, select **Revoke**, and confirm with
**Revoke**. Tailscale says this also revokes the client's API access tokens. It does not say what
happens to auth keys already minted from the client. So, after revoking, check the Keys page for
keys you did not create and revoke them, and delete stray nodes on the Machines page. Ephemeral
player nodes also go away by themselves once their game quits.

</details>

[^tags]: Tailscale, [Tags](https://tailscale.com/kb/1068/tags), checked 2026-10-21.
[^authkeys]: Tailscale, [Auth keys](https://tailscale.com/kb/1085/auth-keys), checked 2026-10-21.
[^trust]: Tailscale, [Trust credentials](https://tailscale.com/docs/reference/trust-credentials.md), checked 2026-10-21.
[^oauth]: Tailscale, [OAuth clients](https://tailscale.com/kb/1215/oauth-clients), checked 2026-10-21.

## Configuration reference

Four things can be configured: the deployment file you inject, the TXT record, each player's
client config, and the `inject` command itself.

### deployment.properties

The private file from [step 5](#5-inject-your-key-into-the-jar). One `key=value` per line, in
UTF-8. Lines that start with `#` are comments, and spaces around a value are ignored.

| Key | Required | Format | Default | Meaning |
|---|---|---|---|---|
| `authkey` | Yes | `tskey-auth-` or `tskey-client-`, then printable ASCII with no spaces (`[\x21-\x7E]+`) | None | Your auth key or OAuth client secret ([Which key](#which-key-auth-key-or-oauth-client)). Cattail adds `?ephemeral=true&preauthorized=true` to a `tskey-client-` secret that has no `?` of its own. |
| `authkey.expires` | No | A date, `YYYY-MM-DD` | None, so no reminder | The auth key's expiry date. The key counts as valid through that day. The server log warns from seven days ahead, players see `Server key expired, ask the server owner` when Tailscale refuses the key after that date, and inject refuses a date that has passed. Leave it out for an OAuth client secret. |
| `tag` | Yes | `tag:`, then lowercase letters, digits and `-` (`tag:[a-z0-9-]+`) | None | The tag your key carries and your policy grants. Every player node advertises it. `inject` also puts it in the output name. |
| `specs.prefix` | No | 1 to 32 characters from `A-Z`, `0-9` and `_` | `CATTAILSPECS` | The first field of the line the server logs for each login ([Read the server log](#read-the-server-log)). |

`inject` checks every rule before it writes anything. Its message names the key and the rule, never
the value. A key that is not in this table is refused. Once the jar runs, `authkey.expires` never
stops a key that Tailscale still accepts: the helper always tries the key, and the date only
changes what the log and the players see.

### DNS TXT record

The TXT record from [step 4](#4-create-the-dns-records), on the name your players type.

| Field | Required | Values | Meaning |
|---|---|---|---|
| `cattail` | Yes | `1` | Marks the name as a Cattail server. The older `tslink=1` is accepted instead, for compatibility. |
| `target` | No, but recommended | An IPv4 address in `100.64.0.0/10` | The server's Tailscale address. The mod uses it only when the A lookup returns no IPv4 address. Keep it equal to the A record. |
| `voice` | No | Absent, `0`, or a port from `1` to `65535` | The UDP port the helper relays for voice. Absent: the game port the player joined on. `0`: no voice relay. A port: that port. Any other value counts as absent. |

Fields are `key=value` pairs separated by `;`. Spaces around them, their order, the case of keys
and unknown keys do not matter. When a key appears twice, the first value counts. When the name
has several TXT records, the mod uses the first one with `cattail=1`.

The mod routes a name only when both of these hold: its IPv4 address, from the A record or else
from `target=`, is in `100.64.0.0/10`, and its TXT record carries `cattail=1`. When the A record
gives an address outside that range, the mod does not read the TXT record at all. IP addresses and
`localhost` are never routed.

### Client config

Each player's game reads `config/cattail-client.toml` in its instance folder. The game writes the
file with these defaults on its first start. After an edit, the player restarts the game.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Route servers that publish a `cattail=1` TXT record through the Tailscale helper. With `false`, every server connects directly. |
| `forcedHosts` | `[]` | Hosts always routed to a tailnet IPv4, as `host=ip` (for example `mc.example.com=100.101.102.103`). A forced host needs no DNS lookup, and its voice uses the game port. The address must be a Tailscale address, or the helper refuses to start. |
| `forceRelay` | `false` | Force the helper to use Tailscale's DERP relays instead of a direct connection. |
| `helperLogLevel` | `info` | Helper log level: `debug`, `info` or `warn`. This version does not pass it to the helper, so it has no effect yet. |
| `shareSpecs` | `true` | Send your PC specs (CPU, RAM, GPU, driver, OS, Java, heap, render distance, shaderpack, mod count, Cattail version; no IP, host name or serial numbers) once per login to servers that ask for them (`cattail:sysinfo`). The server logs them once per login. |

A player whose DNS hides your records gets a line like this from you, in place of the
`forcedHosts` line in that file:

```toml
forcedHosts = ["mc.example.com=100.101.102.103"]
```

### The inject command

```text
java -jar cattail-<version>.jar inject <deployment.properties> [--out <file.jar>]
java -jar cattail-<version>.jar --version
```

| Argument | Meaning |
|---|---|
| `inject <file>` | Checks the deployment file, then writes a copy of the jar that carries it. The jar you run is only read. |
| `--out <file.jar>` | Writes the copy to this path. A file already there is replaced. |
| No `--out` | Writes the copy next to the jar you run. Its name is the jar's name plus your tag without `tag:`: `cattail-<version>.jar` with `tag=tag:mc-player` gives `cattail-<version>-mc-player.jar`. An existing file is never replaced. |
| `--version` | Prints the jar's version. |

When it succeeds, inject prints `Wrote <path> for <tag>` with the full path. It writes a
temporary file first and then moves it into place, so a failure leaves no partial jar. On Linux
and macOS the copy is readable by you only.

| Exit code | Meaning |
|---|---|
| `0` | The copy was written. |
| `1` | A file could not be read or written. The message names the file and the system's reason. |
| `2` | Wrong arguments, a deployment file that breaks a rule, or one of the refusals below. Nothing was written. |

`inject` refuses with exit code 2, and writes nothing, in these cases:

| Message | Cause | Fix |
|---|---|---|
| `<file>: <key> <rule>` | A setting breaks its rule from the table above, or the key is unknown. A line that is not `key=value` shows as `line`. | Correct that line and run inject again. |
| `<file>: authkey.expires is in the past` | The key's date has passed. | Create a new key ([Rotate the key](#rotate-the-key)). |
| `<output> already exists; give --out <output> to replace it` | A file with the default name is there, usually from an earlier inject. | Delete it, or give `--out` with that name to replace it. |
| `<output> is the input jar; inject never writes in place, give another --out` | `--out` names the jar you are running. | Name another file. |
| `<output> is the deployment file; give another --out` | `--out` names your deployment file. | Name another file. |

## Security: what is in the jar

> [!CAUTION]
> The injected jar holds your Tailscale key. Share it only with your players, by private link.
> Never upload it anywhere public: not to a mod site, a public modpack, a public repository or a
> public channel.

**The key can be read.** It is stored as plain text in `cattail/deployment.properties` inside the
jar. A jar is a zip file, so anyone who holds it can read the key. The same holds for your
`deployment.properties` and the server's copy of the jar. Cattail itself never prints the key.
Inject's error messages name settings and rules instead of values, and the server log shows the
`authkey.expires` date and, in each specs line, your `specs.prefix`, never the key. The game
hands the key to the helper in an environment variable, not on a command line.

**What a leaked jar reaches.** Whoever holds the jar can join your tailnet as a player node with
your tag. Your policy decides what that node reaches: with the grant from
[step 2](#2-edit-the-tailnet-policy), the game port on your server, TCP and UDP, and nothing else.
No rule names the player tag as a destination, so nothing can open a connection to a player node.
In the game, the whitelist with `online-mode=true` ([step 6](#6-install-the-jar-on-the-server))
still decides who logs in. An OAuth client secret gives a holder more than an auth key does: the
"If a jar leaks" row in [Which key](#which-key-auth-key-or-oauth-client) says what.

**Why the allow-all rule must go.** Tailscale's default policy lets every device reach every
device on every port, and its `*` includes tagged devices. With that rule in place, every player
node, and anyone with a leaked jar, reaches every device on your tailnet. Replace it as step 2
shows, and keep the `tests` block: Tailscale then refuses any policy change that lets the player
tag reach a port in its `deny` list.

**Revoke and rotate.** If a jar reaches someone it should not, rotate the key as
[Rotate the key](#rotate-the-key) shows. Until you revoke the old key, every jar that carries it
keeps working.

**What the server learns about a player.**

- The Minecraft name, as on any server.
- The node name `mc-<player name>` (lower case) and its Tailscale address.
- The player's public IP address and whether the link is direct or relayed, which
  `tailscale status` and the admin console show for any Tailscale device.
- The player's voice, which Plasmo Voice sends through the server to players nearby.
- Once per login, the PC specs: CPU, core and thread count, RAM, graphics card with its memory and
  driver, OS, Java version, heap, render distance, shaderpack, mod count and Cattail version. No IP
  address, host name or serial number. A player turns this off with `shareSpecs = false`
  ([Client config](#client-config)). The line's format is in
  [Read the server log](#read-the-server-log).

**The generic jar is not secret.** The jar on the Releases page holds no key, and anyone may
download it. On its own it joins nothing over Tailscale: on a Cattail server it shows
`This Cattail jar has not been set up by a server owner`. Check every download against
`SHA256SUMS` ([step 5](#5-inject-your-key-into-the-jar)).

## Operations

Day-to-day tasks after setup. Keep `deployment.properties` private and at hand: rotating the key
and upgrading both start from it.

### Rotate the key

Rotate the key when a jar reaches someone it should not, before an auth key expires, to cut off a
player for good, or to switch between an auth key and an OAuth client. Every player and the
server then need the new jar.

1. Create the new key as [Which key](#which-key-auth-key-or-oauth-client) shows. Copy it: it is
   shown once.
2. Put it in `deployment.properties`. On Linux and macOS, run
   `(umask 077; cat > deployment.properties)` again. It replaces the whole file, so type every
   line again: `authkey`, `tag` and any optional ones. On Windows, edit the file in Notepad. Set
   `authkey.expires` to the new key's expiry date, or leave it out for an OAuth client secret.
3. Inject the generic jar again. The old injected jar already has the default name, so replace it
   with `--out`:

   ```bash
   java -jar cattail-<version>.jar inject deployment.properties --out cattail-<version>-mc-player.jar
   ```

4. Install the new jar on the server: remove the old one from `mods`, copy the new one in and
   restart. Send the new jar to every player who keeps access. Each player deletes the old jar
   first.
5. Revoke the old key: the auth key on the Keys page, or the OAuth client on the Trust credentials
   page. Until you do, every jar with the old key keeps working. After revoking an OAuth client,
   also do the checks in [OAuth client](#oauth-client).
6. On the Machines page, delete the player-tag nodes that are still connected with the old key.
   Revoking does not remove nodes that already joined. For a routine rotation this step is
   optional.

After a leak, revoke the old key first, then delete every node with your player tag on the
Machines page, and send the new jar afterwards. Deleting the nodes is required: revoking leaves
nodes that already joined connected. Players cannot join in between.

### Upgrade to a new release

1. Download the new `cattail-<version>.jar` and `SHA256SUMS` from
   [Releases](https://github.com/kuching-sarawak/cattail/releases), and check the jar as in
   [step 5](#5-inject-your-key-into-the-jar).
2. Inject the same `deployment.properties` into it:

   ```bash
   java -jar cattail-<version>.jar inject deployment.properties
   ```

   The new version has a new file name, so `--out` is not needed. If `authkey.expires` has
   passed, inject refuses: create a new key first ([Rotate the key](#rotate-the-key)).
3. On the server, remove the old Cattail jar from `mods`, copy the new injected jar in and
   restart. The log shows `Cattail <version> loaded` with the new version.
4. Send the new jar to every player. Each player deletes the old jar from `mods` first.
5. Delete the old injected jars you no longer need. Each one holds your key.

Players on the old version can still join while their key is valid. When they do, the game shows
them a notice at the top right, `Cattail update available`, naming the server's version and
theirs and telling them to ask you for the new jar: once per server per game session. Voice works
only when a player's Plasmo Voice matches the server's, so move everyone to the new jar. The Mods
screen also marks Cattail when a newer release exists on GitHub (NeoForge's update checker reads
[update.json](update.json) from this repository).

### Add or remove a player

Players are not users of your tailnet, and there is no per-player key: all players share one jar.
The Minecraft whitelist is the only per-player gate.

- **Add:** `/whitelist add <name>`, in the game as an operator or on the server console. Send the
  injected jar by private link, with [PLAYERS.md](PLAYERS.md)
  ([step 7](#7-send-the-jar-to-your-players)).
- **Remove:** `/whitelist remove <name>`, then `/kick <name>`. Their jar can still reach the game
  port, but they cannot log in.
- **Cut off the jar itself:** rotate the key ([Rotate the key](#rotate-the-key)) and send the new
  jar to everyone else. One player's copy cannot be revoked on its own.

### Read the server log

The server writes its log to `logs/latest.log` in the server folder. To list the Cattail lines on
Linux and macOS:

```bash
grep 'Cattail' logs/latest.log
```

On Windows, in PowerShell:

```powershell
Select-String -Path logs\latest.log -Pattern 'Cattail'
```

| Line | Level | Meaning |
|---|---|---|
| `Cattail <version> loaded` | INFO | The mod loaded. Logged on every start. |
| `Cattail: this jar has not been set up by a server owner (no deployment file)` | WARN | The server runs the generic jar. Install the injected one. |
| `Cattail deployment file rejected: <key> <rule>` | ERROR | The jar's deployment file breaks a rule. `<key>` is `line` for a line that is not a known `key=value`. An unreadable file gives `file could not be read` instead. |
| `Cattail: deployment file rejected (<key>)` | ERROR | Follows the line above. Inject again and install the new jar. |
| `Cattail: the server key expires in <n> day(s); inject a new deployment.properties` | WARN | `authkey.expires` is seven days away or less, `0` on its last day. [Rotate the key](#rotate-the-key). |
| `Cattail: the server key expired on <date>; players cannot join until a new jar is injected` | ERROR | `authkey.expires` has passed. [Rotate the key](#rotate-the-key). |
| The `CATTAILSPECS` line, format below | INFO | One player's PC specs, once per login. |

The server checks the jar once, at start-up, and logs at most one line that starts with
`Cattail:`. Without `authkey.expires`, or with a date more than seven days away, there is none. The `Cattail voice:` lines are explained under
[Voice](#voice).

The specs line has this format:

```text
<prefix>|<player>|<cpu>|<cores>c/<threads>t|<ram> GiB|<gpu>|<vram> GiB|<driver>|<os>|Java <version>|heap <heap> GiB|render <distance>|<shaderpack or none>|<mods> mods|cattail <version>
```

`<prefix>` is `specs.prefix` from your deployment file, `CATTAILSPECS` by default. The server
logs the line for every player whose `shareSpecs` is on, which is the default. A `|` inside a
value becomes `/`, each value is at most 64 characters, and the line is at most 1024 bytes. To list
only these lines on Linux and macOS (if you set `specs.prefix`, use it in place of
`CATTAILSPECS`):

```bash
grep -F 'CATTAILSPECS|' logs/latest.log
```

On Windows, in PowerShell:

```powershell
Select-String -Path logs\latest.log -SimpleMatch 'CATTAILSPECS|'
```

### Stale player nodes

When a game quits normally, the helper logs its node out and Tailscale removes the node at once.
After a crash, a killed game or a lost network, the node stays on the Machines page as
offline. Tailscale removes it by itself, normally 30 to 60 minutes after its last
activity.[^ephemeral] An offline node reaches nothing, so you can leave it. To remove it sooner,
delete it on the Machines page.

Cattail creates only ephemeral nodes. A node without the **Ephemeral** badge that carries your
player tag was made by someone with your key, which an OAuth client secret allows, and so does an
auth key created without **Ephemeral**. Delete the node and rotate the key.

[^ephemeral]: Tailscale, [Ephemeral nodes](https://tailscale.com/kb/1111/ephemeral-nodes), checked 2026-10-21.

### Voice

Voice runs on Plasmo Voice, which the standard jar bundles. By default it uses the game port over
UDP, which step 2 grants and which needs no TXT field. Keep every player and the server on the
same Cattail version: Plasmo Voice works only when both sides match.

**No voice at all.** Use the `-novoice` jar from the release for the server and the players: it
is the same mod without Plasmo Voice, so nothing listens for voice and players see no voice menu.
Set `voice=0` in the TXT record and leave UDP out of the policy (step 2 and step 4). The server
log then shows `Cattail voice: Plasmo Voice is not loaded` once at start, which is expected. Do
not mix the jars: a player with the standard jar on a no-voice server sees
`Plasmo Voice is not installed on this server`, and the other way round the player simply has no
voice.

**Move voice to another UDP port**, for example 24454:

1. On the server, in `config/plasmovoice/server/config.toml`, set `port = 24454` under `[host]`,
   then restart the server.
2. Add `voice=24454` to the TXT record: `cattail=1; target=100.101.102.103; voice=24454`.
3. In the policy, grant `udp:24454` instead of `udp:25565`, and change the UDP test to match.
4. Allow UDP 24454 in the host firewall. If the server runs in a container, publish the port for
   UDP.
5. Players restart the game, so that the mod reads the new TXT record.

The voice check cannot see your TXT record, so it logs the `[host] port` line from the table below
on every start, even when the record and the grant are right. If a container or port-forward maps
voice to a different outside port, set `ip = "0.0.0.0"` and `port = <outside port>` under
`[host.public]` instead, and use the outside port in the TXT record and the grant.

**Turn voice off for tailnet players.** Set `voice=0` in the TXT record, and remove the UDP entry
and the UDP test from the policy. Players restart the game. The voice server keeps running for
anyone who reaches the server another way.

**The voice check.** On the first tick after start, Cattail reads the Plasmo Voice config and logs
one line per finding:

| Line | Level | Fix |
|---|---|---|
| `Cattail voice: Plasmo Voice config checked, no problems` | INFO | None. Logged when no other line is a WARN. |
| `Cattail voice: Plasmo Voice config not found at <path>; the voice server is not running.` | WARN | Plasmo Voice has not written its config yet, or keeps it at another path. If the log has Plasmo Voice's `UDP server is started on` line, the voice server runs; otherwise look for Plasmo Voice errors in the log. |
| `Cattail voice: Plasmo Voice [host] ip = "<ip>" binds the voice server to one address; …` | WARN | Set `ip = "0.0.0.0"` under `[host]`. |
| `Cattail voice: Plasmo Voice [host] port is <port> but the game port is <game port>; …` | WARN | Set `port = 0` under `[host]`, or keep the port and publish it as above. |
| `Cattail voice: Plasmo Voice [host.public] ip = "<ip>" breaks tailnet and LAN players. …` | WARN | Set `ip = "0.0.0.0"` under `[host.public]`, or remove `[host.public]`. |
| `Cattail voice: Plasmo Voice [host.public] port <port> differs from the game port <game port>; …` | INFO | Make sure the TXT record has `voice=<port>` and the policy grants `udp:<port>`. |
| `Cattail voice: could not read config.toml: <reason>`, or `could not parse` | WARN | Correct the file, or delete it and restart so that Plasmo Voice writes a new one. |
| `Cattail voice: Plasmo Voice config check failed` | WARN | The check itself failed, and an error trace follows. Report it ([Support](#support)). |

## Troubleshooting

One entry per symptom, named by the text you or a player sees. Cattail's line on the connect
screen appears below the usual connecting text, and only for a server the mod routes. A player's
logs are in the instance folder: `cattail\helper.log`, `cattail\helper-crash.log` (if it exists)
and `logs\latest.log`. They contain your server's name and address, the player's own public IP
addresses (in lines such as `magicsock: endpoints changed:`), the player's Windows user name and
your tailnet's MagicDNS domain name (the tailnet DNS name shown on the DNS page of the admin
console). The user name is part of the instance folder's path, usually under `C:\Users\<name>`,
which `logs\latest.log` shows in the `Starting Cattail helper:` line (the helper's command line)
and `cattail\helper.log` in the `tsnet starting` line. Replace all of these before you post the
logs anywhere public.

<details><summary><code>Starting Tailscale link…</code></summary>

- **Cause:** the helper is starting. This is normal.
- **Check:** within 20 seconds the line turns into `Connected via Tailscale (…)` or
  `Tailscale link failed: …`.
- **Fix:** nothing to do. If it turns into a failure, see that entry.

</details>

<details><summary><code>Connected via Tailscale (&lt;address&gt;)</code>, then the join fails</summary>

`<address>` is your server's Tailscale address, as the mod took it from your DNS records.

- **Cause:** the link is up, but nothing answers at that address and port. The policy grants no
  `tcp:<port>`, the A record points at another device, the server is down or listens on another
  port, or a host firewall blocks the Tailscale interface.
- **Check:** `<address>` equals `tailscale ip -4` on the server. On the server,
  `tailscale status` lists `mc-<player name>` as a peer. The grant names the port the player
  typed.
- **Fix:** correct what differs. If the game says the player is not whitelisted, the link works:
  run `/whitelist add <name>`.

</details>

<details><summary><code>Tailscale link failed: &lt;reason&gt;</code></summary>

`<reason>` is a short code from the table below.

- **Cause:** the helper did not get ready. The game then tries a direct connection, which fails
  unless that PC runs Tailscale itself.
- **Check:** the reason, then the player's `cattail\helper.log` and `logs\latest.log`.
- **Fix:** the one in the table. The next join retries every reason except `helper-crashed`,
  which needs a game restart.

| Reason | Cause | Fix |
|---|---|---|
| `ready-timeout` or `helper-error:up-timeout` | The helper did not reach your tailnet within 20 seconds. The PC cannot reach Tailscale (a firewall, proxy or captive portal), the node waits for device approval, or the network is slow. | Join again. See the device approval entry below. Try another network. |
| `helper-error:auth` | Tailscale refused the key: it was revoked, it expired and `authkey.expires` is not set, or `tag` names a tag the key may not use. | Compare the key and its tag on the Keys or Trust credentials page with `deployment.properties`, then [rotate the key](#rotate-the-key). |
| `helper-missing` | The jar holds no helper program: it is not a release jar, or it is damaged. | Download the generic jar again, check it against `SHA256SUMS`, inject it and send the new jar. |
| `install-failed` | The game could not write the helper to `cattail\bin` in the instance folder, or the written copy failed its checksum, which means the helper inside the jar is damaged. | Free disk space, make the instance folder writable, and check whether antivirus removed the file. If it happens again, download the generic jar again, check it against `SHA256SUMS`, inject it and send the new jar. |
| `launch-failed` | Windows did not start `cattail-helper.exe`, for example because antivirus blocked it. | Allow the file in `cattail\bin` in the antivirus, then join again. |
| `helper-crashed` | The helper stopped twice in one session. | Restart the game. If it happens again, report it with `cattail\helper-crash.log` ([Support](#support)). |
| `internal-error` | An error in Cattail's start code. `logs\latest.log` has `Cattail helper start crashed`. | Restart the game. If it happens again, report it ([Support](#support)). |
| Any other code | The helper stopped or reported an error before it was ready: `helper-exited:<code>`, `helper-error:<code>` or `ready-malformed`. A `forcedHosts` address outside the Tailscale range gives `helper-error:usage`. | `cattail\helper.log` names the cause. |

</details>

<details><summary><code>This Cattail jar has not been set up by a server owner</code></summary>

- **Cause:** the player has the generic jar from Releases, which holds no key.
- **Check:** the jar in the player's `mods` folder has no tag in its name, as in
  `cattail-<version>.jar`.
- **Fix:** send the injected jar. The player deletes the generic one first.

</details>

<details><summary><code>Cattail deployment file is invalid (&lt;key&gt;)</code></summary>

`<key>` names the setting that broke a rule. It is `line` for a line that is not a known
`key=value`, and `file` when the file could not be read.

- **Cause:** the deployment file inside the player's jar was rejected. `inject` checks the
  file before it writes, so the jar was changed or damaged afterwards.
- **Check:** the player's `logs\latest.log` has `Cattail deployment file rejected:` with the
  setting and the rule.
- **Fix:** inject the generic jar again and send the new jar.

</details>

<details><summary><code>Server key expired, ask the server owner</code></summary>

- **Cause:** Tailscale refused the key, and the `authkey.expires` date in the jar has passed.
- **Check:** the `authkey.expires` date in `deployment.properties`. A server that runs the same
  jar logs `Cattail: the server key expired on <date>` when it starts after that date.
- **Fix:** [rotate the key](#rotate-the-key) and send the new jar to every player.

</details>

<details><summary><code>Cattail supports Windows only</code></summary>

- **Cause:** the player's PC is not 64-bit Windows, for example macOS, Linux or Windows on ARM.
  The game then connects directly, which works only if that PC runs Tailscale itself and your
  policy lets it reach the server.
- **Check:** the PC's operating system and processor.
- **Fix:** the player joins from a Windows x64 PC.

</details>

<details><summary>No Cattail line on the connect screen and no <code>Tailscale Tunnel</code> label</summary>

- **Cause:** the mod did not take the name for a Cattail server, so the game connected directly.
  The mod's status for this, `Not a Cattail server`, is never shown on screen.
- **Check:** on the player's PC, run `nslookup mc.example.com` and
  `nslookup -type=TXT mc.example.com`. The server list shows the label only after it has pinged
  the server. Then go through the causes:
  - The A record is proxied or points outside `100.64.0.0/10`. The mod then never reads the TXT
    record.
  - The A record is missing. The name is then routed only if the TXT record carries `target=`.
  - The TXT record is missing or has no `cattail=1`.
  - The player's resolver drops answers in the Tailscale range and the TXT record has no
    `target=`, or the resolver strips the TXT record too.
  - The player typed an IP address. Addresses are never routed.
  - The records changed after the game started. The mod keeps its decision for each name until
    the game quits.
  - The player's `config/cattail-client.toml` has `enabled = false`.
- **Fix:** correct the records as in [step 4](#4-create-the-dns-records), then the player
  restarts the game. If the player's resolver hides the records, send a `forcedHosts` line
  ([Client config](#client-config)).

</details>

<details><summary>Device approval or Tailnet Lock</summary>

- **Cause:** your tailnet holds new nodes back. With device approval, a node waits for an admin
  unless its key is pre-approved. With Tailnet Lock, a node needs a signature from a trusted node.
- **Check:** on the Machines page, the player's node `mc-<player name>` shows **Needs approval**
  or **Locked out**. With device approval, the player usually sees
  `Tailscale link failed: ready-timeout`.
- **Fix:** for device approval, use an auth key with **Pre-approved** on
  ([rotate the key](#rotate-the-key)). An OAuth client secret needs no change as long as it has no
  `?` of its own: Cattail then asks it for pre-approved keys. Cattail is untested with Tailnet
  Lock, and every game start is a new node that would need a signature, so run the server on a
  tailnet without Tailnet Lock.

Tailscale, [Device approval](https://tailscale.com/kb/1099/device-approval) and
[Tailnet Lock](https://tailscale.com/kb/1226/tailnet-lock), checked 2026-10-21.

</details>

<details><summary>Voice is silent</summary>

- **Cause:** voice traffic (UDP) does not reach the voice server, or the two sides' Plasmo Voice
  versions do not match.
- **Check:**
  - The server log's `Cattail voice:` lines ([Voice](#voice)), and Plasmo Voice's own
    `UDP server is started on` line.
  - The policy grants `udp:<port>` for the voice port: the game port, or the port in `voice=`.
    The TXT record has no `voice=0`.
  - The host firewall allows that UDP port on the Tailscale interface. A container publishes it
    for UDP.
  - The player and the server run the same Cattail version. When they do not, Plasmo Voice shows
    `Sorry, your Plasmo Voice version is not supported on this server.`
  - The server runs Plasmo Voice. When it does not, the player sees
    `Plasmo Voice is not installed on this server`.
  - No other program on the player's PC holds that UDP port. When one does, the player's
    `logs\latest.log` has `helper: WARN voice-port-busy`.
- **Fix:** correct what the check found. After a DNS change, players restart the game.

</details>

<details><summary>The server log says the jar has not been set up</summary>

- **Cause:** the server runs the generic jar. The log has
  `Cattail: this jar has not been set up by a server owner (no deployment file)`.
- **Check:** the Cattail jar in the server's `mods` folder has your tag in its name, as in
  `cattail-<version>-mc-player.jar`.
- **Fix:** remove the generic jar, copy in the injected one and restart. Joins work meanwhile,
  because the server never uses the key. But the log cannot warn you before `authkey.expires`,
  and the specs line uses the default prefix.

The other start-up lines are in [Read the server log](#read-the-server-log).

</details>

## Building from source

You need this only to change Cattail or to inspect its build. The jar from the Releases page is
all a server needs. The requirements are in [To build from source](#to-build-from-source). The
scripts run on Linux x86-64 and macOS, not on Windows.

```bash
git clone https://github.com/kuching-sarawak/cattail.git
cd cattail
bash scripts/install-toolchain.sh
source scripts/env.sh
scripts/build.sh
```

- `scripts/install-toolchain.sh` installs the pinned Go, Temurin JDK and staticcheck under
  `~/.local/toolchains`. It checks every download against the SHA-256 pinned in the script and
  needs no root. Running it again is safe.
- `source scripts/env.sh` puts that toolchain on your `PATH`. Run it in every new shell.
- `scripts/build.sh` runs `go vet`, `staticcheck` and `go test` on the helper, cross-compiles the
  helper for Windows, builds the mod with its tests, runs the headless client smoke test (Linux
  only, under Xvfb), and writes the jar. It refuses to build from uncommitted changes.

| Flag | Effect |
|---|---|
| `--deploy <file>` | Then runs the new jar's `inject` with `<file>`, as in step 5. Keep the file in `.secrets/`, which git ignores. |
| `--skip-tests` | Skips `go test`, the Gradle tests and the smoke test. `go vet` and `staticcheck` still run. |
| `--allow-dirty` | Builds from uncommitted changes. The helper's version then ends in `-dirty`. |

The output, where `<tag>` is your tag without `tag:`:

```text
dist/cattail-<version>.jar                 generic: no key, not secret
dist/cattail-<version>-novoice.jar         generic, without Plasmo Voice
dist/cattail-<version>-<tag>.jar           with --deploy: carries your key, readable by you only
dist/cattail-<version>-novoice-<tag>.jar   with --deploy: the no-voice jar with your key
dist/SHA256SUMS
```

Outside a git checkout, the helper's version ends in `+nogit`. On a Mac, `scripts/build-mac.sh`
installs the toolchain and builds in one command: see [BUILD-MACOS.md](BUILD-MACOS.md).

`SHA256SUMS` on the Releases page verifies that a download is the file that was published. A jar
you build yourself does not match it, even from the release's tag: the build stamps the helper
inside the jar with the commit hash, and releases are built from a different commit than the
public tag.

Every change must pass the gates in [CONTRIBUTING.md](CONTRIBUTING.md): `go vet`, `staticcheck`
and `go test` for the helper, and the Gradle tests for the mod.

## Support

Report bugs and ask questions in the
[issues](https://github.com/kuching-sarawak/cattail/issues) of the public repository. Include:

- the Cattail version: the jar's file name, or the output of
  `java -jar cattail-<version>.jar --version`;
- the exact text on the connect screen, or the server log lines that contain `Cattail`;
- for a player's problem, `cattail\helper.log`, `cattail\helper-crash.log` (if it exists) and
  `logs\latest.log` from the instance folder.

Issues are public. Never attach an injected jar or `deployment.properties`. In logs, replace your
server's name and Tailscale addresses with placeholders such as `mc.example.com` and
`100.101.102.103`. Also replace the player's public IP addresses, which `cattail\helper.log` shows
in lines such as `magicsock: endpoints changed:`; the player's Windows user name, which
`logs\latest.log` and `cattail\helper.log` show in the instance folder's path (usually under
`C:\Users\<name>`); and your tailnet's MagicDNS domain name, the tailnet DNS name shown on the
DNS page of the admin console.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) describes the toolchain, the gates every change must pass, the
rules (no secrets, and no real host names or addresses in code, tests or docs) and the commit
style.

## Acknowledgements

- [Plasmo Voice](https://github.com/plasmoapp/plasmo-voice) provides the proximity voice chat.
  Cattail bundles it unmodified under the LGPL-3.0: see
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- The helper is built on Tailscale's tsnet library and other Go modules under permissive
  licenses, such as BSD, MIT, Apache-2.0 and ISC. Their license texts are in
  [helper/THIRD_PARTY_LICENSES.md](helper/THIRD_PARTY_LICENSES.md).
- Tailscale and WireGuard are trademarks of their respective owners. Cattail uses the names only
  to say what it works with.

Cattail is not affiliated with Tailscale Inc., Mojang or Microsoft.

## License

MIT, see [LICENSE](LICENSE). SPDX-License-Identifier: MIT
