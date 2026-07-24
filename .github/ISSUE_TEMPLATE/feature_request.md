---
name: Feature Request
about: Suggest a new feature or enhancement for WyWF
title: "[FEATURE] "
labels: ["enhancement", "triage"]
assignees: []
---

## Is your feature request related to a problem?

A clear and concise description of what the problem is.
For example: "I'm always frustrated when [...]"

## Proposed solution

A clear and concise description of what you want to happen.

## User-facing experience

How would a user interact with this feature? Walk through a typical use case:

1. User opens Create World screen
2. User types `...` in the Seed field
3. ...
4. Result: ...

## Alternatives considered

A clear and concise description of any alternative solutions or features
you've considered.

## Additional context

Add any other context or screenshots about the feature request here.

---

## Implementation details (optional)

If you have ideas about how this could be implemented, share them here.
This is optional — maintainers will decide on the implementation approach.

**Suggested area of code:**

- [ ] `core/` — domain model (KeywordDictionary, QueryParser, etc.)
- [ ] `search/` — seed search (SeedSearcher, BiomeChecker, etc.)
- [ ] `world/` — world creation (WorldCreator, SpawnAdjuster)
- [ ] `client/` — GUI (SearchScreen)
- [ ] `mixin/` — Minecraft mixins
- [ ] Dictionary / synonyms
- [ ] Configuration / settings
- [ ] Documentation
- [ ] Other: ...

**Backward compatibility:**

- [ ] This feature does not break existing behavior.
- [ ] This feature requires a major version bump.

---

**Before submitting, please:**

- [ ] I have searched existing issues and this feature is not already requested.
- [ ] This feature fits the project's scope (seed search via natural language).
- [ ] I am not asking for a modded-biome-specific feature that should belong
      in a separate compatibility module.
