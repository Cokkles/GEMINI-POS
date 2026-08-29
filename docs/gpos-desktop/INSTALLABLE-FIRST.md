# GPOS Desktop — Installable-First Policy

Packaging is part of the product from Phase D0, not a final release chore.

Every meaningful Windows checkpoint should produce, when technically feasible, a directly testable self-contained artifact that does not require Visual Studio, the .NET SDK, or routine PowerShell environment configuration on the tester machine.

## Checkpoint expectations

A test checkpoint records:

- version
- commit SHA
- branch
- artifact/package type
- artifact location
- install steps
- upgrade steps
- rollback/uninstall steps
- authentication impact
- backend impact
- compatibility notes
- known issues
- validation performed

## Required lifecycle

The normal tester loop is:

1. obtain artifact
2. install
3. launch GPOS Desktop
4. test
5. install next version over it
6. retain appropriate protected auth/config/preferences
7. uninstall cleanly when desired

## State rules

Application binaries are replaceable. DPAPI-protected auth/config and safe user preferences must have explicit upgrade/uninstall retention semantics. No installer or test package may contain OAuth client secrets, downloaded credential JSON, access tokens, ID tokens, refresh tokens, Gemini/API keys, or other server secrets.

## Existing foundation

The current Helper already provides self-contained Windows publishing, package verification, smoke testing, a per-user install path, Desktop/Start Menu shortcuts, and a windowless launcher. D0 evolves this path rather than replacing it prematurely.

## CI target

The Windows branch should gain a GitHub Actions build/checkpoint workflow that restores, tests and publishes the self-contained Windows package as an artifact. Installer technology can mature after the product-shell lifecycle is stable; CI artifact generation should not wait for a final installer choice.