# CI and installable APK builds

**Decision:** Cursor Origin is the git source of truth. GitHub Actions
(`.github/workflows/android.yml`) remains the only quality and signing runner until
Origin has equivalent gates. Secrets stay on GitHub. Do not weaken the existing
Host / API 26 / API 36 / installable-APK checks.

AnaAlarm uses one GitHub Actions workflow on the **CI mirror**
`github.com/burnigtm/AnaAlarm`. Actions do not run for commits that exist only on
Origin (`https://origin.cursor.com/acidburn/AnaAlarm.git`). After pushing to
Origin, also push the same commit to GitHub:

```powershell
.\scripts\push-ci-mirror.ps1
```

See [CONTRIBUTING.md](../CONTRIBUTING.md) for remotes and the Windows/WSL Origin
runbook. Play Store / `bundleRelease` is out of scope
([PLAY_RELEASE_CHECKLIST.md](PLAY_RELEASE_CHECKLIST.md)).

The workflow expands into four visible checks. Three are quality gates; the fourth
creates a directly installable APK only after every quality gate passes on a trusted
`main` build.

```text
Host checks ---------+
API 26 device tests -+-- all green on trusted main --> owner approval --> Installable APK
API 36 device tests -+
```

The `device` entry in the YAML is one matrix job definition, but GitHub expands it into separate
API 26 and API 36 checks.

## When each check runs

| Event | Host | API 26 | API 36 | Installable APK |
|---|---:|---:|---:|---:|
| Pull request | Yes | Yes | Yes | No |
| Push to `main` | Yes | Yes | Yes | After the other three pass and an owner approves |
| Manual dispatch on `main` | Yes | Yes | Yes | After the other three pass and an owner approves |
| Manual dispatch on another ref | Yes | Yes | Yes | No |

The APK check intentionally does not run on pull requests. PR code, including code from forks,
must never receive the internal signing key. Do not configure `Installable APK` as a required PR
check because its correct PR result is `skipped`.

The workflow also has read-only repository permissions, fixed `ubuntu-24.04` runners, immutable
full-SHA action pins, Gradle wrapper validation, strict dependency checksums and locks, and
same-ref concurrency cancellation. If a newer commit reaches the same ref, GitHub may cancel the
older run; only the newest completed `main` run is expected to publish an APK.

`main` is protected for administrators and other writers: Host, API 26, and API 36 must pass on an
up-to-date branch, review conversations must be resolved, and force-pushes/deletion are disabled.
`Installable APK` is excluded from required PR checks because it intentionally skips PR events.

## What the four checks prove

### 1. Host checks

This is the fast, device-free gate. It:

1. Runs all debug JVM unit tests.
2. Compiles the Android instrumented-test source set.
3. Builds the debug APK and minified release APK.
4. Builds the unsigned internal variant, signs it with a disposable one-day key, then runs the
   same signature, certificate, manifest, minification, and checksum verifier used for the stable
   artifact. This tests the distribution tooling on pull requests without exposing a real key.
5. Runs Android Lint for debug, release, and internal.
6. On trusted `main`, uploads the unsigned APK and R8 mapping as a seven-day intermediate
   artifact for the later signer job.
7. Uploads Gradle test, lint, and related host reports as `android-host-reports`, even on failure.

It catches Kotlin/Java behavior regressions, Android-test compilation errors, release/R8 failures,
lint findings, and dependency-integrity failures. It does not prove real Android runtime behavior.

### 2. Device tests (API 26)

This check boots the minimum-supported Android version on a Google APIs emulator. It:

1. Builds and installs the debug app and test APK.
2. Grants and reads back the applicable required capabilities.
3. Runs the required alarm, boot, firing, and notification group. The verdict must be exactly
   34 tests with zero skipped/assumption tests.
4. Clears both app packages and restores capabilities so the aggregate phase starts clean.
5. Runs the complete instrumented suite. The verdict must be exactly 173 tests with zero skips.
6. Uploads instrumentation output, logcat, activity state, and Android test reports as
   `android-device-api-26`.

The exact counts make an accidentally filtered or assumption-skipped suite fail closed instead of
reporting a misleading green result.

