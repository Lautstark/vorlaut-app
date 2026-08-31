#!/usr/bin/env python3
"""Writes the "Spiegel + Ei" compound-word game as a board package.

The game is one board per round: at the top the two word parts, below them the
compound they make and three that they do not. The right key says the word and
turns the page; a wrong key says its own word and leaves the page standing.
That one press doing two things is SPEC.md 7.3's `ext_lautstark_speak_on_navigate`,
and this package exists so the flag can be played rather than only parsed.

**Why this is a script and not a committed .obz.** The exchange fixtures are
fetched at a pinned commit and never copied in, because a copied fixture stops
tracking the spec and then passes forever (docs/exchange-pin.md). A committed
demo package is the same hazard one step down: it would be a board built against
whatever the spec said the day somebody ran this, with nothing to notice when
that stopped being true. A generator is re-run instead, and what it writes is
checked against the importer by ZusammensetzspielTest, which builds the same
boards in-process.

**Why every key navigates, including the wrong ones.** A wrong key carries
`load_board` back to the board it is already on. It could have been written as a
plain `ext_lautstark_speak_immediately` key — it would sound identical — but the
viewer draws a corner wedge that says what a press does, and `speak_immediately`
and speak-then-navigate get *different* wedges. On a board of four answers that
marks the right one, and a child who cannot read can win every round by looking
at the corners. Self-navigation makes all four keys the same shape, so the wedge
says "this key speaks and the page may turn" about all of them and gives nothing
away. See docs/zusammensetzspiel.md.

Usage:
    python3 tools/make-zusammensetzspiel.py [--out zusammensetzspiel.obz] [--voice Anna]

Needs macOS `say` and `afconvert`: the viewer speaks recorded clips only and has
no text-to-speech of its own, so a package with no recordings is a silent game.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import zipfile

SPEC_VERSION = "1.4.0"
PACKAGE_ID = "b7f2c0d4-0000-4000-8000-5a6d69656c00"

# One round: the two parts, the compound they make, and three they do not.
#
# The three wrong answers are chosen the same way every round, which is what
# makes this a test of the word rather than of luck: the first shares the *first*
# part, the second shares the *second* part, and the third shares neither and is
# usually a word from another round. A child who has understood that both halves
# have to fit rules out the first two; one who is matching on a single half does
# not. That pattern is the thing worth judging over twenty rounds, so it is
# written here in the open rather than buried in a shuffle.
ROUNDS: list[tuple[str, str, str, list[str]]] = [
    ("Spiegel", "Ei", "Spiegelei", ["Spiegelbild", "Eierbecher", "Schneemann"]),
    ("Hand", "Schuh", "Handschuh", ["Handtuch", "Schuhkarton", "Regenbogen"]),
    ("Schnee", "Mann", "Schneemann", ["Schneeball", "Feuerwehrmann", "Zahnbürste"]),
    ("Regen", "Bogen", "Regenbogen", ["Regenschirm", "Bogenlampe", "Apfelbaum"]),
    ("Zahn", "Bürste", "Zahnbürste", ["Zahnarzt", "Haarbürste", "Fußball"]),
    ("Sonnen", "Blume", "Sonnenblume", ["Sonnenschirm", "Blumentopf", "Haustür"]),
    ("Apfel", "Baum", "Apfelbaum", ["Apfelsaft", "Baumhaus", "Tischdecke"]),
    ("Fuß", "Ball", "Fußball", ["Fußweg", "Ballspiel", "Kinderwagen"]),
    ("Haus", "Tür", "Haustür", ["Hausschuh", "Türklinke", "Wasserhahn"]),
    ("Tisch", "Decke", "Tischdecke", ["Tischbein", "Bettdecke", "Halskette"]),
    ("Blumen", "Topf", "Blumentopf", ["Blumenwiese", "Topflappen", "Teekanne"]),
    ("Kinder", "Wagen", "Kinderwagen", ["Kinderzimmer", "Wagenrad", "Briefkasten"]),
    ("Wasser", "Hahn", "Wasserhahn", ["Wasserglas", "Hühnerstall", "Badewanne"]),
    ("Hals", "Kette", "Halskette", ["Halstuch", "Fahrradkette", "Schlafanzug"]),
    ("Tee", "Kanne", "Teekanne", ["Teetasse", "Gießkanne", "Taschenlampe"]),
    ("Brief", "Kasten", "Briefkasten", ["Briefmarke", "Holzkasten", "Erdbeere"]),
    ("Bade", "Wanne", "Badewanne", ["Badetuch", "Regentonne", "Nachthemd"]),
    ("Schlaf", "Anzug", "Schlafanzug", ["Schlafsack", "Badeanzug", "Buchladen"]),
    ("Taschen", "Lampe", "Taschenlampe", ["Taschentuch", "Lampenschirm", "Spiegelei"]),
    ("Erd", "Beere", "Erdbeere", ["Erdboden", "Blaubeere", "Handschuh"]),
]


# Which of the four seats the right answer sits in, round by round.
#
# Written out rather than computed, because every short arithmetic rotation is
# one a child can learn instead of the words: `index % 4` walks the answer
# rightwards one seat per round, and *any* linear rule modulo four repeats with
# a period of four. This sequence uses each seat five times, never twice in a
# row, and in no order worth memorising.
SEATS = [1, 3, 0, 2, 3, 1, 2, 0, 2, 0, 1, 3, 2, 0, 3, 1, 3, 2, 0, 1]


def board_id(index: int) -> str:
    return f"runde-{index + 1:02d}"


def slug(word: str) -> str:
    """A file-safe stem. The words are German, so the umlauts have to go."""
    table = {"ä": "ae", "ö": "oe", "ü": "ue", "ß": "ss", "Ä": "Ae", "Ö": "Oe", "Ü": "Ue"}
    return "".join(table.get(c, c) for c in word).lower()


def record(word: str, voice: str, into: pathlib.Path) -> pathlib.Path:
    """One clip, as the 16 kHz mono PCM WAV SPEC.md 6 tolerates.

    The viewer plays recordings and nothing else — deliberately, so that a
    sentence never comes out half in the voice it was recorded in and half in
    the phone's. That makes the clips part of the package rather than a nicety,
    and a game generated without them is a game that makes no sound.
    """
    aiff = into / f"{slug(word)}.aiff"
    wav = into / f"{slug(word)}.wav"
    subprocess.run(["say", "-v", voice, "-o", str(aiff), word], check=True)
    subprocess.run(
        ["afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", str(aiff), str(wav)],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    aiff.unlink()
    return wav


def speaking_key(key_id: str, word: str, goes_to: str) -> dict:
    """A key that says its word and then turns to `goes_to`.

    SPEC.md 7.3's speak-on-navigate. `goes_to` being the key's own board is how
    a wrong answer is written: it speaks, and the navigation lands where it
    already was.
    """
    return {
        "id": key_id,
        "label": word,
        "vocalization": word,
        "sound_id": slug(word),
        "load_board": {"id": goes_to},
        "ext_lautstark_speak_on_navigate": True,
    }


def prompt_key(key_id: str, word: str) -> dict:
    """A word part at the top of the board. Says itself, turns nothing."""
    return {
        "id": key_id,
        "label": word,
        "vocalization": word,
        "sound_id": slug(word),
        "ext_lautstark_speak_immediately": True,
    }


def build(out: pathlib.Path, voice: str) -> None:
    words: set[str] = set()
    for part_a, part_b, correct, wrong in ROUNDS:
        words.update([part_a, part_b, correct, *wrong])
    words.update(["Los geht's", "Geschafft", "Noch einmal"])

    boards: dict[str, dict] = {}

    # The start board, and the first key that carries the flag: it says "Los
    # geht's" and opens round one, which is one press for what is otherwise two.
    boards["start"] = {
        "format": "open-board-0.1",
        "id": "start",
        "locale": "de",
        "name": "Zusammensetzspiel",
        "buttons": [speaking_key("los", "Los geht's", board_id(0))],
        "grid": {"rows": 1, "columns": 1, "order": [["los"]]},
    }

    for index, (part_a, part_b, correct, wrong) in enumerate(ROUNDS):
        here = board_id(index)
        # The last round leads to the closing board rather than to a round that
        # does not exist.
        onward = board_id(index + 1) if index + 1 < len(ROUNDS) else "geschafft"

        # Where the right answer sits. A child who has spotted that position 1
        # is always right has learned the board and not the words.
        correct_seat = SEATS[index]
        answers = list(wrong)
        answers.insert(correct_seat, correct)

        buttons = [prompt_key("teil-a", part_a), prompt_key("teil-b", part_b)]
        row = []
        for seat, word in enumerate(answers):
            key_id = f"antwort-{seat + 1}"
            # The right key turns the page; a wrong key navigates to the board it
            # is standing on, which speaks and stays put. See the module comment
            # for why the wrong keys navigate at all.
            buttons.append(speaking_key(key_id, word, onward if word == correct else here))
            row.append(key_id)

        boards[here] = {
            "format": "open-board-0.1",
            "id": here,
            "locale": "de",
            "name": f"Runde {index + 1}",
            "buttons": buttons,
            # Four columns: the two parts sit centred above the four answers.
            # OBF has no cell spanning, so the "split tile" the game is drawn
            # from is two tiles side by side.
            "grid": {
                "rows": 2,
                "columns": 4,
                "order": [[None, "teil-a", "teil-b", None], row],
            },
        }

    # "Noch einmal" navigates by `load_board` and not by `:home`, though start
    # *is* the root board and `:home` would land in the same place. SPEC.md 7.3
    # narrows speak-on-navigate to `load_board` and says it MUST be ignored
    # beside `:home`, so the `:home` spelling of this key would turn the page in
    # silence. Naming the board is what lets the key say the words.
    boards["geschafft"] = {
        "format": "open-board-0.1",
        "id": "geschafft",
        "locale": "de",
        "name": "Geschafft",
        "buttons": [
            prompt_key("lob", "Geschafft"),
            speaking_key("nochmal", "Noch einmal", "start"),
        ],
        "grid": {"rows": 1, "columns": 2, "order": [["lob", "nochmal"]]},
    }

    manifest = {
        "format": "open-board-0.1",
        "root": "boards/start.obf",
        "paths": {
            "boards": {b: f"boards/{b}.obf" for b in boards},
            "images": {},
            "sounds": {slug(w): f"sounds/{slug(w)}.wav" for w in sorted(words)},
        },
        "ext_lautstark_spec_version": SPEC_VERSION,
        "ext_lautstark_package_id": PACKAGE_ID,
        "ext_lautstark_package_name": "Zusammensetzspiel",
        "ext_lautstark_modified": "2026-08-31T12:00:00Z",
        "ext_lautstark_symbol_source": "none",
        "ext_lautstark_redistributable": True,
        "ext_lautstark_tts_voice": "de_DE-thorsten-medium",
        # A quiz wants a press to count on release rather than on touch, so that
        # a finger that lands on the wrong key can slide off it. 0 is off; these
        # are the author's default and the viewer's own setting still wins.
        "ext_lautstark_hold_time_ms": 0,
        "ext_lautstark_release_time_ms": 300,
    }

    staging = pathlib.Path(tempfile.mkdtemp(prefix="zusammensetzspiel-"))
    try:
        clips = staging / "clips"
        clips.mkdir()
        print(f"Recording {len(words)} clips in the voice {voice} …", file=sys.stderr)
        # `say` costs about thirteen seconds of start-up per invocation on this
        # machine whatever the word is, so a hundred clips one after another is
        # twenty minutes of almost entirely waiting. They are independent files.
        ordered = sorted(words)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            paths = list(pool.map(lambda w: record(w, voice, clips), ordered))
        recorded = dict(zip(ordered, paths))

        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as package:
            package.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
            for name, board in boards.items():
                package.writestr(
                    f"boards/{name}.obf", json.dumps(board, ensure_ascii=False, indent=2)
                )
            for word, path in recorded.items():
                package.write(path, f"sounds/{slug(word)}.wav")
    finally:
        shutil.rmtree(staging, ignore_errors=True)

    print(
        f"Wrote {out} — {len(boards)} boards, {len(ROUNDS)} rounds, {len(words)} clips.",
        file=sys.stderr,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=pathlib.Path, default=pathlib.Path("zusammensetzspiel.obz"))
    parser.add_argument(
        "--voice",
        default="Anna",
        help="a macOS de_DE voice; `say -v '?'` lists them",
    )
    args = parser.parse_args()
    build(args.out, args.voice)


if __name__ == "__main__":
    main()
