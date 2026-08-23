# FlyShare wire protocol, version 2

This is the normative description of what FlyShare devices say to each other.
It is written so that a second implementation — on Android, iOS, or anything
else — can interoperate with the Node one without reading its source.

Everything here was taken from the reference implementation in `src/core/`. If
the two ever disagree, the code is what actually ships, and this document is the
bug.

---

## 1. Conventions

| Term | Meaning |
|---|---|
| **base64url** | RFC 4648 §5, no padding. This is what JWK uses, and every key and nonce on the wire is in this form. |
| **u32** | Unsigned 32-bit integer, **big-endian**. |
| **UTF-8 string** | When a value is hashed "as a string", its UTF-8 bytes are hashed — not its decoded value. This matters in §5. |
| **X25519** | RFC 7748. Public keys are 32 raw bytes, carried as base64url (43 characters). |
| MUST / SHOULD / MAY | RFC 2119. |

Protocol version is **2**. Version 1 was unencrypted and is not compatible: a v2
device answers a v1 opening frame with `session-err` and closes. Implementations
MUST NOT negotiate downwards.

## 2. Constants

| Name | Value | Overridable by |
|---|---|---|
| Discovery port (UDP) | `45888` | `FLYSHARE_DISCOVERY_PORT` |
| Multicast group | `239.255.77.88` | `FLYSHARE_MULTICAST` |
| Transfer port (TCP) | `45889` | `FLYSHARE_TRANSFER_PORT` |
| Announce interval | 3000 ms | — |
| Peer expiry | 12000 ms of silence | — |
| Maximum frame body | 4 MiB | — |
| Handshake step timeout | 30 s | — |
| Human decision timeout | 180 s | — |

## 3. Identity

Every device holds one long-term **X25519 key pair**, generated on first run and
never rotated. Losing it invalidates every pairing, which is intended to be a
visible failure rather than a silent one.

A device also has a **device id**: 8 random bytes as 16 lowercase hex
characters. The id is a routing label, not a credential — authentication comes
from the key, so two devices claiming the same id simply fail to complete a
handshake with each other's peers.

Public keys travel as the base64url of their 32 raw bytes (the `x` member of the
JWK encoding).

## 4. Discovery

Discovery is UDP/JSON. Every packet is a single JSON object, no framing.

A device sends each packet **twice**: once to the multicast group, and once to
the IPv4 broadcast address of every non-internal interface it owns. Consumer
routers and host firewalls drop one or the other often enough that sending both
is worth the few hundred bytes.

Receivers MUST ignore packets whose `id` equals their own.

### 4.1 Announce

Sent every 3000 ms, on startup, and immediately in response to a `probe`.

```json
{
  "t": "announce",
  "id": "9b52506ab52eeb22",
  "name": "Vladyslav's ThinkPad",
  "os": "windows",
  "port": 45889,
  "ver": 2,
  "addrs": ["192.168.100.234", "172.18.96.1"]
}
```

`os` is one of `windows`, `macos`, `linux`, `android`, or the platform's own
name. It is a label for the interface and carries no protocol meaning.

`addrs` lists every non-internal IPv4 address of the sender, **best first**.
A receiver SHOULD connect to the first entry that shares a subnet with one of
its own interfaces, preferring its physical adapters over virtual ones, and fall
back to the packet's source address. Without this, a Windows host advertises a
Hyper-V switch address that nothing else on the network can reach.

### 4.2 Probe

```json
{ "t": "probe", "id": "9b52506ab52eeb22" }
```

Sent once on startup. Every device that receives it MUST answer with an
`announce` immediately, so a device that just joined does not wait a full
interval to populate its list.

### 4.3 Bye

```json
{ "t": "bye", "id": "9b52506ab52eeb22" }
```

Sent on clean shutdown. Receivers SHOULD drop the peer at once. A device that
disappears without a `bye` expires after 12 s of silence.

## 5. Framing

Every TCP connection carries **length-prefixed JSON frames**:

```
+---------+-----------------------+
|  u32 N  |  N bytes of UTF-8 JSON |
+---------+-----------------------+
```

`N` MUST NOT exceed 4 MiB; a larger value is a protocol violation and the
connection MUST be closed.

Data connections switch, after their handshake, to alternating frames and raw
payload — see §8.2. Payload bytes are never escaped, base64'd, or parsed.

## 6. Connection types

Both pairing and transfers use the same TCP port. The **first frame** decides
what the connection is:

| First frame `t` | Meaning |
|---|---|
| `pair` | Pairing exchange. Stays plaintext; §7 explains why that is safe. |
| `session` | Secure session setup, then TLS. |

Anything else MUST be answered with `session-err` and the connection closed.

## 7. Pairing

