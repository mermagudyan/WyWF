# Security Policy

## Supported Versions

WYWTF is a Minecraft **26.x** client mod (tested on 26.2). Security updates are
provided for the latest released version only.

| Minecraft | Version  | Supported          | Notes                       |
|-----------|----------|--------------------|-----------------------------|
| 26.x      | 1.0.x    | :white_check_mark: | Tested on 26.x              |
| other     | any      | :x:                | Build for 26.x only         |

If you are running an unsupported version, please update to the latest
release before reporting any security issue.

---

## Reporting a Vulnerability

**DO NOT open a public GitHub issue for security vulnerabilities.**

If you discover a security vulnerability in WYWTF, please report it privately:

1. **Preferred:** Use GitHub's private vulnerability reporting:
   https://github.com/mermagudyan/WyWF/security/advisories/new
   (`Security` tab → `Report a vulnerability`).
2. **Alternative:** Open a private security discussion on the repository:
   https://github.com/mermagudyan/WyWF/discussions

Please include the following in your report:

- A description of the vulnerability and its impact
- The affected version(s)
- Steps to reproduce (proof of concept if possible)
- Affected file(s) or class(es), if known
- Suggested fix, if you have one

### Response Timeline

| Step                          | Target Time     |
|-------------------------------|-----------------|
| Acknowledge receipt           | within 48 hours |
| Initial assessment            | within 7 days   |
| Fix or mitigation plan        | within 30 days  |
| Public disclosure (with credit) | after fix is released, or 90 days from report — whichever comes first |

You will receive updates at each step. If you don't hear back within the
target time, please follow up.

---

## Scope

### In Scope

- Vulnerabilities that allow arbitrary code execution through the mod
- Crashes or denial-of-service caused by malformed queries that bypass the
  parser and reach reflection / unsafe paths
- Mixin-related bugs that could cause world corruption
- Seed injection leading to unintended world generation behavior
- Unsafe deserialization (if any is introduced in the future)

### Out of Scope

- Bugs in Minecraft itself (report to Mojang via bugs.mojang.com)
- Bugs in Fabric Loader or Fabric API (report to their respective repos)
- Bugs in third-party mods that interact with WYWTF
- Performance issues that are not security-relevant
- "The mod uses a lot of CPU" — that's the search feature doing its job
- Social engineering or phishing attempts against maintainers

---

## Security Best Practices for Users

WYWTF is a client-side mod. To stay safe:

1. **Download only from official sources:**
   - GitHub Releases
   - Modrinth
   - CurseForge

2. **Verify the file hash** if one is published in the release notes.

3. **Do not run WYWTF on a server.** The mod is marked
   `"environment": "client"` and is not designed for server-side use.

4. **Do not paste arbitrary text into the Seed field** if you don't trust
   your dictionary source. WYWTF only recognizes keywords from its built-in
   dictionary — unknown words are ignored — but keep this in mind when
   extending the dictionary from third-party JSON files.

5. **Keep your Minecraft and Fabric Loader updated.** WYWTF relies on
   Minecraft's internal APIs; using an outdated Minecraft version with a
   newer WYWTF build may cause mixin failures.

---

## Security Best Practices for Contributors

When contributing to WYWTF, follow these rules:

- **No `Runtime.exec()`.** Ever.
- **No `Method.invoke()` on user-controlled class names.** Reflection is
  allowed only for fixed, hard-coded class names (e.g., Mixin accessors).
- **No network calls.** The mod is fully offline.
- **No file system writes** outside of the standard Minecraft config
  directory.
- **All user input** (the Seed field text) must pass through `QueryParser`
  before reaching any internal API. Never feed raw user input to reflection,
  file paths, or class loaders.
- **Sanitize log output.** Don't log full queries if they contain private
  information (e.g., a user typing their real name).

---

## Acknowledgments

We thank security researchers who responsibly disclose vulnerabilities.
Reporters will be credited in the release notes (unless they prefer to
remain anonymous).
