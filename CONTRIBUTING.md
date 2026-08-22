# Contributing to AnaAlarm

## Remotes (Origin primary, GitHub CI mirror)

**Cursor Origin** is the source of truth. **GitHub** is the CI and signing
mirror: GitHub Actions runs Host checks, API 26/36 emulator suites, and the
gated `com.anaalarm.internal` APK. Secrets never live in Origin or in this
repository.

| Remote | URL | Role |
|---|---|---|
| `origin` | `https://origin.cursor.com/acidburn/AnaAlarm.git` | Source of truth |
| `github` | `https://github.com/burnigtm/AnaAlarm.git` | Actions + signing mirror |

Repo page: https://cursor.com/codebase/acidburn/AnaAlarm

Authenticate Origin from **WSL** (the CLI is not supported on native Windows):

```bash
export PATH="$HOME/.local/bin:$PATH"
origin auth login
origin auth status
origin repo list
```

`git clone` of the Origin URL also needs that WSL credential helper. After
`origin auth login`, clone with:

```bash
origin repo clone acidburn/AnaAlarm
# or: git clone https://origin.cursor.com/acidburn/AnaAlarm.git
```

Then add the CI mirror if it is missing:

```bash
git remote add github https://github.com/burnigtm/AnaAlarm.git
```

### Windows (this project’s usual host)

The Origin CLI is **not supported on native Windows**. Use **WSL**:

1. `wsl`
2. `curl -fsSL https://downloads.cursor.com/origin/install.sh | sh` (skip if already installed at `~/.local/bin/origin`)
3. Put `~/.local/bin` on PATH, then `origin auth login` (browser) and
   `origin repo list`.
4. Use git from WSL for `git fetch`/`git push` to `origin.cursor.com`. Windows
   git can edit the same working tree but will not have the Origin credential
   helper. Do not install Origin with a homemade Windows binary.

GitHub `gh` remains valid **only** for the CI mirror (action SHA pins, the
`internal-distribution` environment, artifacts).

## How Origin work still hits GitHub gates

GitHub Actions does not run on `origin.cursor.com` pushes. Until Origin has an
equivalent pipeline, every commit that should be gated must also reach GitHub:

```powershell
.\scripts\push-ci-mirror.ps1
```

That pushes `HEAD` to the `github` remote (create it if missing). Open PRs
against the GitHub mirror for required checks (Host, API 26, API 36). Do **not**
mark `Installable APK` as a required PR check; it is supposed to skip on PRs.
Signing secrets stay in the GitHub Environment `internal-distribution`.

Do not weaken `--dependency-verification=strict`, SHA-pinned actions, or the
34 + 173 zero-skip device gates.

## Local development

Windows: JDK 17+, Android SDK 36, `.\gradlew.bat testDebugUnitTest`.
On-device: `.\scripts\run-instrumented-tests.ps1`.
Installable-APK rehearsal scripts under `scripts/ci/` are bash (Git Bash or WSL).

Play Store publishing is **out of scope** until an owner revives
[docs/PLAY_RELEASE_CHECKLIST.md](docs/PLAY_RELEASE_CHECKLIST.md).
