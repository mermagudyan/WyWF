# Pull Request

## Description

<!-- Brief description of what this PR changes and why. Link any related issues. -->

Fixes #<issue-number>
Related to #<issue-number>

## Type of change

<!-- Check all that apply -->

- [ ] 🐛 Bug fix (non-breaking change which fixes an issue)
- [ ] ✨ New feature (non-breaking change which adds functionality)
- [ ] 💥 Breaking change (fix or feature that would cause existing
      functionality to not work as expected)
- [ ] 📚 Documentation update
- [ ] 🌐 Dictionary / synonym addition
- [ ] ⚡ Performance improvement
- [ ] 🧪 Test addition / fix
- [ ] 🔧 Build / CI / tooling
- [ ] ♻️ Refactor (no functional change)

## Scope

<!-- Where in the codebase does this PR make changes? -->

- [ ] `core/` — domain model
- [ ] `search/` — seed search
- [ ] `world/` — world creation
- [ ] `client/` — GUI
- [ ] `mixin/` — Minecraft mixins
- [ ] Dictionary / synonyms
- [ ] Documentation
- [ ] Build / CI
- [ ] Other: ...

## Checklist

<!-- Please check all that apply. Some items may not be relevant to your PR. -->

### Code

- [ ] My code follows the [coding standards](CONTRIBUTING.md#coding-standards)
      (4-space indent, 120-char line limit, `final` by default).
- [ ] No new Minecraft imports added to the `core/` package.
- [ ] No `null` returns added (use `Optional` instead).
- [ ] No `synchronized` blocks added in the search hot loop.
- [ ] No new allocations introduced in `SearchWorker.run()` per-seed path.
- [ ] New public API is documented with Javadoc.
- [ ] Existing public API changes are reflected in the README.

### Dictionary changes (if applicable)

- [ ] New synonyms do not partially overlap with existing longer synonyms.
- [ ] New entries are added via `register()` or `registerSynonym()`.
- [ ] `rebuildIndex()` is called after batch changes.
- [ ] Tests are added in `KeywordDictionaryTest`.

### Tests

- [ ] I have added tests for my changes.
- [ ] All existing tests pass: `./gradlew test`.
- [ ] I have manually tested the change in Minecraft: `./gradlew runClient`.

### Build

- [ ] The project builds cleanly: `./gradlew build`.
- [ ] The lint check passes: `./gradlew check`.
- [ ] No new compiler warnings introduced.

### Git hygiene

- [ ] My commits follow [Conventional Commits](CONTRIBUTING.md#commit-messages).
- [ ] My branch is up to date with `main`.
- [ ] I have squashed or reorganized commits logically.

### Documentation

- [ ] I have updated the README if necessary.
- [ ] I have updated the CHANGELOG if necessary.
- [ ] I have added Javadoc to new public types and methods.

### Backward compatibility

- [ ] This PR does not break existing user queries.
- [ ] If breaking, I have bumped the major version in `gradle.properties`.
- [ ] If breaking, I have documented the migration path.

---

## Test plan

<!-- How did you verify this change works? Be specific. -->

1. Ran `./gradlew build` — passes
2. Ran `./gradlew test` — all tests pass
3. Launched Minecraft 26.x via `./gradlew runClient`
4. Tested the following queries in the Seed field:

   ```
   <query 1>
   <query 2>
   <query 3>
   ```

   Expected: ...
   Actual: ...

5. Tested edge cases:

   - Empty Seed field
   - Numeric seed (e.g., `12345`)
   - Random string (e.g., `abc123`)
   - Long mixed query (e.g., `please spawn me near a village by the warm ocean thanks`)
   - Query with only unknown words (e.g., `qwerty asdf`)

## Screenshots / Recordings

<!-- If your PR changes the GUI or any user-visible behavior, attach a
     screenshot or short recording. -->

## Performance impact

<!-- If your PR touches the search loop or the dictionary, briefly describe
     the performance impact. -->

- [ ] No measurable performance impact.
- [ ] Performance improved (briefly describe how):
- [ ] Performance may have changed — I have run a benchmark:

  ```
  Before: ... seeds/sec
  After:  ... seeds/sec
  ```

## Migration notes

<!-- If this is a breaking change, describe how users should migrate. -->

## Additional notes

<!-- Anything else maintainers should know. -->

---

## Reviewer notes

<!-- Tips for the reviewer — what to focus on, what to skip. -->

**Priority areas:**

1.
2.

**Skip:**

1.
2.

---

Thank you for your contribution! 🎉

By submitting this pull request, you agree to:
- License your contribution under the [Apache License 2.0](LICENSE).
- Abide by the [Code of Conduct](CODE_OF_CONDUCT.md).
