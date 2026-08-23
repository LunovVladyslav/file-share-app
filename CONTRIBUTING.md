# Contributing

## Getting set up

```bash
git clone https://github.com/LunovVladyslav/file-share-app.git
cd file-share-app
npm ci      # only fetches the two packaging tools; the app has no runtime dependencies
npm start
```

To see a transfer actually happen you need two instances. `FLYSHARE_HOME`
relocates all state, so a second one can run beside the first:

```bash
FLYSHARE_HOME=/tmp/flyshare-b FLYSHARE_TRANSFER_PORT=45899 FLYSHARE_UI_PORT=45891 npm start
```

## Before opening a pull request

```bash
npm test
```

Three suites run: pairing (including the short-authentication-string
properties), protocol and consent, and a full end-to-end transfer that compares
SHA-256 on both sides. They run as separate processes because each device needs
its own identity key.

## The protocol document

`docs/PROTOCOL.md` is normative: it is what a second implementation reads
instead of this source tree. `test/spec.js` re-derives the keys and codes from
that document and compares them against the implementation, so the two cannot
drift apart silently.

If you change anything on the wire, change the document in the same commit. A
protocol change without a document change is the bug this project is most likely
to ship.

## House rules

- **No runtime dependencies.** `dependencies` stays empty. Build-time tooling in
  `devDependencies` is fine.
- **Anything touching consent, pairing or paths needs a test.** Those are the
  parts where a regression is invisible until it matters.
- **Comments explain why, not what.** The code says what it does.
- Interface strings live in `ui/i18n.js`. A new string needs all four
  languages; plural forms go through `Intl.PluralRules` rather than hand-written
  rules.

## Adding a language

Add an entry to `LANGUAGES` and a block to `STRINGS` in `ui/i18n.js`, then add
the code to the allowlist in `src/ui-server.js`. Nothing else needs touching —
plural handling and number formatting come from `Intl`.
