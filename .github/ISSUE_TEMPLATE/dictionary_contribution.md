---
name: Dictionary Contribution
about: Suggest new keywords, synonyms, or translations for the WyWF dictionary
title: "[DICT] "
labels: ["dictionary", "enhancement"]
assignees: []
---

## What would you like to add?

Check all that apply:

- [ ] New biome keyword
- [ ] New structure keyword
- [ ] New object keyword
- [ ] New synonym for an existing keyword
- [ ] New language / localization
- [ ] Fix an incorrect synonym
- [ ] Other: ...

## Existing keyword (if applicable)

Which existing keyword does this contribution relate to?

```
minecraft: <existing canonical id>
```

## Proposed additions

List the new synonyms, keywords, or translations you'd like to add:

| Type        | Canonical ID              | Synonym(s)         | Language |
|-------------|---------------------------|--------------------|----------|
| biome       | minecraft:ocean           | sea, морето        | EN, BG   |
| structure   | minecraft:village         | Dorf               | DE       |
| ...         | ...                       | ...                | ...      |

## Reasoning

Why should these be added? Are they commonly used by players in your
language? Are they standard Minecraft terms in your locale?

## Verification

Have you tested that these synonyms don't conflict with existing ones?

- [ ] I checked `KeywordDictionary.java` and these synonyms are not already
      registered.
- [ ] These synonyms do not partially overlap with existing longer synonyms
      (e.g., adding "warm" alone would conflict with "warm ocean").
- [ ] These synonyms are unambiguous in the target language.

## Examples

Show 2-3 example queries that would work with the new synonyms:

```
<query 1>  →  should parse as: ...
<query 2>  →  should parse as: ...
<query 3>  →  should parse as: ...
```

---

**Before submitting, please:**

- [ ] I have searched existing issues and dictionary contributions.
- [ ] I have read the "Improving the Dictionary" section in CONTRIBUTING.md.
- [ ] I am not duplicating synonyms that already exist.
