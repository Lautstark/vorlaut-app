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

## Current state: unpinned

`exchange.sha` in [`gradle.properties`](../gradle.properties) is empty.

The spec repository was renamed from `vorlaut` to `vorlaut-diy-talker`, so the
URL moved, and no `exchange-v1.0.0` tag is cut — the spec is a draft and stays
one until a real board round-trips to a tablet. There is therefore no ref that
can honestly be called stable.

While `exchange.sha` is empty the fixture-fetch task **fails with a clear
message** rather than skipping. That is on purpose. A conformance suite that
quietly does nothing when its inputs are missing is the same silent-divergence
failure as a stale copy, arrived at from the other direction.

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

## Working against a local checkout

While developing against an unreleased spec, point the build at a working tree
instead. This is for local work only — it is never set in CI, and it does not
count as a pin.

```bash
./gradlew :boardpackage:test -Pexchange.localPath=$HOME/Code/vorlaut-diy-talker/exchange
```
