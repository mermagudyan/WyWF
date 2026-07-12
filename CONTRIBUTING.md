# Contributing to WyWF

First off, thanks for taking the time to contribute! ❤️

This document describes how to contribute to **What You Want To Find (WYWTF)**.
Following these guidelines helps maintainers and the community understand your
contribution and review it faster.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [How Can I Contribute?](#how-can-i-contribute)
  - [Reporting Bugs](#reporting-bugs)
  - [Suggesting Enhancements](#suggesting-enhancements)
  - [Pull Requests](#pull-requests)
  - [Improving the Dictionary](#improving-the-dictionary)
- [Development Setup](#development-setup)
- [Coding Standards](#coding-standards)
- [Commit Messages](#commit-messages)
- [Branching](#branching)
- [Testing](#testing)
- [Releases](#releases)

---

## Code of Conduct

By participating in this project, you agree to abide by the
[Code of Conduct](CODE_OF_CONDUCT.md). Please read it. Be kind. Be patient.

---

## Getting Started

WYWTF is a Fabric mod for Minecraft 26.x (tested on 26.2), written in Java 25,
built with Gradle. Make sure you have the following installed before you start:

| Tool | Minimum Version |
|------|-----------------|
| JDK  | 25              |
| Gradle | 8.10+         |
| Git  | 2.30+           |

Fork the repository, then:

```bash
git clone https://github.com/mermagudyan/WyWF.git
cd WyWF
./gradlew build
```

If the build succeeds, you're ready to start hacking.

---

## How Can I Contribute?

### Reporting Bugs

Bugs are tracked as [GitHub issues](https://github.com/mermagudyan/WyWF/issues).
Before opening a new one:

1. **Search existing issues** — your bug may already be reported.
2. **Check the latest version** — the bug may have been fixed in `main`.
3. **Collect information**:
   - WYWTF version
   - Minecraft version
   - Fabric Loader version
   - Fabric API version
   - Java version
   - Operating system
   - Logs (latest.log and crash reports, if any)
   - The exact query you typed in the Seed field
   - Expected vs. actual behavior
4. Use the **Bug Report** issue template.

### Suggesting Enhancements

Enhancement suggestions are also tracked as
[GitHub issues](https://github.com/mermagudyan/WyWF/issues). Before opening one:

1. **Search existing issues** — your idea may already be under discussion.
2. **Use the Feature Request template**.
3. Be specific:
   - What problem does this solve?
   - What does the user-facing experience look like?
   - Any alternatives you've considered?

Good enhancement suggestions are concrete and focused on user value, not on
implementation details. Implementation is the maintainers' job.

### Pull Requests

1. Fork the repo and create your branch from `main`.
2. If you've added code that should be tested, add tests.
3. If you've changed APIs, update the documentation.
4. Make sure the build passes: `./gradlew build`.
5. Make sure your code lints clean: `./gradlew check`.
6. Issue that pull request! Use the **Pull Request template**.

**Small PRs are welcome.** Prefer 5 small PRs over 1 giant one.

### Improving the Dictionary

WYWTF is a dictionary-driven mod. The easiest and most impactful way to
contribute is to extend the synonym dictionary in
`src/main/java/com/wywf/core/KeywordDictionary.java`:

- Add a new synonym in another language
- Add a missing colloquial term (e.g., "stash" for "village")
- Register a modded biome or structure
- Add a brand-new keyword category

When contributing dictionary changes:

1. Add the entry via `register()` / `registerSynonym()`.
2. Call `rebuildIndex()` after batch changes.
3. Add a test case in `QueryParserTest`.
4. Document the new entry in the README.

---

## Development Setup

The recommended IDE is **IntelliJ IDEA** (Community or Ultimate):

1. `File → Open` the cloned repository.
2. Wait for Gradle import to finish.
3. Run `./gradlew genSources` to download Minecraft sources for reference.
4. Run `./gradlew runClient` to launch a Minecraft client with the mod loaded.

For VS Code users, install the **Extension Pack for Java** and
**Fabric for Java** extensions.

---

## Coding Standards

- **Java 25 features are welcome** — records, sealed types, pattern matching,
  virtual threads (with care).
- **Final by default.** Mark classes, fields, and parameters `final` unless
  you have a reason not to.
- **No `null` returns** unless the API explicitly allows it. Prefer `Optional`.
- **Prefer immutability.** `ParsedQuery`, `SearchResult`, etc. are immutable
  by design — keep it that way.
- **Interfaces over concrete classes** in the `search/` and `world/` packages.
  This is what allows modded biome/structure support.
- **No Minecraft imports in `core/`.** The `core/` package must compile
  without any Minecraft dependency.
- **Atomic counters in `SearchProgress`** — never add a `synchronized` block
  in the hot search loop.
- **Single allocation per seed.** The only allocation per seed is the
  `WorldContext`. Don't introduce `new ArrayList<>()` inside `SearchWorker.run()`.
- **4-space indentation. No tabs.**
- **UTF-8 file encoding.**
- **Line length: 120 chars** (soft limit).

---

## Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body>

<footer>
```

**Types:**

| Type     | Use for                                       |
|----------|-----------------------------------------------|
| `feat`   | New feature                                   |
| `fix`    | Bug fix                                       |
| `docs`   | Documentation only                            |
| `style`  | Formatting, no code change                    |
| `refactor` | Code change that neither fixes a bug nor adds a feature |
| `perf`   | Performance improvement                       |
| `test`   | Adding or correcting tests                    |
| `chore`  | Build, tooling, dependencies                  |
| `ci`     | CI configuration                              |

**Examples:**

```
feat(parser): support Chinese synonyms
fix(search): race condition in SearchWorker when seed limit reached
docs(readme): add German query examples
perf(biome-checker): skip empty biomes early
test(parser): add bilingual query mix tests
```

**Scope** is one of: `parser`, `dictionary`, `search`, `world`, `client`,
`mixin`, `build`, `docs`, `ci`.

---

## Branching

- `main` — always buildable, always releasable.
- `feature/<short-description>` — for new features.
- `fix/<short-description>` — for bug fixes.
- `release/<version>` — for release prep (rare).

Branch off `main`, open PRs back to `main`.

---

## Testing

Tests live in `src/test/java/`. Run them with:

```bash
./gradlew test
```

What to test:

- `QueryParser` — every synonym in the dictionary should parse correctly.
- `KeywordDictionary` — `matchAt()` returns the longest match.
- `SearchConfig` — thread count formula matches the spec.
- `SearchProgress` — atomic counters behave correctly under concurrency.

What NOT to test (yet — requires Minecraft runtime):

- `VanillaBiomeChecker` / `VanillaStructureChecker` — these need a real
  `BiomeSource` instance. Integration tests are a future task.

---

## Releases

Releases follow [Semantic Versioning](https://semver.org/):

```
MAJOR.MINOR.PATCH
```

- `MAJOR` — breaking changes (new Minecraft version that requires
  recompilation, mixin renames, removed API).
- `MINOR` — new features (new dictionary categories, new search modes).
- `PATCH` — bug fixes.

The release process:

1. Update `gradle.properties` (`mod_version`).
2. Update `CHANGELOG.md` (if present) or the GitHub release notes.
3. Tag: `git tag v1.0.1 && git push --tags`.
4. CI builds the jar and creates a GitHub Release.
5. Attach the jar and sources jar to the release.
6. Publish to Modrinth / CurseForge (manual, optional).

---

## Questions?

- Open a [Discussion](https://github.com/mermagudyan/WyWF/discussions) for general questions.
- Open an [Issue](https://github.com/mermagudyan/WyWF/issues) for bugs and feature requests.
- Mention `@mermagudyan` in a PR if you need a review and nobody responds
  within 3 days.

Thanks again for contributing! 🎉
