# GitHub Repository Setup Checklist

This file documents the repository setup state. Each item corresponds to a
GitHub feature that should be configured on the repo settings page
(`https://github.com/mermagudyan/WyWF/settings`).

## ✅ Done — Files in this repo

| Item                              | File                                              | Status |
|-----------------------------------|---------------------------------------------------|--------|
| Description                       | `.github/DESCRIPTION.txt`                         | ✅     |
| README                            | `README.md`                                       | ✅     |
| Code of conduct                   | `CODE_OF_CONDUCT.md`                              | ✅     |
| Contributing                      | `CONTRIBUTING.md`                                 | ✅     |
| License                           | `LICENSE`                                         | ✅     |
| Security policy                   | `SECURITY.md`                                     | ✅     |
| Issue templates                   | `.github/ISSUE_TEMPLATE/bug_report.md`            | ✅     |
| Issue templates                   | `.github/ISSUE_TEMPLATE/feature_request.md`       | ✅     |
| Issue templates                   | `.github/ISSUE_TEMPLATE/dictionary_contribution.md` | ✅   |
| Issue template config             | `.github/ISSUE_TEMPLATE/config.yml`               | ✅     |
| Pull request template             | `.github/PULL_REQUEST_TEMPLATE.md`                | ✅     |

## ⚙️ To configure on GitHub (manual steps)

After pushing this repo to GitHub, complete the following on the settings page:

### 1. About section (right sidebar)

- [ ] Click the gear icon next to "About" on the repo homepage.
- [ ] **Description:** paste from `.github/DESCRIPTION.txt` (long version).
- [ ] **Website:** `https://github.com/mermagudyan/WyWF` (or your project page).
- [ ] **Topics:** add `minecraft`, `fabric`, `fabric-mod`, `minecraft-mod`,
      `seed-finder`, `natural-language`, `java`, `apache-license`,
      `contributions-welcome`, `good-first-issue`.
- [ ] Check "Releases" to display the latest release in the sidebar.

### 2. General settings → Features

- [ ] ✅ Issues
- [ ] ✅ Discussions (for community Q&A — see `config.yml` contact link)
- [ ] ✅ Projects (if you want a kanban board)
- [ ] ✅ Wiki (optional)
- [ ] ✅ Sponsorships (optional — only if you accept donations)
- [ ] ❌ Preserve this repository (only for archived repos)

### 3. General settings → Pull Requests

- [ ] ✅ Allow merge commits
- [ ] ✅ Allow squash merging (recommended default — use Conventional Commits)
- [ ] ❌ Allow rebase merging (optional, disable if you prefer squash)
- [ ] ✅ Always suggest updating pull request branches
- [ ] ✅ Automatically delete head branches

### 4. General settings → Archives

- [ ] ✅ Allow forking (or disable if you want a single-source repo)

### 5. Branches → Branch protection rules

Protect `main`:

- [ ] ✅ Require a pull request before merging
  - [ ] Required approvals: **1** (or 2 for high-traffic repos)
  - [ ] ✅ Dismiss stale pull request approvals when new commits are pushed
  - [ ] ✅ Require review from Code Owners
- [ ] ✅ Require status checks to pass
  - [ ] Require branches to be up to date before merging
  - [ ] Required checks: `build`, `test` (configure in CI workflow)
- [ ] ✅ Require conversation resolution before merging
- [ ] ✅ Require signed commits (recommended)
- [ ] ✅ Require linear history (if squash-merge only)
- [ ] ❌ Include administrators (optional — usually you want admins to bypass)

### 6. Rules → Rulesets

Optional — modern alternative to branch protection:

- [ ] Create a ruleset "main-protected" targeting `main`
- [ ] Require pull request
- [ ] Require status checks
- [ ] Restrict who can push to matching branches

### 7. Tags → Releases

- [ ] Create `v1.0.0` tag on `main` after first stable build.
- [ ] Mark as "Latest release".
- [ ] Attach `wywtf-1.0.0.jar` and `wywtf-1.0.0-sources.jar` as assets.
- [ ] Write release notes (use Conventional Commits auto-generated changelog
      if available).

### 8. Tags and releases → Auto-generated release notes

- [ ] Configure release notes template at
      `.github/release.yml` (categories: feat, fix, perf, docs, etc.).

### 9. Actions → General

