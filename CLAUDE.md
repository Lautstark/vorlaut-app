# Working in this repository

Several agents work here at once. The five rules below exist because on
2026-08-25 this repository did not have them, and in one afternoon one agent
managed to clobber another's committed fixture, land a third's unfinished work
on `main`, and lose track of which branch it was standing on — none of it
maliciously and all of it avoidably.

`Lautstark/vorlaut-diy-talker` has carried the first two rules for longer and
does not have these problems. This file is that convention arriving here, where
it should have been from the first commit.

## 1. Take a worktree, named after your branch

```bash
git worktree add -b claude/<task> .claude/worktrees/<task> main
```

A worktree lives at `.claude/worktrees/<branch without the `claude/` prefix>`.
No generated names, so that `git worktree list` is the whole dashboard.

**The rule this replaces is "just check out a branch in the main directory",
and that is what went wrong.** Two agents in one working tree means the branch
under your feet changes without your doing anything: a `git stash push` at one
commit and a `git stash pop` after somebody else's merge wrote *their* newer
fixture back to *your* older copy of it, and a test nobody had touched went red.
The main checkout at `~/Code/vorlaut-app` belongs to whoever is passing through;
do not assume it is still on the branch you left it on, and do not leave your
work in it.

## 2. Say who you are, first

Before the first edit, not afterwards:

```bash
git config branch.$(git branch --show-current).description "Agent A - what you are doing"
```

Read them all back with:

```bash
git config --get-regexp 'branch\..*\.description'
```

A branch called `claude/first-column-gap` with three uncommitted files in it
tells the next agent nothing about whether it is safe to touch. A description
takes one command and answers that.

## 3. Read what you are about to merge

```bash
git log --oneline main..$(git branch --show-current)
```

**Always, before every merge.** A branch is not necessarily only yours: if you
created `claude/foo` and somebody else also reached for that name, their commits
are now on it, and `git merge --no-ff claude/foo` lands work that was not yours
to land. That is exactly how seven commits of another agent's unfinished editor
design arrived on `main` — from one merge run without looking.

And never this:

```bash
git checkout -b claude/foo || git checkout claude/foo   # NO
```

The fallback silently turns "make me a branch" into "join whatever exists under
that name". Pick a name nobody else would pick, and let the command fail if it
is taken.

## 4. Repo-wide edits belong on `main`, in their own session

A rename, a formatting sweep, a dependency bump, a licence header. A feature
branch never carries one; ask for it on `main` and wait, rather than clearing
the ground before your real task.

The exception is small and stated: if `spotlessCheck` is already failing on
`main` for a file you did not write, fixing the wrap is fair game — say so in
the commit message, because a formatting-only change to someone else's file
looks like a merge accident otherwise.

## 5. Land your own finished work

Trunk-based. No pull requests — this repository has never merged through one.

```bash
git push -u origin "$(git branch --show-current)"
```

CI runs on **every branch push** (`.github/workflows/ci.yml` triggers on `push`
with no branch filter, deliberately: a workflow that only fires on `main` gives
you its verdict after the merge, which is too late to be a verdict). Wait for
green, then:

```bash
git log --oneline main..$(git branch --show-current)   # rule 3, every time
git -C ~/Code/vorlaut-app status -sb                   # must say main, and be clean
git -C ~/Code/vorlaut-app merge --no-ff "$(git branch --show-current)"
git -C ~/Code/vorlaut-app push origin main
```

`--no-ff` always, so a branch stays visible as a unit. If the main checkout is
on somebody else's branch or is dirty, **wait** — it is usually free again
within the hour, and the alternative is standing on their work.

Then remove the worktree and delete the branch, so rule 1's dashboard stays
true.

## What the build needs

There is no system JDK on the development machine. Android Studio's bundled
runtime is the one that works:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

`./gradlew build` runs everything CI runs — unit tests, Android lint with
`warningsAsErrors`, and `spotlessCheck`. Run it before you push, not after.

## The exchange fixtures are fetched, never copied

`:boardpackage` is checked against the conformance fixtures in
`Lautstark/vorlaut-diy-talker`, pinned by commit SHA in `gradle.properties` and
fetched at build time. **Do not copy them in.** A copy stops tracking the spec
the moment either side changes and then passes forever, which is worse than
having no fixtures because it looks like coverage. See
[`docs/exchange-pin.md`](docs/exchange-pin.md), which also covers what to do
when the pin is unset and why the build fails loudly rather than skipping.

`boardpackage/src/test/resources/builder/` is the one exception and is not a
fixture: those are real builder output, kept so the two programs can be shown to
meet. Its README says why that does not break the rule.

## The design comes from somewhere

Colours, type and spacing are a port of `Lautstark/design`, in
`app/src/main/kotlin/de/lautstark/vorlaut/app/design/`. The generator there is
the source of every hex; editing one here to taste silently drops the contrast
guarantee it was solved for. Before writing a new component, look in that
repository's `docs/components.css` — the button tiers, the notice, the empty
state, the sheet and the footer already exist, and a fourth product drawing its
own lookalikes is the drift that file was written to stop.
