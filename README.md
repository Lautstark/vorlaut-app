# vorlaut-app

The Android viewer for Lautstark board packages. It imports a `.obz` package and
renders it. That is all it does.

**A pure viewer.** No editing, no symbol search, and no network access — there is
no `INTERNET` permission in the manifest and there must never be one. Every image
and every sound a board needs is baked into the package it came in, which is what
makes "resolves no reference" a property of the format rather than a habit of the
code. It is also what makes the non-redistributable rule enforceable: a package
whose bytes must not leave the device is safest in an app that cannot send them.

The DIY ESP32 talker lives in [`Lautstark/vorlaut-diy-talker`](https://github.com/Lautstark/vorlaut-diy-talker)
and is a different thing with a different board model. Nothing here reads its
format.

## Layout

| Module | What it is |
|---|---|
| `:boardpackage` | The domain model and the importer. Plain Kotlin, no Android SDK, unit-tested on the JVM. |
| `:app` | Jetpack Compose. The import path and a screen listing what was parsed. |

`:boardpackage` is a JVM module rather than an Android library on purpose. The
importer is the part that has to be exactly right, and the conformance fixtures
are the only thing that says whether it is — so they must run in a plain `test`
task, with no emulator and no Robolectric between the assertion and the parser.
A Gradle check fails the build if anything under `boardpackage/src/main` imports
`android.*`, because the first accidental `android.util.Log` would quietly undo
that.

## The exchange format

The format is specified outside this repository, in
[`Lautstark/vorlaut-diy-talker`](https://github.com/Lautstark/vorlaut-diy-talker)
under `exchange/`: `SPEC.md` and the conformance fixtures in `fixtures/`. Those
fixtures are the acceptance criteria for `:boardpackage` — every one of them must
produce exactly the outcome its `.expected.json` describes.

**The fixtures are never copied into this repository.** They are fetched at a
pinned commit. A copy stops tracking the spec the moment either side changes, and
a stale fixture passes forever — which is the one failure this arrangement exists
to avoid. A spec change has to surface as a failing build, not as silent
divergence.

> **The pin is not set yet.** The spec repository was renamed and no
> `exchange-v1.0.0` tag is cut, so there is no stable ref to pin. Until a commit
> SHA is recorded in `exchange.sha`, the fixture-fetch step fails with a clear
> message and **CI cannot be green** — deliberately, because a conformance suite
> that quietly does nothing when its inputs are missing is the same silent
> divergence a stale copy causes. See [`docs/exchange-pin.md`](docs/exchange-pin.md).

One fixture, `multipage`, is on a named blocked list: its `.obz` and its
`.expected.json` contradict each other, so no importer can satisfy both. The
block is checked to still be failing, so correcting the fixture upstream turns
the suite red and names the block to remove.

## Building

Requires JDK 21 and an Android SDK with API 37. `local.properties` needs an
`sdk.dir` line; it is git-ignored.

```bash
./gradlew build
```

The parser's own suite, which is the fast one and the one that matters:

```bash
./gradlew :boardpackage:test
```

While the pin is unset, point the build at a local checkout of the spec:

```bash
./gradlew build -Pexchange.localPath=$HOME/Code/vorlaut-diy-talker/exchange
```

## Licence

MIT. See [`LICENSE`](LICENSE).