- [ ] ✅ Allow all actions and reusable workflows (or restrict to verified)
- [ ] ✅ Allow GitHub Actions to create and approve pull requests
- [ ] Workflow permissions: **Read and write** (or read-only + bot token)
- [ ] ✅ Allow actions to approve pull requests

### 10. Secrets and variables → Actions

Add the following repository secrets (used in CI):

- [ ] `MODRINTH_TOKEN` — for publishing to Modrinth
- [ ] `CURSEFORGE_TOKEN` — for publishing to CurseForge
- [ ] `GPG_SIGNING_KEY` — for signing release commits/tags (optional)

### 11. Pages

If you want a project page:

- [ ] Source: GitHub Actions
- [ ] Add a workflow that builds Javadoc / MkDocs and deploys to Pages
- [ ] Custom domain (optional)

### 12. Security → Code security and analysis

- [ ] ✅ Enable Dependabot security updates
- [ ] ✅ Enable Dependabot version updates (add `.github/dependabot.yml`)
- [ ] ✅ Enable code scanning (CodeQL) — add `.github/workflows/codeql.yml`
- [ ] ✅ Enable secret scanning
- [ ] ✅ Enable pushed protection for secrets

### 13. Security → Security advisories

- [ ] ✅ Enable private security advisories
- [ ] Test the "Report a vulnerability" flow (matches `SECURITY.md`)

### 14. Collaborators and teams

- [ ] Add maintainers as collaborators with "Admin" or "Maintain" role.
- [ ] Add `CODEOWNERS` file at `.github/CODEOWNERS`:

  ```
  *       @maintainer1 @maintainer2
  /src/main/java/com/wywtf/core/    @maintainer1
  /src/main/java/com/wywtf/mixin/   @maintainer2
  ```

### 15. Discussion categories

Configure these discussion categories (in order):

1. **📢 Announcements** — read-only, for maintainers
2. **💬 General** — open discussion
3. **💡 Ideas** — feature requests that aren't ready for an issue
4. **🙋 Q&A** — questions and answers
5. **🙏 Show and tell** — user-created seed collections, screenshots

### 16. Issues → Templates (preview)

- [ ] Go to `Issues → New issue` and verify all 3 templates render correctly:
  - Bug Report
  - Feature Request
  - Dictionary Contribution
- [ ] Verify the "config.yml" contact links show up at the bottom of the
      template chooser.

### 17. Milestones

Create milestones for upcoming releases:

- [ ] `v1.0.1` — bug fixes
- [ ] `v1.1.0` — JSON dictionary support, more languages
- [ ] `v1.2.0` — config screen, history of found seeds
- [ ] `v2.0.0` — public API for other mods

### 18. Labels

Add the following labels (or import from `.github/labels.yml`):

**Type:**
- `bug` — #d73a4a
- `enhancement` — #a2eeef
- `documentation` — #0075ca
- `question` — #d876e3

**Priority:**
- `priority:critical` — #b60205
- `priority:high` — #d93f0b
- `priority:medium` — #fbca04
- `priority:low` — #0e8a16

**Status:**
- `triage` — #fbca04
- `in-progress` — #1d76db
- `blocked` — #b60205
- `wontfix` — #ffffff

**Scope:**
- `core` — #c5def5
- `search` — #c5def5
- `world` — #c5def5
- `client` — #c5def5
- `mixin` — #c5def5
- `dictionary` — #d4c5f9
- `ci` — #e99695

**Special:**
- `good first issue` — #7057ff
- `help wanted` — #008672
- `security` — #d73a4a
- `breaking` — #b60205

### 19. Social image

- [ ] Create `assets/social-preview.png` (1280×640) showing the mod name
      and tagline.
- [ ] Upload it via Settings → Social image. This will be shown in social
      media link previews.

### 20. Repository visibility

- [ ] Public (recommended for an open-source mod)
- [ ] If private initially: set up everything first, then make public.

---

## Workflow files to add (future)

After this initial setup, add the following CI/CD workflow files:

- `.github/workflows/build.yml` — build on push and PR
- `.github/workflows/test.yml` — run unit tests
- `.github/workflows/codeql.yml` — security scanning
- `.github/workflows/release.yml` — publish on tag push
- `.github/dependabot.yml` — dependency updates
- `.github/release.yml` — release notes categories
- `.github/CODEOWNERS` — code ownership

These are not included in this archive — they require maintainer-specific
secrets and decisions (which CI runners, which publish targets, etc.).
