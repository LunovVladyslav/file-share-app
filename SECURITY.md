# Security

## Reporting a vulnerability

Please open a [security advisory](https://github.com/LunovVladyslav/file-share-app/security/advisories/new)
rather than a public issue. I will respond as quickly as I reasonably can.

## What FlyShare defends against

FlyShare is designed for a network you already trust — home or office Wi-Fi —
and it defends against the things that go wrong there.

| Threat | How it is handled |
|---|---|
| Someone reading your files off the air | Every byte travels inside TLS 1.3. There is no unencrypted mode to fall back to. |
| A device on the network pushing files at you | Nothing is written until you accept. Unpaired devices cannot get past the handshake. |
| A device impersonating one you trust | The session key is derived from the key pinned during pairing. An impostor cannot complete the TLS handshake. |
| Someone intercepting the pairing itself | The six-digit code is derived from the key exchange and shown on both screens. A device in the middle produces a different number on each. |
| A stolen key file revealing past transfers | Each connection gets a fresh ephemeral key. Recorded traffic stays unreadable even if `identity.json` is later compromised. |
| A malicious sender writing outside your download folder | Incoming paths are stripped of `..`, drive letters and reserved names, then confined to the download folder. |
| A website in another browser tab driving the app | The local interface listens on 127.0.0.1 only and requires a per-run token that cross-origin JavaScript cannot read or send. |

Each of these has a test in `test/`.

## Known limits

- **The six-digit code carries about 20 bits.** An active attacker has roughly a
  one-in-a-million chance per attempt of producing a matching pair of codes. The
  commitment step in the pairing protocol prevents them from searching for one.
- **The interface token is readable by other processes on the same machine.** It
  is delivered inside the page's HTML. The defence is aimed at other browser
  tabs, not at malicious software already running as your user.
- **Release binaries are not signed with a paid certificate.** Windows
  SmartScreen and macOS Gatekeeper will warn on first launch. Build from source
  if that matters to you.
- **Device names are labels, not identity.** Authentication rests on the pinned
  key; a renamed device is still the same device.