Pairing establishes mutual trust by having a person compare a six-digit number
on two screens. The initiator is whoever dials.

```
initiator (A)                                responder (B)
    │  pair          {ver, device}             │
    │ ────────────────────────────────────────►│
    │  pair-commit   {commit}                  │
    │ ◄────────────────────────────────────────│
    │  pair-reveal   {pub, nonce}              │
    │ ────────────────────────────────────────►│
    │  pair-open     {pub, nonce, device}      │
    │ ◄────────────────────────────────────────│
    │        both compute the same 6 digits     │
    │        a person confirms them on B        │
    │  pair-result   {accept, reason?}         │
    │ ◄────────────────────────────────────────│
```

`device` is `{ "id", "name", "os" }`. `nonce` is 16 random bytes as base64url.

### 7.1 The commitment, and why it is not optional

**B MUST send `pair-commit` before it has seen A's public key**, and A MUST
verify on receipt of `pair-open` that

```
commit == pairingCommitment(pub, nonce)
```

aborting the pairing if it does not match.

Without this step the scheme is not merely weaker, it is broken. A machine in
the middle terminates two exchanges and chooses its own key pair on each leg, so
it can try key after key until the two codes it displays happen to agree — about
a million cheap attempts, which is seconds of work. Committing first means
neither side can still be shopping for a key once it knows the other's, which
puts an attacker back at one chance in a million per attempt, rate-limited by a
person pressing a button.

### 7.2 Commitment derivation

```
commit = base64url( SHA-256(
    "flyshare-sas-commit-v2"        as UTF-8 bytes
  ‖ pub                             as UTF-8 bytes of the base64url string
  ‖ nonce                           as the 16 decoded raw bytes
) )
```

Note the asymmetry: `pub` is hashed as its **textual** base64url form, while
`nonce` is hashed as **decoded bytes**. An implementation that decodes both, or
neither, will not interoperate.

### 7.3 Six-digit code derivation

Both sides compute, with `shared = X25519(own identity private, peer identity public)`:

```
salt     = decode(initiatorNonce) ‖ decode(responderNonce)      (32 bytes)
info     = "flyshare-sas-v2|" ‖ initiatorPub ‖ "|" ‖ responderPub   (UTF-8)
material = HKDF-SHA256(ikm = shared, salt = salt, info = info, L = 4)
code     = decimal(u32(material) mod 1000000), zero-padded to 6 digits
```

`initiatorPub` and `responderPub` are the base64url strings, in role order —
initiator first regardless of who computes it.

### 7.4 Result

`pair-result` carries `{ "accept": true }` or `{ "accept": false, "reason": "…" }`.
On acceptance **both** devices store the other's public key against its device
id. A rejected or timed-out pairing stores nothing.

The code is roughly 20 bits. An active attacker gets one chance in a million per
attempt; the defence rests on a person actually looking at the second screen.

## 8. Secure sessions

Everything that is not pairing happens inside TLS 1.3.

### 8.1 Setup

```
client                                        server
   │  session      {ver, deviceId, ephPub}      │
   │ ──────────────────────────────────────────►│
   │  session-ok   {deviceId, ephPub}           │   or session-err
   │ ◄──────────────────────────────────────────│
   │           both derive the PSK               │
   │        TLS 1.3 handshake on this socket     │
```

`ephPub` is a **fresh X25519 public key generated for this connection** and
discarded afterwards.

The server answers `session-err` and closes when it cannot proceed:

```json
{ "t": "session-err", "needsPairing": true, "reason": "this device has not been paired yet" }
```

`needsPairing` distinguishes "we have never met" from a version mismatch, so the
interface can offer to pair instead of showing a network error.

A client MUST also verify that `session-ok.deviceId` equals the device it meant
to reach, and abort otherwise.

### 8.2 Session key

```
ephemeralShared = X25519(own ephemeral private, peer ephemeral public)
pairingSecret   = X25519(own identity  private, peer identity  public)
ordered         = the two device ids, sorted lexicographically, joined with "|"
info            = "flyshare-session-v2|" ‖ ordered            (UTF-8)

psk = HKDF-SHA256(ikm = ephemeralShared, salt = pairingSecret, info = info, L = 32)
```

Sorting the ids means both ends derive the same key regardless of who dialled.

The ephemeral half provides forward secrecy: the key that protected a connection
existed only in memory, so recorded traffic stays unreadable even if the identity
file is stolen later. The pairing half provides authentication: a device that
never paired cannot produce this value, so its TLS handshake fails and there is
nothing to fall back to.

### 8.3 TLS

- TLS **1.3 only** (`minVersion = maxVersion = TLSv1.3`).
- External PSK, with PSK identity `flyshare` (ASCII).
- No certificates are presented or verified. The PSK is the authentication.
- The reference implementation negotiates `TLS_CHACHA20_POLY1305_SHA256`;
  any TLS 1.3 AEAD suite is acceptable.

