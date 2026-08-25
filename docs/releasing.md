# Cutting a release

A release is a git tag. Pushing `vMAJOR.MINOR.PATCH` builds a signed APK and
attaches it to a GitHub Release; nothing else publishes anything, and there is no
button to press.

```bash
git tag v0.2.0
git push origin v0.2.0
```

## What the tag sets off

The tag push runs [`ci.yml`](../.github/workflows/ci.yml) like any other push, so
the parser suite, the Android build and the format check all run first. The
`release` job `needs:` all three. That is the whole of the "if green" part: a
failure anywhere leaves the tag with no release rather than with an untested
binary, and the fix is to delete the tag, land the fix, and tag again.

When they pass, the release job:

1. derives the version from the tag,
2. writes the keystore out of repository secrets,
3. runs `:app:assembleRelease`,
4. **verifies the APK is actually signed**, and only then
5. creates the release with the APK and its SHA-256 attached.

Step 4 is not ceremony. A release build with no credentials does not fail — it
produces an unsigned APK, which looks like a successful build right up until a
device refuses to install it. `apksigner verify` asks the artifact rather than the
build, so a missing secret, a wrong alias and a corrupt keystore all surface in
the same place, before anything is published. The certificate is printed into the
log, so the record of which key a given release went out under is the run itself.

## The version comes from the tag

The tag is the only place a version is written down.

| Tag | `versionName` | `versionCode` |
|---|---|---|
| `v0.2.0` | `0.2.0` | `200` |
| `v1.0.0` | `1.0.0` | `10000` |
| `v1.4.11` | `1.4.11` | `10411` |

`versionCode` is packed as `major * 10000 + minor * 100 + patch`, so it rises with
the version and is never a second number to remember. It holds while minor and
patch stay under 100; `0.100.0` would collide with `1.0.0` and is the point at
which the packing needs revisiting.

`app/build.gradle.kts` keeps `0.1.0` and `1` as fallbacks. Those are what an
untagged local build gets and are not the released version — nothing needs
bumping in the file when cutting a release.

A tag that is not `vMAJOR.MINOR.PATCH` fails the job with a message saying so
rather than guessing a version. There is no pre-release channel: if one is wanted,
it is a `--prerelease` flag on the `gh release create` call and a looser tag
pattern, both deliberate additions.

## The key must never change

An Android app's identity is its signing key. A device accepts an update only
from the key that installed the app, and refuses anything else outright rather
than warning — so a release signed with a different key does not degrade, it
strands everyone who already has the app on an install they cannot update and can
only uninstall and replace, losing its data. On a tablet a child depends on, that
is not a small thing.

There is no recovery. Play App Signing can re-issue a lost upload key; this
project has no store listing and does not use it, so the keystore is the key.
Signature scheme v3 can rotate a key, but rotation must be signed by the old key,
so it retires a key and never replaces a lost one — and `minSdk` is 26 while
rotation is honoured only from 28.

So the fingerprint is pinned in [`gradle.properties`](../gradle.properties), and
the release job checks the APK against it before publishing:

```properties
release.certificateSha256=
```

Nothing about a wrong-key build looks wrong — it compiles, it signs, it verifies.
The pin is the only thing that can tell it from a right one. While it is empty the
release fails and prints the digest of whatever it just signed, so the first
release says what to paste here; it does not skip, for the reason
[`docs/exchange-pin.md`](exchange-pin.md) gives about the other pin in this file.

Read it off the keystore with:

```bash
keytool -list -v -keystore vorlaut-release.jks -alias vorlaut | grep SHA256
```

Paste it in either form — `keytool` prints uppercase with colons and `apksigner`
prints lowercase without, and the check normalises both rather than failing on
formatting. The fingerprint is not a secret: it is printed in the log of every
release by design, which is what makes the run a record of the key it went out
under.

Changing this value is changing which key ships. It is a deliberate act with the
consequences above attached, never a fix for a failing release.

## The signing key

Release signing is configured entirely from outside the tree. Four values, all or
none — three of four is a typo, and the build stops rather than quietly producing
an unsigned APK:

| Secret | Environment variable | What it is |
|---|---|---|
| `RELEASE_KEYSTORE_BASE64` | (decoded to `RELEASE_KEYSTORE`) | the `.jks`, base64-encoded |
| `RELEASE_KEYSTORE_PASSWORD` | `RELEASE_KEYSTORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | `RELEASE_KEY_ALIAS` | the key's alias inside the store |
| `RELEASE_KEY_PASSWORD` | `RELEASE_KEY_PASSWORD` | the key password |

### Creating the keystore

Once, on a machine that is not CI:

```bash
keytool -genkeypair -v -keystore vorlaut-release.jks -alias vorlaut \
  -keyalg RSA -keysize 4096 -validity 10000
```

**Back it up before doing anything else with it**, into a password manager or
another durable store that is not this machine: the `.jks` file itself as an
attachment, and the store password, key password and alias beside it. The file is
the part that cannot be regenerated — a password without it is worth nothing. See
above for what losing it costs.

Keep it outside the repository, somewhere like `~/keys`. `.gitignore` refuses
`*.jks`, `*.keystore` and `keystore.properties`, but that is a backstop against a
mistake, not a reason to keep a signing key in a directory that gets committed,
archived and copied.

### Putting it into GitHub

```bash
base64 -i vorlaut-release.jks | pbcopy
```

Paste that as the `RELEASE_KEYSTORE_BASE64` repository secret under
**Settings → Secrets and variables → Actions**, and add the other three beside it.
Secrets are write-only once set: they can be replaced but not read back, so keep
the local copy.

### Building a signed APK locally

The same four values, as environment variables:

```bash
RELEASE_KEYSTORE=$HOME/keys/vorlaut-release.jks \
RELEASE_KEYSTORE_PASSWORD=... \
RELEASE_KEY_ALIAS=vorlaut \
RELEASE_KEY_PASSWORD=... \
./gradlew :app:assembleRelease -Prelease.versionName=0.2.0 -Prelease.versionCode=200
```

Gradle properties work too — `release.keystore`, `release.keystorePassword`,
`release.keyAlias`, `release.keyPassword` — but only in `~/.gradle/gradle.properties`.
Putting them in this repository's `gradle.properties` commits them.

With none of them set, `./gradlew build` behaves exactly as it always has: the
release APK comes out unsigned and no signing config is created at all. It is not
signed with the debug key, because a release artifact that installs anywhere is
the thing worth being unable to produce by accident.

## Re-running a release

`gh release create` fails if a release already exists for the tag, and that
failure is deliberate — a published release is not overwritten by a re-run. To
redo one, delete the release and its tag first, then push the tag again.

## The APK is sideloaded

There is no store listing. Whoever installs this fetches an APK from a release
page and turns off a warning to install it, so the SHA-256 goes up beside the APK:
it is the only way for them to tell that what they downloaded is what the run
built.

```bash
shasum -a 256 -c vorlaut-0.2.0.apk.sha256
```
