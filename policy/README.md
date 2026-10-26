# Tailnet policy for Cattail

`policy.example.hujson` shows the parts of a tailnet policy that Cattail needs. It is not a
complete policy. Merge it into the policy you already have in the Tailscale admin console
(Access controls), then save. Tailscale runs the `tests` on every save.

## Placeholders

| Placeholder | Meaning | Example |
|---|---|---|
| `<tag>` | The player tag. Must equal `tag` in your `deployment.properties`. | `tag:mc-player` |
| `<server-tailscale-ip>` | Tailscale IPv4 of the Minecraft server. | `100.101.102.103` |
| `<port>` | The server's game port. | `25565` |

## Merge step by step

1. Save a copy of your current policy first (admin console, or
   `GET https://api.tailscale.com/api/v2/tailnet/-/acl`).
2. `tagOwners`: add the line `"<tag>": ["autogroup:admin"]`. The tag must exist here before
   you can create the auth key (or OAuth client) that carries it.
3. `hosts`: add `"minecraft": "<server-tailscale-ip>"`. Any alias name works; keep it the same
   in the grant and the tests.
4. `grants`: add the player grant
   `{"src": ["<tag>"], "dst": ["minecraft"], "ip": ["tcp:<port>", "udp:<port>"]}`
   (TCP for the game, UDP for voice; see [Voice](#voice)).
5. Check your other rules. **If your policy still has the default allow-all rule**
   (`"src": ["*"], "dst": ["*"]`, as a grant or as an `acls` entry), replace it with the
   member-to-member grant from the example. `*` includes tagged player nodes, so leaving it in
   lets every player node reach every device on your tailnet. The same goes for any rule whose
   `src` is `*` or `autogroup:tagged`.
6. Make sure no rule names `<tag>` (or `*`, or `autogroup:tagged`) as a destination. Then
   nothing can open a connection to a player node.
7. If the server itself is a tagged device, `autogroup:member` does not cover it. Add a grant
   from your own devices to the server's tag if you still need to reach it (SSH, admin).
   You can also use the server's tag as the `dst` of the player grant instead of the
   `hosts` alias.
8. `tests`: add the two player tests. Extend the `deny` list with the ports your server runs
   besides the game (SSH, web panels, databases) and with a few of your other devices.
9. Keep your existing `ssh`, `nodeAttrs`, `autoApprovers` and other sections as they are.
   Cattail does not use them.
10. Save. The console rejects the policy if a test fails or the syntax is wrong.

## Verify

- **Policy tests.** They run on every save in the admin console. To check a draft without
  saving it, post it to the validate endpoint with an API access token (keep the token in a
  `curl -K` config file, not on the command line):

  ```bash
  curl -sS -K <token.curlcfg> -X POST -H 'Content-Type: application/hujson' \
    --data-binary @policy.hujson https://api.tailscale.com/api/v2/tailnet/-/acl/validate
  ```

  `{}` means the policy is valid and every test passed.
- **From a real player node.** Join with the injected jar, then open the Machines page: the node
  `mc-<username>` carries `<tag>`. On the server, `tailscale status` lists it as a peer.
- **Isolation from the node's own view.** A probe build of the jar dials a list of addresses
  from a player node and logs `allowed` or `denied` for each (`scripts/build.sh --deploy <file>
  --probe`, with the addresses in `build.conf`). Only `<server-tailscale-ip>:<port>` may say
  `allowed`.

## Voice

Cattail bundles Plasmo Voice, and voice is on by default at the game port: the helper relays
UDP on the same port number as the game, where the Plasmo Voice server listens by default.
So the player grant carries `udp:<port>` next to `tcp:<port>`, and the UDP test accepts
`minecraft:<port>`.

- If your DNS TXT record sets `voice=<n>`, grant `udp:<n>` instead of `udp:<port>` (and use
  `minecraft:<n>` in the UDP test).
- If it sets `voice=0`, voice is off: the player grant needs no UDP entry, and you can drop
  the UDP test.

The policy is one of several voice steps on the owner side; the rest (port publishing,
firewall) are in the server owner's voice checklist.
