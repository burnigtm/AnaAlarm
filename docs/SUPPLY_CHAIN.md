# Build supply-chain controls

AnaAlarm treats workflow code and downloaded build artifacts as release inputs. CI uses four
complementary controls:

1. Every GitHub Action is referenced by a full immutable commit SHA. A nearby comment records the
   reviewed release tag.
2. Jobs use the explicit `ubuntu-24.04` runner family instead of the moving `ubuntu-latest` alias.
3. The Gradle wrapper distribution is checked by `distributionSha256Sum`, and CI validates the
   wrapper before running it.
4. Gradle resolves with strict dependency locks and SHA-256 verification metadata.

The runner image and Android SDK packages can still receive upstream servicing. Pinned setup
actions, the locked Maven graph, checksums, reports, and the API-level matrix make that remaining
environment drift visible rather than silently changing application dependencies.

## Updating a Gradle dependency

Change the requested version, then deliberately regenerate both controls from a trusted network.
First, the maintenance task resolves every project dependency graph without invoking compilers and
updates the lock state:

```bash
./gradlew resolveAndLockAll --write-locks
```

Then resolve the real CI task inputs so Gradle records the JAR/AAR artifacts as well as their
metadata:

```bash
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin \
  assembleDebug assembleRelease lintDebug lintRelease \
  --write-verification-metadata sha256 --no-parallel
./gradlew assembleInternal lintInternal \
  --write-verification-metadata sha256 --no-parallel
```

On Windows, replace `./gradlew` with `.\gradlew.bat`.

Review `app/gradle.lockfile` and `gradle/verification-metadata.xml`. Confirm every new component and
checksum belongs to the dependency you intended to introduce. Do not accept a checksum simply
because Gradle downloaded it; compare suspicious artifacts with the publisher's release
information or repository.

Verification metadata is artifact- and platform-specific. After an Android Gradle Plugin update,
search the metadata for OS classifiers such as `-windows`, `-linux`, and `-osx`. Fetch every exact
classifier supported by the project's Windows, Linux CI, and macOS development environments from
Google Maven, verify its SHA-256 independently, and add the reviewed hashes together. Generating
metadata on only one workstation records only that workstation's AAPT2 binary and will make strict
verification fail on the other platforms.

Then run the complete gate without write flags:

```bash
./gradlew testDebugUnitTest compileDebugAndroidTestKotlin \
  assembleDebug assembleRelease lintDebug lintRelease \
  --dependency-verification=strict --no-parallel
./gradlew assembleInternal lintInternal \
  --dependency-verification=strict --no-parallel
```

Commit dependency declarations, locks, and verification metadata together. A CI failure saying a
configuration is not locked or an artifact checksum is missing is an expected fail-closed signal,
not a reason to weaken strict mode.

## Updating a workflow action

Start from the action's primary upstream repository and review the proposed release notes and
diff. Resolve the release tag to a commit with GitHub CLI; annotated tags require dereferencing the
returned tag object until the object type is `commit`:

```bash
gh api repos/OWNER/REPOSITORY/git/ref/tags/vX.Y.Z
gh api repos/OWNER/REPOSITORY/git/tags/TAG_OBJECT_SHA
gh api repos/OWNER/REPOSITORY/commits/COMMIT_SHA
```

Replace the 40-character SHA in `.github/workflows/android.yml` and update the adjacent tag
comment in the same change. Never replace it with a floating major tag such as `@v4`.

Current reviewed pins are:

| Action | Release | Commit |
|---|---:|---|
| `actions/checkout` | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| `actions/setup-java` | v5.7.0 | `b6effb05e454b25005698d916606bdc6ffcbf961` |
| `gradle/actions` | v6.2.0 | `3f131e8634966bd73d06cc69884922b02e6faf92` |
| `ReactiveCircus/android-emulator-runner` | v2.38.0 | `a421e43855164a8197daf9d8d40fe71c6996bb0d` |
| `actions/upload-artifact` | v7.0.1 | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` |
| `actions/download-artifact` | v8.0.1 | `3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c` |

## Ephemeral API 36 release-smoke signing

`scripts/ci/smoke-release.sh` generates its key inside `RUNNER_TEMP`, gives it one-day validity,
signs only the already-minified release APK, verifies the result with `apksigner`, and removes the
key and signed APK on exit. This proves installability and launch behavior without placing a
reusable signing secret or distributable smoke APK in the repository or workflow artifacts.

## Stable internal-distribution signing

The separate `Installable APK` job publishes `com.anaalarm.internal` only after all three quality
checks pass on trusted `main` and the environment approval gate is satisfied. Host builds and
rehearses the minified unsigned APK without secrets, then uploads it as short-lived intermediate
input. The signer starts on a fresh runner, downloads that input, and runs no Gradle or build task;
the reviewed signing helper executes from the approved commit. One shell step receives the
`internal-distribution` secrets, reconstructs the PKCS12 key in runner temporary storage, signs
through `apksigner` environment-password inputs, checks the configured and version-controlled
certificate fingerprints plus manifest identity, and deletes the key
before upload. The upload step receives none of the signing secrets; it still uses GitHub's normal
short-lived runtime token.

The internal key is reusable so Android can update an earlier internal installation, but it is
never a Play app-signing/upload key or a production-package key. Key loss or rotation requires
testers to uninstall the old internal package. Secret names, branch restrictions, artifact
contents, installation, retention, offline-backup guidance, and rotation procedure are in
[CI_AND_INSTALLABLE_BUILDS.md](CI_AND_INSTALLABLE_BUILDS.md).

Remote enforcement protects `main` for administrators and other writers: the Host/API 26/API 36
checks must pass on an up-to-date branch, review conversations must be resolved, and force-pushes
and branch deletion are disabled. The internal environment's normal path additionally requires
owner approval; its current GitHub setting permits an administrator to explicitly bypass that
checkpoint.

Primary references:
[Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html),
[Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html), and
[GitHub secure use of full-length action SHAs](https://docs.github.com/en/actions/reference/security/secure-use).
