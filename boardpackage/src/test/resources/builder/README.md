# A package the builder wrote

`vorlaut-diy.obz` is not a conformance fixture. It is one file that the vorlaut
builder actually produced, kept here so that `BuilderPackageTest` can open it
with this importer.

## Why this does not break the no-copies rule

[`docs/exchange-pin.md`](../../../../../docs/exchange-pin.md) forbids copying the
conformance fixtures into this repository, because a copied fixture stops
tracking the spec the moment either side changes and then passes forever.

This file tracks nothing, which is exactly why it may live here. It is a
snapshot of one builder's output on one day. It is not evidence about the
format; it is evidence that the two programs meet — the only thing neither
suite can show on its own, since both validate against their own reading of the
same document.

## What it is

Exported from a Sammlung with two sets, made through the page by
`e2e/app_package.spec.ts`:

| | |
|---|---|
| Boards | `set-1` "Morgens", `set-2` "Spielen", ringed by their set keys |
| Speech keys | three, all `ext_lautstark_speak_immediately` |
| Pictures | one PNG, 16×16, uploaded rather than picked — so `symbol_source: none` |
| Recordings | three Ogg Opus clips, from the browser's own WebCodecs encoder |
| Voice | `de-DE-KatjaNeural`, `locale: de-DE` |

The audio is synthesised from a stand-in for Azure rather than from Microsoft:
a real piper synthesis pulls tens of megabytes of onnx from a CDN, which is not
something a test suite should do. Everything downstream of the samples — the
levelling, the encoder, the Ogg container, the archive — is the real thing.

## Cutting a new one

Whenever the export changes shape. From a checkout of
`Lautstark/vorlaut-diy-talker`:

```bash
DUMP_TO=/tmp/vorlaut-diy.obz E2E_PORT=8842 npm run test:e2e -- e2e/app_package.spec.ts --grep "passes the spec"
```

Copy the result over `vorlaut-diy.obz` and run `./gradlew :boardpackage:test`.
Two fields move on every cut and nothing asserts them: the package id is a fresh
UUID per Sammlung, and `ext_lautstark_modified` is the moment it was made.

Then update the line below, because the point of this file is that somebody can
tell how old it is.

**Cut from vorlaut-diy-talker@16cfd1f, 2026-08-24.**
