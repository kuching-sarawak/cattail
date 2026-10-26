# Join the server with Cattail (players)

> Server owners: replace `<your-server-hostname>` below with your server's address (the name
> your DNS records are on, such as `mc.example.com`) before you send this page to players.

Cattail is one mod file. With it in your `mods` folder, the game connects to the server
through a private network link that starts and stops with the game. You do not need a
Tailscale account or any app, and the game runs without admin rights. Only the optional
Windows Firewall click in step 3 asks for an administrator; macOS needs none, and on Linux none
is expected (untested).

**Windows x64, Linux x64 and macOS on Apple silicon only.** On anything else (Windows on ARM with
an arm64 Java, an Intel Mac, Linux on ARM, or an x86-64 Java under Rosetta on an Apple silicon
Mac) the mod stays idle and the connect screen says
`Cattail supports Windows x64, Linux x64 and macOS on Apple silicon only`. An x64 Java on Windows
on ARM counts as Windows x64 and runs the x64 helper under emulation (untested).

The server owner sends you the jar by private link. Do not share the jar or the link with
anyone else: it works as a key to the server.

## 1. Install the jar

Cattail needs a NeoForge instance for the server's Minecraft version; the server owner tells you
which version. In a Fabric or Forge instance, or one for another Minecraft version, Cattail does
not run: the game starts without it and shows no Cattail line at all, or stops with a mod loading
error.

1. Download the Cattail jar (one file, named like `cattail-<version>-mc-player.jar`).
2. Open the folder of the game instance you use for this server. In the CurseForge launcher:
   right-click the instance, **Open Folder**. In Prism Launcher: **Folder**. The right folder
   holds both `mods` and `config`.
3. Put the jar in the `mods` folder, next to the other mods. Keep only one Cattail jar there:
   when you update, delete the old one first.
4. Start the game as usual.

## 2. Add the server

Multiplayer, **Add Server**, Server Address:

```text
<your-server-hostname>
```

Join it. While connecting, the screen shows `Starting Tailscale link…`, then
`Connected via Tailscale (<server address>)`.

In the server list, the server's row shows the Cattail icon, a small yellow cat, left of the
player count once the list has pinged it. It is bright for a Cattail server and dim while
Cattail's helper program is not ready yet; a bright icon alone does not mean the link is up.
Hover over it to see the server's Cattail version, its Tailscale address (green while the link
is up, grey otherwise) and any notes from the server owner.

If another mod's icon on that row sits over Cattail's, move Cattail's icon with
`serverListIconOffsetX` and `serverListIconOffsetY` in `config/cattail-client.toml` (in your
instance folder; pixels, negative is left or up) and restart the game.

## 3. The firewall prompt

The first time you open Multiplayer (with this server in the list) or join, Cattail's helper
program starts, and your system's firewall may ask about it.

### Windows

Windows shows **"Windows Defender Firewall has blocked some features of this app"** for
`cattail-helper.exe`. Tick both **Private networks** and **Public networks** and click
**Allow access**. It only asks once.

Allowing needs an administrator. On a standard (non-admin) Windows account, **Allow access**
asks for an administrator's password. If you cannot give one, click **Cancel**: the game still
works, but your traffic takes a slower relayed path.

### macOS

If the macOS firewall is on, macOS is expected to ask **"Do you want the application
cattail-helper to accept incoming network connections?"** (not yet checked on a player's Mac; if
the wording differs, answer it the same way). Click **Allow**: it should keep direct
connections possible. If you click **Deny**, the game should still work, with your traffic on a
slower relayed path. No administrator should be needed.

### Linux

Most desktop Linux installs ask nothing. A strict firewall that blocks UDP out can force the
slower relayed path.

## When the server has a newer Cattail