### 3. Device tests (API 36)

This check repeats the same 34-test and 173-test, zero-skip gates on the current target SDK. It
also verifies modern exact-alarm and full-screen-intent app-ops. After the instrumented suite it:

1. Builds the minified production-shaped release variant.
2. Creates a one-day, ephemeral CI key under runner temporary storage.
3. Aligns, signs, and verifies that release APK.
4. Installs it, cold-launches `MainActivity`, and checks that the process stays alive.
5. Builds the exact internal variant, signs it with a separate disposable key, verifies it is
   aligned/non-debuggable, installs `com.anaalarm.internal`, cold-launches its `MainActivity`, and
   checks that its process stays alive.
6. Deletes the temporary keys and signed APKs on exit.

Its diagnostics are uploaded as `android-device-api-36`. This ephemeral smoke key is deliberately
unrelated to both the stable internal-distribution key and any future Play key.

### 4. Installable APK

This is a gated distribution job, not another test suite. It waits for Host, API 26, and API 36,
then runs only for a push or manual dispatch on `main`. The normal
`internal-distribution` environment path requires explicit approval from repository owner
`burnigtm` before the job starts. Repository administrators can explicitly bypass that checkpoint
under the environment's current GitHub setting; such bypasses should be exceptional and audited.

The job:

1. Calculates a deterministic Android version code as
   `workflow run number * 100 + run attempt` (with attempts limited to 1-99). New workflow run
   numbers sort after older runs, and attempts sort within one run.
2. Has Host build and rehearse an unsigned, minified `internal` variant without signing secrets.
3. After every gate passes and approval is granted, starts a fresh runner, checks out the approved
   commit, and downloads the validated unsigned APK and R8 mapping. No Gradle or build task runs
   there; the reviewed signing helper and certificate pin execute from that approved commit.
4. In one secret-scoped shell step, reconstructs the PKCS12 keystore in runner temporary storage,
   aligns and signs the APK, and removes the keystore on exit.
5. Passes passwords to `apksigner` through environment variables rather than command arguments.
6. Verifies the APK signature and both configured/version-controlled certificate fingerprints.
7. Uses `aapt` to require package `com.anaalarm.internal`, label `AnaAlarm Internal`, the expected
   version code/name, no debuggable marker, final ZIP alignment, and an R8 mapping file proving
   minification occurred.
8. Uploads only the verified APK, checksum, and build metadata as
   `anaalarm-installable-<run>-<attempt>` for 30 days.

The internal application ID allows this build to coexist with a production `com.anaalarm` app.
The internal label makes it clear which copy is being opened. No DeepSeek API key or user data is
embedded in the APK.

Version ordering depends on preserving this workflow's GitHub identity. Before deleting,
recreating, or moving the workflow file, record the highest published version code and change the
formula to a higher base; otherwise a reset run counter can produce APKs Android treats as
downgrades.

## Download and install an APK

These artifacts exist only on **GitHub Actions** (the CI mirror), not on Origin.

1. Open the GitHub mirrorâ€™s **Actions** tab (`github.com/burnigtm/AnaAlarm`).
2. Select **Android quality** and open the latest successful run on `main`.
3. In **Artifacts**, download `anaalarm-installable-<run>-<attempt>`.
4. Extract the downloaded ZIP. GitHub artifacts are ZIP archives; the APK inside is the file to
   install.
5. Either open the APK on the device and allow **Install unknown apps** for that file source, or
   connect the device with USB debugging and run:

   ```bash
   adb install -r AnaAlarm-internal-<run>.<attempt>-<commit>.apk
   ```

