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

The pin is `5ffeb579bccefc71e7b63f2e19008440df0c3179`, recorded as `exchange.sha` in
[`gradle.properties`](gradle.properties). Moving it is a deliberate change with a
test run attached, never a routine bump — see
[`docs/exchange-pin.md`](docs/exchange-pin.md).

All 13 fixtures pass.

### And one package the builder actually wrote

The fixtures say this importer reads the format. They cannot say that it reads
what the one program writing packages in real life produces — vorlaut validates
its output against its own reading of `SPEC.md`, and this suite validates
fixtures against the same document, so both can pass and still not meet.

So one real export is committed, at
[`boardpackage/src/test/resources/builder/`](boardpackage/src/test/resources/builder/),
and `BuilderPackageTest` opens it. It is a sample rather than a fixture — it
tracks nothing and is re-cut when the export changes, which is why it may live
here at all. Its README says how to cut a new one.

The first run of it found a defect neither side could see: every board said
`locale: "en"` over German sentences, because the builder took that field from
its own page language. On a tablet that is a German board read aloud in an
English voice. Fixed in vorlaut-diy-talker@3006e38.

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

To develop against an unreleased change to the spec, point the build at a local
checkout instead. This is for local work only and is not a pin:

```bash
./gradlew build -Pexchange.localPath=$HOME/Code/vorlaut/exchange
```

## Releasing

Push a `vMAJOR.MINOR.PATCH` tag. CI runs the suites first and, only if they pass,
builds a signed APK and attaches it to a GitHub Release — the version comes from
the tag, and nothing in the tree needs bumping. See
[`docs/releasing.md`](docs/releasing.md), which is also where the signing key and
its secrets are described.

```bash
git tag v0.2.0 && git push origin v0.2.0
```

## Licence

MIT. See [`LICENSE`](LICENSE).
