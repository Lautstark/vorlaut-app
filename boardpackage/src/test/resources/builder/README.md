# Packages the builder wrote

`vorlaut-diy.obz` and `vorlaut-tablet.obz` are not conformance fixtures. They
are two files the vorlaut builder actually produced, kept here so that
`BuilderPackageTest` and `BuilderTabletPackageTest` can open them with this
importer.

## Why this does not break the no-copies rule

[`docs/exchange-pin.md`](../../../../../docs/exchange-pin.md) forbids copying the
conformance fixtures into this repository, because a copied fixture stops
tracking the spec the moment either side changes and then passes forever.

These files track nothing, which is exactly why they may live here. Each is a
snapshot of one builder's output on one day. They are not evidence about the
format; they are evidence that the two programs meet — the only thing neither
suite can show on its own, since both validate against their own reading of the
same document.

## Why there are two

They cover different halves of the specification, and the second exists because
the first could not reach the other half.

A **talker** Sammlung is a five-key device: a fixed 2×3 grid with the speaker's
corner empty, every button speaking at once because there is no message bar, and
a ring of `load_board`s. That is about a third of what SPEC.md describes, and a
five-key device has no use for the rest.

A **tablet** Sammlung uses the rest: a grid the builder chose, buttons that
compose into the message bar, `background_color` carrying a word class, the
`:home` action, and navigation that is a graph rather than a ring. None of
those can appear in a talker package at all.

## What they are

### `vorlaut-diy.obz`

Exported from a Sammlung with two sets, made through the page by
`e2e/app_package.spec.ts`:

| | |
|---|---|
| Boards | `set-1` "Morgens", `set-2` "Spielen", ringed by their set keys |
| Speech keys | three, all `ext_lautstark_speak_immediately` |
| Colours | none at all. The set colour drew the border round all five displays and was written as `border_color` per key and `ext_lautstark_board_color` per board; the builder writes neither now. |
| Pictures | one PNG, 16×16, uploaded rather than picked — so `symbol_source: none` |
| Recordings | three Ogg Opus clips, from the browser's own WebCodecs encoder |
| Voice | `de-DE-KatjaNeural`, `locale: de-DE` |
| Profile | `ext_lautstark_spec_version` 1.2.0, above the 1.1.0 this importer implements — SPEC.md §12 requires accepting a higher minor, and this is where that happens rather than being asserted |

**Cut from vorlaut-diy-talker@4055c1f, 2026-08-26.**

### `vorlaut-tablet.obz`

Exported from a tablet Sammlung with two pages, made through the page by
`e2e/editor_app.spec.ts`:

| | |
|---|---|
| Grid | 3×5, the same on both boards |
| Boards | `board-1` (root, unnamed), `board-2` "Essen", reached by one button |
| Buttons | three appending, one `ext_lautstark_speak_immediately`, one navigating, and `:home` |
| Colours | Modified Fitzgerald Key, as `background_color` per button. No page colour — the builder stopped writing `ext_lautstark_board_color`, for this half and for the talker. |
| Pictures | one PNG, uploaded rather than picked — so `symbol_source: none` |
| Recordings | Ogg Opus, on the buttons that speak their own text |
| Voice | `de-DE-KatjaNeural`, `locale: de-DE` |
| Profile | `ext_lautstark_spec_version` 1.2.0, as above |

Two of its buttons share one clip. That is not a defect: the stand-in
synthesiser answers two of these sentences identically and content-addressed
naming writes one member for them, which the test asserts rather than tolerates.

No `:speak` and no `:clear` any more: the builder stopped putting those in
cells once the viewer drew Sprechen, Zurück and Weg on the message bar itself,
so a grid button for one of them spent a cell out of fifteen duplicating a
control that is always on screen. `:home` stays, because nothing in the chrome
goes home. Neither action has left the format, and `message-bar.obz` is still
normative about both — what these two samples no longer carry is
*builder-written* evidence for them, which is a real loss and is recorded here
rather than in a commit message.

**Cut from vorlaut-diy-talker@4055c1f, 2026-08-26.**

## Cutting a new one

Whenever the export changes shape. From a checkout of
`Lautstark/vorlaut-diy-talker`:

```bash
DUMP_TO=/tmp/vorlaut-diy.obz E2E_PORT=8842 \
  npm run test:e2e -- e2e/app_package.spec.ts --grep "passes the spec"

DUMP_TO=/tmp/vorlaut-tablet.obz E2E_PORT=8842 \
  npm run test:e2e -- e2e/editor_app.spec.ts --grep "a tablet Sammlung leaves"
```

Copy the result over the file here and run `./gradlew :boardpackage:test`.

Use `npm run test:e2e` rather than `npx playwright test`: the first builds the
site and the second serves whatever build is already there, so the second can
quietly dump a sample of the code as it was before your change.

Two fields move on every cut and nothing asserts them: the package id is a fresh
UUID per Sammlung, and `ext_lautstark_modified` is the moment it was made.

Then update the line under each file above, because the point of these is that
somebody can tell how old they are.