If a notice `Cattail update available` appears at the top right when you join, the owner has
moved the server to a newer Cattail than your jar. You can keep playing; ask the owner for the
new jar, then replace yours as in [Install the jar](#1-install-the-jar).

## Voice chat

Voice chat is built in when the owner sent you the standard jar: Cattail includes Plasmo Voice,
a proximity voice chat mod. If the jar's name has `-novoice` in it, the owner chose no voice
chat, and this section does not apply. Players near
you in the world hear you, and you hear them. There is nothing to install or set up.

- **Talking and settings** belong to Plasmo Voice. Open the Plasmo Voice menu to pick your
  microphone, change volumes, or switch between push-to-talk and voice activation. You talk
  with the talk key shown in the Plasmo Voice menu. The key that opens the menu is listed
  under Options, Controls, Key Binds, in the Plasmo Voice group, as **Voice Settings**.
- **Microphone permission.** If your system asks whether Java or your launcher may use the
  microphone, allow it. On Windows, if the Plasmo Voice menu shows your microphone as not
  available, open Windows Settings, Privacy & security, Microphone, and make sure microphone
  access is on and desktop apps may use the microphone.
- **Already using Plasmo Voice as a separate mod?** A separately installed Plasmo Voice jar
  whose version is within [2.1.17, 2.2) loads instead of the bundled copy (any other version
  stops the game from starting), but voice works reliably only when its version matches the
  server's. The simplest choice is to remove it and let Cattail's copy load.

Plasmo Voice may show one of these messages:

| Message | Meaning | Tell the owner |
|---|---|---|
| `Plasmo Voice is not installed on this server` | The server does not run Cattail. | That you see this message and which Cattail jar you have. |
| `Sorry, your Plasmo Voice version is not supported on this server.` | Your Plasmo Voice version does not match the server's. The server and your Cattail build must match. | Ask the owner for the current jar. |
| `Can't connect to the UDP server` | The server's voice port is not open on the way you connect. The game itself still works. | That you see this message, and whether you joined through Cattail or on the local network. |

If another program on your PC already uses the server's voice port (UDP), voice cannot connect
and you see the same `Can't connect to the UDP server` message; the owner can tell from your
log.

## What the server learns about you

- Your Minecraft name, as with any server.
- Your link's name on the server's network: `mc-<your name>` (lower-case).
- Your voice, while you press the talk key (or while you speak, if you switched to voice
  activation), to players nearby; it travels inside the same encrypted link as the game.
- Like any Tailscale connection, your public IP address and whether the link is direct or
  relayed are visible to the network's owner.
- **Your PC specs**, if the server asks for them: CPU name, core and thread count, RAM,
  graphics card name, its memory and driver version, operating system version, Java version, the
  memory Minecraft may use, render distance, shaderpack name (Iris only), number of mods
  and the Cattail version. They are read on your PC and sent once per join, only to a server
  that asks for them (one that advertises the Cattail channel). They contain no IP address, no computer name and no serial numbers.

To stop sending specs, set `shareSpecs = false` in `config/cattail-client.toml` (in your
instance folder) and restart the game.

## If it does not connect

What the Cattail line on the connect screen means, and what to do:

| Line on screen | What to do |
|---|---|
| `Starting Tailscale link…` | Wait, it changes by itself. |
| `Connected via Tailscale (<server address>)` | The link is up; if the game still does not join, ask the server owner. |
| `Tailscale link failed: <reason>` | Restart the game (this often helps after a network change); if the line comes back, ask the server owner and tell them the reason. |
| `Cattail supports Windows x64, Linux x64 and macOS on Apple silicon only` | Join from a supported PC: Windows or Linux on a 64-bit Intel or AMD processor, or a Mac with Apple silicon. On a Mac, the game must run on an Apple silicon (arm64) Java, not an Intel (x86-64) one under Rosetta. |
| `This Cattail jar has not been set up by a server owner` | Ask the server owner, you need a new jar from them. |
| `Cattail deployment file is invalid (<setting>)` | Ask the server owner, you need a new jar from them. |
| `Server key expired, ask the server owner` | Ask the server owner, you need a new jar from them. |

When the game does not recognise the server as a Cattail server (`Not a Cattail server`), it
shows no Cattail line at all: see the first two points below.

- The connect screen shows no Cattail line at all: first check in your launcher that the instance
  runs NeoForge on the Minecraft version the server owner named (step 1). In any other instance
  Cattail does not run.
- Still no Cattail line, in the right instance: your internet provider's DNS may hide the
  server's records. Ask the owner for the server's `forcedHosts` line. Open
  `config/cattail-client.toml` in your instance folder with a text editor (Notepad on Windows),
  replace the line that starts with `forcedHosts` with the owner's line (it looks like
  `forcedHosts = ["mc.example.com=100.101.102.103"]`), save the file and restart the game.
- The server says you are not whitelisted: ask the owner.
- The owner may ask for your log files, all in the instance folder:
  `cattail/helper.log`, `cattail/helper-crash.log` (if it exists) and `logs/latest.log` (on
  Windows the same folders show with backslashes). None of them contain the server's key.

## Uninstall

Quit the game and delete the jar from `mods`. Nothing else keeps running. You can also delete
the `cattail` folder in the instance folder.
