# The compound-word game, and what playing it turned up

`tools/make-zusammensetzspiel.py` writes a board package that plays the
"Spiegel + Ei" game: one board per round, the two word parts at the top, and
below them the compound they make plus three that they do not. The right key
says the word and turns to the next round; a wrong key says its own word and
leaves the page standing.

That one press doing two things is SPEC.md 7.3's `ext_lautstark_speak_on_navigate`,
which this repository reads as of the pin move to 1.4.0. The package exists so
the flag can be **played** and not only parsed: whether the distractors are well
chosen, whether the words are right, and whether it carries over twenty rounds
are questions for a tablet, and the device side of the talker is still being
built.

```bash
python3 tools/make-zusammensetzspiel.py --out zusammensetzspiel.obz
```

It needs macOS `say` and `afconvert`. Every clip is recorded, because this
viewer plays recordings and nothing else — see below.

## How the rounds are built

Twenty rounds, in `ROUNDS` at the top of the script. The three wrong answers are
chosen the same way every time, and that pattern is the point rather than
decoration:

| Seat | What it is |
|---|---|
| first wrong | shares the **first** part (`Spiegel` + `Ei` → `Spiegelbild`) |
| second wrong | shares the **second** part (→ `Eierbecher`) |
| third wrong | shares neither, usually a word from another round (→ `Schneemann`) |

A child who has understood that *both* halves have to fit rules out the first
two. One who is matching on a single half does not, and which of the two is
happening is visible in **which** wrong key gets pressed. That is the thing to
watch for over twenty rounds, and it is why the distractors are written out in
the open instead of drawn from a shuffle.

The right answer's seat rotates with the round number, so it is never in the
same place twice running: a child who has spotted that position 1 is always
right has learned the board and not the words.

## Three things that were in the way

### The corner wedge gave the answer away

`BoardScreen` draws a corner wedge on any key whose press does something other
than add a word to the sentence: `Wedge.Onward` where the page will change,
`Wedge.Sound` where the key speaks at once. That is a good rule for a
communication board — it tells the person what a press will do *before* they
press it — and it is exactly wrong for a quiz.

Written the obvious way, a round's right key is speak-then-navigate
(`Wedge.Onward`) and its three wrong keys are `speak_immediately`
(`Wedge.Sound`). The right one is then the odd corner out on every board, and a
child who cannot read can win twenty rounds by looking at the marks.

**The package works around it rather than the viewer.** A wrong key is written
as `load_board` back to the board it is already standing on, carrying the same
flag: it speaks, and the navigation lands where it was. All four keys are then
the same shape, wear the same wedge, and the marking says nothing. Nothing in
the viewer changed and nothing in the spec was bent — but the mark is still a
mark, and the general problem is unsolved: **a board that is a quiz wants its
keys not to advertise what they do.** If quiz boards become a thing the talker
does, that is a viewer question and not one the author of a package should have
to be this clever about. The same leak is in the accessibility text
(`describe()`), which reads "spricht und öffnet eine Seite" on exactly one key
per board; the self-navigation closes that too.

### The viewer has no text-to-speech, by design

`Speech` plays clips baked into the package and nothing else. The reasoning is
in the class comment and it is sound: a sentence half in the recorded voice and
half in the phone's is the thing a person notices and cannot unhear, and a
button with no recording is drawn as having no voice, which is a fault a
caregiver can see and go and fix.

The consequence for a generated package is that it must ship its own audio or
be silent, which is why the script records a hundred-odd clips through `say`
rather than leaving the manifest's `ext_lautstark_tts_voice` to do the work. It
runs for a couple of minutes. Nothing here is a defect — it is worth writing
down because the fixture the flag is pinned against states `audio: "tts"`
throughout, so the conformance suite can pass on a package this viewer would
play in silence.

### The "play again" key cannot be `:home`

SPEC.md 7.3 narrows speak-on-navigate to `load_board` and says it MUST be
ignored beside `action: ":home"` — see below. The closing board's "Noch einmal"
key would have been the natural `:home`, and as `:home` it would turn the page
without saying anything. It names `boards/start.obf` through `load_board`
instead, which lands in the same place and is allowed to speak.

That is a small thing here because the game's start board is a real board that
can be named. It would not be small on a package whose only way back is `:home`.

## The narrowing, and where it stands

`ext_lautstark_append_on_navigate` rides on `load_board` **and** on
`action: ":home"`. Its sibling `ext_lautstark_speak_on_navigate` rides on
`load_board` **only**; beside `:home` it MUST be ignored, and a `:home` key
carrying the flag must be indistinguishable from one without it.

This is not an oversight in the spec, and it was checked before it was
implemented. SPEC.md 1.4.0 argues the narrowing in §7.3 in its own paragraph —
the modifier exists for one authoring model, a key naming the page it leads to,
and a board model with no message bar has no start page either, its pages being
a ring the page key cycles — and leaves the door open: *"A future minor version
may widen it if a board model turns up that wants both."* The `navigate-and-speak`
fixture pins it from the other side, pairing its `e2` (flag beside `:home`) with
its `e3` (no flag) so that an importer which widened it on its own would go red.

So the importer implements the narrow rule, and implements it structurally:
`OnActivate.SpeakThenNavigate.then` is typed `Navigate` and not `Navigation`, so
a `Home` cannot be put in it at all. **If the narrowing is later retracted, that
field's type is the change** — widen it to `Navigation`, and `Actions.speaking`
starts being asked at the `:home` sites too.

## What the game does not need

No images and no colours. `ext_lautstark_symbol_source` is `none` and no key
carries a `background_color`, so the viewer draws its own defaults — the design
is `Lautstark/design`'s and a demo package is not the place to invent a second
one. The words are the content, and a round is legible without a picture.