### 8.4 What runs inside

The first frame **inside** TLS declares the connection's purpose:

| `t` | Purpose |
|---|---|
| `offer` | Control connection for one transfer (§9). |
| `data` | Bulk data connection belonging to a transfer (§9.3). |

## 9. Transfers

A transfer has one control connection and *N* data connections, all to the same
peer, all inside their own TLS sessions.

### 9.1 Offer

```json
{
  "t": "offer",
  "ver": 2,
  "transferId": "3f2a…",
  "from": { "id": "…", "name": "…", "os": "…" },
  "files": [{ "rel": "Photos/IMG_0001.CR3", "size": 26214400 }],
  "totalSize": 26214400,
  "streams": 4
}
```

`transferId` is any string unique to the sender; the reference implementation
uses a UUID. `rel` uses **forward slashes** on every platform, so a Windows
sender and an Android receiver agree on the layout. `streams` is advisory.

The receiver MUST treat `from.id` as untrusted display data and use the device
id proven by the TLS handshake instead.

### 9.2 Answer

The receiver asks a person, unless it is configured to auto-accept for
already-paired devices. Nothing may be written to storage before this answer.

```json
{ "t": "offer-result", "accept": true, "token": "…32 hex chars…" }
```

or

```json
{ "t": "offer-result", "accept": false, "reason": "declined by user" }
```

The `token` authorises data connections for this transfer and MUST be
unpredictable. Before replying with acceptance, the receiver SHOULD create every
destination file at its final size, so parallel streams can write at their own
offsets without extending the file.

### 9.3 Data connections

Each data connection opens its own secure session, then sends:

```json
{ "t": "data", "transferId": "3f2a…", "token": "…" }
```

The receiver answers `{"t":"data-ok"}`, or `{"t":"data-err","reason":"…"}` and
closes. After `data-ok` the connection is **one-directional** and alternates:

```
+--------------------------------+---------------------------+
| frame: {"t":"chunk",           |  exactly `length` bytes   |
|   "fileIndex", "offset",       |  of file content          |
|   "length"}                    |                           |
+--------------------------------+---------------------------+
```

repeated for as many chunks as this connection carries, then a final
`{"t":"end"}` frame before closing.

Chunking is the sender's choice. The reference implementation splits files
larger than 32 MiB and gives each connection whole small files, which spreads a
thousand-file folder across streams without extra bookkeeping. Files of size 0
get no chunk at all — the receiver created them during preallocation.

A receiver MUST reject a chunk whose `offset + length` exceeds the declared file
size, and MUST close the connection on any frame it did not expect.

### 9.4 Completion and failure

When every declared byte has arrived and been flushed, the receiver sends
`{"t":"done","transferId":"…"}` on the **control** connection.

| Message | Direction | Meaning |
|---|---|---|
| `{"t":"cancel","reason"}` | sender → receiver | Abandon the transfer. |
| `{"t":"error","reason"}` | receiver → sender | Transfer failed; stop sending. |

A dropped control connection means the transfer failed. There is no resume: an
interrupted transfer starts again.

## 10. Receiver obligations for paths

`rel` comes from another machine and MUST be treated as hostile. A receiver MUST:

1. Split on both `/` and `\`.
2. Drop every `.` and `..` segment, and every empty segment.
3. Replace characters illegal on the target filesystem. The reference
   implementation replaces `< > : " | ? *` and U+0000–U+001F with `_`.
4. On Windows, additionally strip trailing dots and spaces, and prefix reserved
   device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`).
5. Resolve the result and confirm it is still inside the download directory,
   rejecting it otherwise.
6. Never overwrite silently — the reference implementation renames to
   `name (2).ext`.

A manifest containing `../../ESCAPED.txt` must land inside the download folder,
not beside it. This is covered by `test/protocol.js`.

## 11. Interoperability checklist

A new implementation is compatible when it can:

- [ ] Announce, probe, and expire peers on UDP 45888, over both multicast and broadcast.
- [ ] Choose a reachable peer address from `addrs` rather than trusting the packet source.
- [ ] Complete a pairing as **both** initiator and responder, including verifying the commitment.
- [ ] Produce the same six digits as the Node implementation for the same inputs.
- [ ] Derive an identical session PSK and complete a TLS 1.3 external-PSK handshake.
- [ ] Refuse an unpaired peer with `session-err` + `needsPairing`.
- [ ] Send and receive a multi-file, multi-stream transfer with byte-identical results.
- [ ] Contain a hostile `rel` path.
- [ ] Reject a data connection bearing the wrong token.

The Node test suite in `test/` exercises every one of these and is the practical
reference for expected behaviour.
