# Checking the LAN receiver on real hardware

`PackageReceiver` and its screen are unit-tested, and the transport underneath
them was measured separately in `Lautstark/design` (`docs/mocks/README.md`).
Neither of those touches a tablet. This file is the part that needs a tablet, a
laptop, a home network and somebody's hands.

It is a checklist rather than a report, because it wants running again — on a
new device, after an Android upgrade, or whenever the sending half changes.
What one run found is at the bottom.

Everything here is a place where the piece-wise proofs genuinely do not reach.
None of it is a smoke test, and the order is by how likely each is to be wrong.

## 1. Does the port come up on this tablet at all

The one thing no test in this repository covers, deliberately:
`PackageReceiverTest` binds port 0 so that whether the suite passes never
depends on what happens to be listening on the machine running it. On a tablet
the real port is the real question.

**Sammlung hinzufügen → Vom Rechner empfangen.**

- Expect a large address and „Wartet auf ein Paket."
- **„Dieses Tablet kann gerade nichts annehmen."** with no address means the
  bind failed and reported itself, which is the path working. Find out what
  holds 8765 on that device.
- The failure that matters is neither of those: **an address on screen that
  nothing answers.** If the editor gets no response while the tablet is showing
  a number, the bind reporting is lying — and that is the worst outcome
  available here, because it sends somebody to re-check a number that was
  already right.

## 2. Is that the address the laptop can reach

`LanAddress` prefers a `wlan*` interface and otherwise takes the first private
IPv4. A tablet in a dock, on a VPN, or with a second interface up could show an
address the laptop cannot reach.

Read the tablet's own address independently — `adb shell ip -f inet addr show
wlan0`, or Settings → wifi — and compare. Reading it *before* opening the screen
is better: then the two sources are genuinely independent rather than one of
them being the thing under test.

If they differ, the interface ordering is wrong on that device. Record both.

## 3. A real Sammlung, at a real size

The transport was measured at 3 MB and 48 MB against a throwaway receiver.
Nothing has put a real package through the real importer into `PackageStore` on
tablet storage. Use the largest real Sammlung available — 30 MB or more.

- While it arrives: the pulsing dot is replaced by a plate reading **„Ein Paket
  wird empfangen …"** with the size beneath. There is **no progress bar**, and
  that is the design rather than a gap — see `design.md` §4.3.
- When it lands: the screen returns to the list by itself and the outcome line
  names it.
- **Time the gap between the sender saying done and the tablet changing
  screens.** `ImportViewModel.receive` runs on the connection's own thread, so
  the sender is held open through the whole parse and the disk write. If that
  gap is long enough to look broken to a person, the fix is on the sending side
  — it would have to say something during it — not here.

## 4. Send the same package twice

Exercises `AlreadyCurrent`, which is the one outcome word that is easy to get
backwards.

- Second send ends „ist nicht neuer …" and changes nothing.
- Then send a genuinely newer build of the same Sammlung: „… ersetzt", and the
  board must still open afterwards.

## 5. The socket really does close

Most of the argument in `AndroidManifest.xml` rests on this, so it is worth
seeing rather than trusting.

- On the receive screen, press Home. Send from the laptop: it must **fail
  outright** — no response at all, not a refusal.
- Return to the app: listening again, without leaving and re-entering the
  screen.
- Leave with Zurück and send again: must fail.

## 6. Refusals arrive as codes

Rename any `.zip` and send it.

- The tablet's list shows a refusal line.
- The editor shows **its own German sentence with a bare code beside it** —
  never an English sentence fragment. `reason` is a closed set
  (`RejectionCode.wireName`, or one of `PackageReceiver.Codes`); `detail` is
  prose and is for a log.

## 7. Reduced motion

Settings → Accessibility → **Remove animations** on, then reopen the screen.

The dot must be **steady and still visible** — not blinking, and not gone. An
infinite Compose transition never sees `MotionDurationScale`, so this is an
explicit check in the code and can regress silently.

## Not worth testing, and why

- **The Local Network Access prompt.** That is the sender's browser asking the
  sender. If Chrome asks, the answer is Allow.
- **The editor on the tablet.** Samsung Internet as a *sender* is a measured
  dead end — it enforces the LNA checks with no permission UI and refuses in
  450 ms. The tablet is only ever the receiver, so this does not apply to it,
  but testing that way would look like a bug in this code.

## What one run found

**2026-08-28, SM-X130 on Android 16, laptop and tablet on the same wifi.**

Installing over the release-signed v0.6.0 needed a clean uninstall first — a
downgrade block, then a signature mismatch — which loses the Sammlungen on the
device. Worth knowing before starting rather than during.

- **1 — passed.** „Wartet auf ein Paket." and `192.168.0.36`. The two-way sheet
  drew as designed, with no second primary button.
- **2 — passed, independently.** `ip -f inet addr show wlan0` read `192.168.0.36`
  before the app displayed anything; the app then showed the same. The laptop at
  `192.168.0.197` pinged 3/3, average 119 ms — high for a LAN, and worth
  allowing for when reading the timing in item 3, because it is the wifi rather
  than this code.
- Noted in passing: the dimming earns its keep. On a `192.168.0.x` network the
  third octet is not the Fritzbox `178`, so two numbers have to be copied — and
  both of the ones that move are the two drawn at full strength.
