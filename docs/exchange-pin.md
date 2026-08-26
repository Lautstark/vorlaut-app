# Pinning the exchange spec

The board package format — `SPEC.md` and the conformance fixtures — lives in
`Lautstark/vorlaut-diy-talker` under `exchange/`, not here. This file records how
this repository consumes it and why it is done this way.

## The rule

**Do not copy the fixtures into this repository.** The spec's own README is
explicit about it, and the reasoning is the point rather than the ceremony: a
copied fixture stops tracking the spec the moment either side changes, and a
stale fixture passes forever. The whole reason to have conformance fixtures is
that a spec change shows up as a failing build. A copy converts that into silent
divergence, which is worse than having no fixtures at all — it looks like
coverage.

So the fixtures are fetched at a pinned commit, and moving the pin is a
deliberate change with a test run attached, never a routine bump.

## Current state

```properties
exchange.sha=4055c1fc387865015f7da1334fb5d1c6509307a1
```

That commit carries `SPEC.md` 1.2.0 and fifteen fixtures, all of which pass.

Moved from `9412459` (1.1.0, fourteen) for a rule rather than a fixture, which
is the first time this pin has moved for one. 1.2.0 adds
`ext_lautstark_append_on_navigate`: a navigating button may append its entry to
the message bar before it navigates, on `load_board` and on `:home` alike. The
fixture is `navigate-and-append`, and the two new `on_activate` values it states
are `append+navigate:<board id>` and `append+home`.

**A minor bump, so the importer at the old pin would still have imported this
fixture** — it would have ignored the unknown field under §10.3 and treated the
carrier as plain navigation. What it would not have done is pass: the fixture
states the compound `on_activate`, and that is the difference between the
intended degradation and conformance. Reading the field is this pin's whole
job.

Before that, `9412459` (1.1.0, fourteen), moved for one fixture. `unknown-ext`
lost `ext_vorlaut_active` from its board and gained `ext_vorlaut_color` in its
place: the talker deleted the `active` field along with the active/inactive
distinction it expressed, so the fixture had been holding this importer against a
field no builder writes any more.

**No rule moved with it.** `SPEC.md` is untouched, there is no version bump, and
an importer passing at the old pin passes at this one — `ext_vorlaut_active` was
never named in the spec, only in a fixture. What the new shape adds is a trap the
old one could not spring: the board carries `ext_vorlaut_color` and
`ext_lautstark_board_color` at once, holding **different** colours, so an importer
reading the talker's namespace instead of ours answers `#FF6B35` and fails rather
than passing because the two happened to agree. `BoardPackageImporter` reads only
`ext_lautstark_board_color`, which is why this one was green on the first run.

Before that, `5ffeb57` (1.0.0, thirteen fixtures), moved for
`ext_lautstark_first_column_gap` — SPEC.md §4.1's layout hint and the
`first-column-gap` fixture that comes with it. A minor bump, so the importer at
that pin would have kept passing; what it would not have done is read the field,
and the fixture asserting the default is the one that says so.

No `exchange-v1.2.0` tag is cut and none will be until a real board round-trips
to a tablet, so a commit SHA is the pin. The spec is still a draft and the
fixtures will move; each move is a deliberate change with a test run attached.

While the fetch task fetches from a fixed SHA, a raw URL carrying a full commit
SHA is immutable, which is the property a pin needs. If `exchange.sha` is ever
emptied the task **fails with a clear message** rather than skipping — a
conformance suite that quietly does nothing when its inputs are missing is the
same silent divergence a stale copy causes, reached from the other side.

## Setting the pin

Put the commit SHA in `gradle.properties`:

```properties
exchange.sha=<40-character commit SHA>
```

The SHA must be reachable from the default branch of the public repository, so CI
can fetch it without a token. Then run the suite and commit the result together
with the run:

```bash
./gradlew :boardpackage:test
```

## Blocked fixtures

`ConformanceTest.blocked` names fixtures that are not self-consistent and are
therefore not asserted against. **It is currently empty.**

It held `multipage` while that fixture's `.obz` and `.expected.json` disagreed
about board `essen` — the package rendered a third button the expectation did
not list. The root cause was in the generator, whose `members` and `expected`
were two sibling literals rather than one, so the two halves could drift and
byte-reproducibility could not catch it. Both are fixed upstream.

A blocked fixture is **not** a skipped one. Each entry is checked to still be
failing, so the day the fixture is corrected upstream the suite goes red and says
which block to remove. Removing one has to be a deliberate act rather than
something that happens by drift, which is the same reason the fixtures are pinned
rather than copied.

## Working against a local checkout

While developing against an unreleased spec, point the build at a working tree
instead. This is for local work only — it is never set in CI, and it does not
count as a pin.

```bash
./gradlew :boardpackage:test -Pexchange.localPath=$HOME/Code/vorlaut/exchange
```

Note the local directory is `~/Code/vorlaut` even though the repository is now
`Lautstark/vorlaut-diy-talker`; only the repository was renamed.
