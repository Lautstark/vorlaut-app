# vorlaut-app

The Android viewer for Lautstark board packages. It imports a `.obz` package and
renders it. That is all it does.

**A pure viewer.** No editing and no symbol search. Every image and every sound
a board needs is baked into the package it came in, which is what makes "resolves
no reference" a property of the format rather than a habit of the code — nothing
here fetches anything at render time, because nothing here fetches anything.

**It receives, and it cannot send.** There *is* an `INTERNET` permission in the
manifest now, so that a Sammlung can arrive from the editor over the home network
rather than on a memory stick. That sentence replaces a stronger one. This
README used to say there was no such permission and there must never be one, and
the reason given was that a viewer which cannot reach the network cannot move a
non-redistributable package's bytes off the device — `exchange/SPEC.md` §5.2.
That argument was structural and it is gone; what stands in its place is
narrower, and is the honest version:

- **There is no client.** `PackageReceiver` is a server. Nothing in this app
  opens an outbound connection, builds a URL, or reads a response.
- **There is one route.** `POST /paket`, plus the `OPTIONS` a browser sends
  before it. No `GET`, no listing, no path that hands back a package's bytes or
  its metadata — a socket able to serve them is exactly the path §5.2 forbids.
- **It is open only while somebody is watching it.** The listener is bound when
  the receive screen appears and closed when it is left or the app is
  backgrounded. No foreground service, and no port open while a child is using
  the board.

`PackageReceiverTest` is what keeps that true after everyone who agreed to it has
gone: it sweeps every HTTP method against the route and every plausible path
against the router, and fails if anything but the one `POST` is served. The
guarantee moved from *structurally impossible* to *one route, POST only, enforced
by a test* — deliberately, and with the test as the load-bearing part.

What a test on the JVM cannot check is a tablet, a laptop and a home network.
[`docs/receiving-a-package.md`](docs/receiving-a-package.md) is the checklist for
that half, and records what one run of it found.

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

## Setting up the tablet a child will hold

The app asks Android to pin itself to the screen whenever the board is showing,
and re-asks whenever it comes back to the front. That stops Home, Overview,
notifications and a stray swipe. It is **not** a kiosk: this app is not a device
owner, so Android's own unpin gesture still exists, and no app can switch it
off. Making it a real lockdown needs device-owner provisioning, which means
factory-resetting the tablet — a different piece of work with a different cost.

Three things have to be turned on by hand, once, on the tablet itself. There is
no adb or API equivalent: Android requires a human to confirm them in Settings.
Wording below is One UI 8 / Android 16.

1. **Bildschirmfixierung** — Einstellungen → Sicherheit und Datenschutz →
   Weitere Sicherheitseinstellungen. Without it the first `startLockTask()` only
   raises a confirmation dialog instead of pinning.
2. **A device screen lock (PIN, pattern or password).** Where the device offers
   *Zum Aufheben der Fixierung PIN abfragen*, this is what that toggle needs, and
   it turns the unpin gesture into "gesture, then the device PIN". On One UI 8
   the toggle is gone — but on that same version the standard unpin gesture does
   not exit a pinned app at all, so there is nothing left to gate. Verify by
   trying to unpin it yourself before handing the tablet over; do not assume
   either way from the version number.
3. **The app's own PIN**, in vorlaut's Einstellungen. This is a different lock
   from the two above and guards a different door: the way out *through the app*,
   the handle at the left edge of the board. Android's pinning guards the ways
   out around it.

After a reboot somebody has to tap the icon once — the app does not start
itself. Pinning re-engages on its own as soon as the board is showing.

**The board is nailed to the glass.** It is drawn landscape, filling the screen,
on the same pixels every time. Turning the tablet does not move it: held the
right way round it is upright, held the other way round it is upside down,
exactly as a sheet of paper taped to the screen would be. Nothing reflows,
nothing spins, nothing has to be worked out — a board is a page whose shape and
position a child learns, and the strongest form of that promise is one the
device cannot interrupt.

Asking Android for landscape would have been the obvious way and it no longer
works: it ignores a fixed `screenOrientation` on large screens from Android 16,
and the property that opted out of that is gone at targetSdk 37, which is what
this app compiles against. Measured on a Galaxy Tab, not inferred. So the one
thing the app does is undo Android's own turning, which is why this holds
whether auto-rotate is on or off.

Two things this does not reach, both because they are not the app's window:
anything Android draws itself, such as the pinning notice, and Compose's dialogs
and menus. That is why it wraps the board rather than the whole app — and the
list screens were never wrong in portrait anyway.

Turning the tablet no longer restarts anything either. It used to: the activity
was recreated, and the board came back on its start page with the sentence bar
empty. The app now handles the configuration change itself.

## Licence

MIT. See [`LICENSE`](LICENSE).