On first installation the app requests the normal notification and microphone runtime permissions
where applicable. Exact-alarm and full-screen access use warning cards that take the user to the
relevant Android settings screen; they are not ordinary runtime prompts. Follow the
[README first-run steps](../README.md#10-first-run-and-configuration). Configure your own DeepSeek
key in the app if you want AI conversation; CI never includes one.

To update, install an artifact from a newer workflow run with `adb install -r` or open it on the
device. The stable internal signer and run-ordered version code allow Android to update the
existing internal app while retaining its data. Rerunning an older workflow after a newer one does
not turn the older source revision into an upgrade; its version code remains lower. An older code
normally cannot replace a newer one. Uninstalling `com.anaalarm.internal` enables a rollback but
deletes that package's alarms, settings, API key, and local conversation history.

## Verify the download

Every artifact contains:

- `AnaAlarm-internal-<run>.<attempt>-<commit>.apk`
- the matching `.apk.sha256` file
- `build-info.txt` with the package, label, version, commit, certificate fingerprint, build type,
  and minification status

On macOS or Linux, run this from the extracted directory:

```bash
sha256sum -c AnaAlarm-internal-*.apk.sha256
```

On PowerShell:

```powershell
$apk = Get-ChildItem .\AnaAlarm-internal-*.apk
$expected = (Get-Content "$($apk.FullName).sha256").Split()[0].ToLowerInvariant()
$actual = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "APK checksum mismatch" }
```

The adjacent checksum detects download corruption or a mismatched file. Because it is delivered
in the same artifact, it is not independent proof of origin. The job additionally pins and checks
the signing-certificate fingerprint before GitHub accepts the artifact upload.

The independent trust anchor is
[`.github/internal-distribution-certificate.sha256`](../.github/internal-distribution-certificate.sha256),
which is reviewed with source changes rather than delivered inside the artifact. Compare it with:

```bash
apksigner verify --print-certs AnaAlarm-internal-*.apk
```

The `Signer #1 certificate SHA-256 digest` must exactly equal the 64-character digest in that
file. The workflow also requires the GitHub Environment variable and this version-controlled pin
to match before it signs or uploads anything.

## Signing boundary and repository configuration

The job uses the GitHub Environment named `internal-distribution` on the **CI mirror**,
restricted to the `main` branch. Origin has no copy of these secrets. It needs these
environment secrets:

| Name | Purpose |
|---|---|
| `ANAALARM_INTERNAL_KEYSTORE_BASE64` | Base64-encoded dedicated internal PKCS12 keystore |
| `ANAALARM_INTERNAL_STORE_PASSWORD` | PKCS12 store password |
| `ANAALARM_INTERNAL_KEY_PASSWORD` | Private-key password; for the documented `keytool` PKCS12 flow, set it to the store password |

It also needs one non-secret environment variable:

| Name | Purpose |
|---|---|
| `ANAALARM_INTERNAL_CERT_SHA256` | Expected SHA-256 fingerprint for the internal signing certificate |

The same fingerprint is pinned in
`.github/internal-distribution-certificate.sha256`. Rotation must update the GitHub variable and
the reviewed repository pin together.

The fixed key alias is `anaalarm-internal`. Secret values exist only on the signing step; they are
not job-wide, are not available to setup/upload actions, and are not available to PR checks. The
checkout also disables persisted Git credentials in the signing job. The unsigned build occurs
on Host; the approved signer starts on a fresh runner and never executes Gradle.

This key is exclusively for package `com.anaalarm.internal`. Never reuse a Play app-signing key,
Play upload key, production sideload key, developer debug key, or another project's key. The
installable artifact is a convenience build for testers; it is not a Play candidate, an AAB, or
proof that the Play release checklist passed.

GitHub does not allow secret values to be retrieved after saving them. When provisioning a key,
keep an encrypted offline backup if preserving the internal app's long-term update chain matters.
Never commit that backup, its base64 form, or its passwords.

The environment's `main` deployment policy restricts which ref can request the key, and its
required reviewer creates a manual checkpoint after the three quality gates. Repository branch
protection separately requires those checks, an up-to-date branch, resolved conversations, and
blocks admin bypass, force-pushes, and deletion. These controls still depend on careful write and
reviewer access: approved code that reaches `main` can run in the environment.

### Provision or replace the environment key

Use this runbook from a trusted administrator machine. Do not perform it from a pull-request
checkout or a shared shell. The `keytool` command below creates a PKCS12 whose key password is the
store password, so give `ANAALARM_INTERNAL_KEY_PASSWORD` the same value as
`ANAALARM_INTERNAL_STORE_PASSWORD`.

1. Create/update the environment with a required reviewer (replace `OWNER/REPO`). This example
   uses the authenticated owner and permits self-approval for a single-maintainer repository; use
   a separate trusted reviewer when one is available:

   ```powershell
   $reviewerId = [int64](gh api user --jq .id)
   $body = @{
       wait_timer = 0
       reviewers = @(@{ type = "User"; id = $reviewerId })
       prevent_self_review = $false
       deployment_branch_policy = @{
           protected_branches = $false
           custom_branch_policies = $true
       }
   } | ConvertTo-Json -Depth 5 -Compress
   $body | gh api --method PUT `
       repos/OWNER/REPO/environments/internal-distribution --input -
   ```

   Then restrict deployments to `main`:

   ```bash
   gh api --method POST \
     repos/OWNER/REPO/environments/internal-distribution/deployment-branch-policies \
     -f name=main -f type=branch
   ```

   The second command is needed only when the `main` policy does not already exist.

2. Generate a dedicated PKCS12 key. Omitting password arguments makes `keytool` prompt instead of
   placing passwords in shell history:

   ```bash
   keytool -genkeypair -keystore anaalarm-internal.p12 -storetype PKCS12 \
     -alias anaalarm-internal -keyalg RSA -keysize 3072 -sigalg SHA384withRSA \
     -validity 10000 \
     -dname 'CN=AnaAlarm Internal Distribution,OU=Internal,O=AnaAlarm'
   keytool -exportcert -keystore anaalarm-internal.p12 \
     -alias anaalarm-internal -file anaalarm-internal.der
   ```

3. Before uploading anything, copy the password-protected PKCS12 to encrypted offline storage and
   save its password in an approved password manager. Test that the backup can be opened. GitHub
   cannot return the secret later.
4. Calculate the public certificate fingerprint:

   ```powershell
   $fingerprint = (Get-FileHash .\anaalarm-internal.der -Algorithm SHA256).Hash.ToLowerInvariant()
   $fingerprint
   ```

5. Set the secrets on the **GitHub CI mirror** (not Origin). `gh secret set` prompts securely
   when no value flag or pipe is supplied. Replace `OWNER/REPO` with `burnigtm/AnaAlarm`:

   ```bash
   base64 < anaalarm-internal.p12 | tr -d '\n' | \
     gh secret set ANAALARM_INTERNAL_KEYSTORE_BASE64 \
       --env internal-distribution --repo OWNER/REPO
   gh secret set ANAALARM_INTERNAL_STORE_PASSWORD \
     --env internal-distribution --repo OWNER/REPO
   gh secret set ANAALARM_INTERNAL_KEY_PASSWORD \
     --env internal-distribution --repo OWNER/REPO
   ```

6. Set `ANAALARM_INTERNAL_CERT_SHA256` to the lowercase fingerprint and update
   `.github/internal-distribution-certificate.sha256` to the identical value in the same reviewed
   change:

   ```bash
   gh variable set ANAALARM_INTERNAL_CERT_SHA256 \
     --env internal-distribution --repo OWNER/REPO --body FINGERPRINT
   ```

7. Exercise the secret-free local rehearsal before pushing:

   ```bash
   ANAALARM_INTERNAL_VERSION_CODE=100 \
   ANAALARM_INTERNAL_VERSION_SUFFIX=ci \
     bash scripts/ci/build-internal-apk.sh
   bash scripts/ci/test-installable-apk.sh
   ```

8. Delete only the working copies after the encrypted backup is verified. Then manually dispatch
   **Android quality** on `main`, approve the `internal-distribution` deployment after reviewing
   its commit, and compare the artifact certificate with the repository pin.

## Key rotation or loss

Android accepts an update only when the package ID and signing identity match the installed app.
Replacing or losing the internal key therefore breaks in-place updates.

To rotate:

1. Generate a new dedicated internal key and record an encrypted offline backup.
2. Replace all three `internal-distribution` secrets together.
3. Replace `ANAALARM_INTERNAL_CERT_SHA256` and
   `.github/internal-distribution-certificate.sha256` with the new certificate fingerprint.
4. Run the workflow manually on `main` and verify its APK, checksum, and `build-info.txt`.
5. Tell testers to uninstall `com.anaalarm.internal`, then install and configure the new build.
6. If compromise is suspected, delete still-downloadable affected artifacts and record the
   incident and rotation date.

The official internal variant uses a different application ID, but the production signing
identity is the decisive protection: even an attacker who chooses package ID `com.anaalarm`
cannot update an installed Play-signed app without its production signer. A compromised internal
key can still impersonate the internal package, so rotate it promptly.

## Retention and limitations

- GitHub retains each installable artifact for 30 days. Use the newest successful `main` run.
- The unsigned intermediate is retained for seven days, so approve the signer within that window.
  If it expires first, rerun the workflow on `main` to regenerate the input. It is not installable
  and should not be distributed.
- Direct installs do not receive Play-managed automatic updates.
- Emulator checks do not prove physical speaker volume, microphone/TTS quality, OEM background
  restrictions, overnight timing, battery behavior, or real lock-screen behavior.
- The API 36 ephemeral signed-release smoke and the stable installable artifact serve different
  purposes and intentionally use different keys.
- Final Play evidence still requires the physical-device matrix and production-signed candidate
  in [PLAY_RELEASE_CHECKLIST.md](PLAY_RELEASE_CHECKLIST.md).

See [TESTING.md](TESTING.md) for the full automated and manual test plan and
[SUPPLY_CHAIN.md](SUPPLY_CHAIN.md) for dependency/action maintenance controls.

---

## Issue #9 acceptance-criteria evidence matrix

Every acceptance bullet from
[issue #9](https://github.com/burnigtm/AnaAlarm/issues/9) mapped to its implementation and
proof, so the issue can be audited (and was closed by PR #11's program).

| Acceptance criterion | Evidence |
| --- | --- |
| Minified `internal` variant, `com.anaalarm.internal`, label *AnaAlarm Internal* | `app/build.gradle.kts` (`create("internal")`, `applicationIdSuffix`, `resValue("string", "internal_app_name", ...)`) |
| Gradle and pull-request jobs free of reusable signing secrets | Signing secrets appear only in the main-only `installable` job under the `internal-distribution` environment (`.github/workflows/android.yml`); Host/PR jobs receive none |
| Rehearse unsigned input in Host; sign on a fresh runner that executes no Gradle | Host: "Rehearse installable APK packaging" + "Stage unsigned signer input"; `installable` job contains no `gradlew` step — only checkout, JDK, artifact download, signing script |
| Distribution only after Host, API 26, API 36 pass on trusted main with approved environment | `installable:` `needs: [host, device]` + `if: github.ref == 'refs/heads/main' ...` + `environment: internal-distribution` |
| Dedicated stable internal key scoped to the main-only environment | `ANAALARM_INTERNAL_KEYSTORE_BASE64` et al. referenced exclusively in that job/environment; pin file `.github/internal-distribution-certificate.sha256` version-controlled |
| Verify signature, certificate pin, package, label, version, mapping, non-debuggable, alignment, checksum | `scripts/ci/sign-installable-apk.sh`: cert-pin comparison, `apksigner verify --print-certs` digest match, single-signer check, `aapt dump badging` package/versionCode/versionName/label assertions, debuggable rejection, `zipalign -c -P 16 4`, `sha256sum` sidecar + `build-info.txt` |
| Workflow-run-ordered CI version code; 30-day final retention | `version_code = GITHUB_RUN_NUMBER * 100 + GITHUB_RUN_ATTEMPT`; upload step `retention-days: 30` |
| PR rehearsal with disposable key; install + cold-launch on API 36 | `scripts/ci/test-installable-apk.sh` generates an ephemeral 1-day key, rewrites its own pin file, runs the identical verifier; API-36 device job builds the internal APK and performs `adb install` + cold-launch + liveness smoke (`run-device-ci.sh`) |
| Document four checks, download/install/update, checksum/signer verification, key provisioning/rotation, retention, limitations | This document: "When each check runs", "What the four checks prove", "Download and install an APK", "Verify the download", "Signing boundary…", "Provision or replace the environment key", "Key rotation or loss", "Retention and limitations" |

Residual owner action (repository Settings, not code): protect `main` with the three quality
checks as required status checks, require resolved conversations, and block force pushes and
deletions.
