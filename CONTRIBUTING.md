<!-- SPDX-License-Identifier: Apache-2.0 -->

# Contributing to Nova

Thank you for contributing to Nova. This document standardizes the **issue, PR, and commit-message** conventions and the contribution workflow. For code conventions and architecture boundaries, see [`AGENTS.md`](AGENTS.md).

---

## Workflow

1. **Open an issue first.** Whether it is a bug, a feature, or a doc change, agree on scope in an issue before starting work. Small typo / one-line fixes are exempt.
2. **Branch off `main`.** Keep each branch focused on a single change. Recommended branch name: `<type>/<short-slug>` — e.g. `feat/cursor-pagination`, `fix/schema-onetomany-leak`, `docs/contributing-guide`.
3. **Verify locally.** Run `./gradlew build` to confirm the full test suite passes. For tight feedback while iterating, run the narrow task first, e.g. `./gradlew :nova-project:nova-core:test`.
4. **Open a PR.** Use the [PR template](#pr-conventions) and link the related issue with `Closes #N`.
5. **Review and merge.** Merge after at least one approval. Prefer squash-merge to keep history tidy.

---

## Commit message conventions

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <subject>

<body, optional>

<footer, optional>
```

### Type (required)

| Type       | Use for                                                                |
|------------|------------------------------------------------------------------------|
| `feat`     | A new user-visible feature                                              |
| `fix`      | A bug fix                                                               |
| `docs`     | Docs only (README, `docs/`, javadoc)                                    |
| `refactor` | Internal cleanup with no behavior change                                |
| `test`     | Adding or modifying tests only                                          |
| `perf`     | Performance improvement                                                 |
| `build`    | Build system / dependencies (Gradle, dependency versions)               |
| `ci`       | CI pipeline (`.github/workflows/`)                                      |
| `chore`    | Miscellany that does not fit the above (version bump, gitignore, etc.) |
| `style`    | Code style / whitespace (no logic change)                               |

### Scope (optional, recommended)

Prefer the module name where it applies:

`core`, `r2dbc`, `dialect-postgresql`, `dialect-mysql`, `dialect-mariadb`, `dialect-h2`, `dialect-oracle`, `spring-boot`, `spring-data`, `metrics`, `docs`, `build`, `ci`, `schema`, `sql`, `tx`, `nova` (aggregate).

### Subject (required)

- Imperative mood, present tense (`add`, `fix`, `update` — not `added` / `adds`).
- Lowercase first letter, no trailing period.
- **50 characters** preferred, **72** absolute max.

### Breaking changes

Append `!` after the type and add a `BREAKING CHANGE:` footer.

```
feat(core)!: drop blocking convenience method block()

BREAKING CHANGE: ReactiveEntityOperations.block(...) has been removed.
Use .subscribe() or upstream Reactor operators. Migration guide: ...
```

### Examples

Good:

```
feat(dialect-h2): add @Json column mapping with text fallback
fix(schema): exclude @OneToMany inverse fields from createTable column list
docs(readme): split into focused docs/ pages and trim README
refactor(core): extract EntityListenerInvoker from SimpleReactiveEntityOperations
test(sql): cover pageable.totalElements with empty result set
build: bump default version to 1.0.2-SNAPSHOT
```

Avoid:

```
update README                          # no type, no scope, vague
fix bug                                # which bug?
feat: Added new feature for users.     # past tense + period + vague
chore: stuff                           # meaningless
WIP                                    # only acceptable if you squash before merge
```

### No AI co-authors

Do not add an AI agent as `Co-Authored-By:` (per AGENTS.md).

---

## Issue conventions

Choose one of the four templates defined under [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/).

### 1. Bug report

- **Environment** — Java version, Nova version, dialect, R2DBC driver version
- **Steps to reproduce** — minimal code snippet; inline any fixture entities
- **Expected behavior** — what should have happened
- **Actual behavior** — what actually happened, plus stack trace if any
- **What you tried** — workarounds, debugging breadcrumbs

### 2. Feature request

- **Problem** — why this matters, in user-scenario terms
- **Proposal** — the API or behavior you want to add or change
- **Alternatives** — what else you considered and why you rejected it
- **Scope** — impact area: new dialect, new core API, new module, etc.

### 3. Documentation

- **Location** — README section, `docs/<file>.md`, or javadoc class
- **Issue type** — missing, incorrect, unclear, or out of date
- **Suggested change** — how the text should read

### 4. Question

- **Goal** — what you are trying to accomplish
- **What you tried** — which code / docs you consulted and where you got stuck

Prefer GitHub Discussions for questions when available. Promote answers that generalize into a doc patch or a tracked issue.

### Good issue titles

- `[bug] schema generation crashes on @OneToMany inverse field`
- `[feat] support Oracle dialect`
- `[docs] README install snippet uses an unrunnable placeholder`

Titles to avoid: `bug`, `not working`, `have a question`.

---

## PR conventions

The [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) is prefilled automatically. Fill in every section.

### PR template

```markdown
## Summary
<1-3 lines: what changed and why>

## Related issue
Closes #<number>

## Changes
- <change 1>
- <change 2>

## Test plan
- [ ] `./gradlew build` passes
- [ ] Narrow test for the changed module passes (`./gradlew :nova-project:<module>:test`)
- [ ] New behavior or bug fix is covered by a regression test
- [ ] Documentation updated — README / docs/ / javadoc (as applicable)

## Breaking change
- [ ] No
- [ ] Yes — impact and migration:

## Checklist
- [ ] Commit messages follow Conventional Commits
- [ ] PR is scoped to a single concern
- [ ] User-visible changes documented under `docs/` (as applicable)
- [ ] New dependencies or modules have prior agreement per AGENTS.md "Boundaries" (as applicable)
```

### PR size

- Prefer a diff of **≤300 lines**. Split larger work into a series of incremental PRs.
- Larger PRs are acceptable when the motivation and scope are clear, but flag the size in advance so reviewers can plan.
- **Single concern** — do not bundle a README trim, a CI change, and a new feature into one PR.

### Merge policy

- Merge after at least one approval.
- No direct pushes to `main`.
- Do not bypass pre-commit / pre-push hooks (`--no-verify`). If a hook fails, fix the root cause.
- Force-push only on your own PR branch. Never force-push `main` or any protected branch.

---

## Code conventions

The full set of conventions — architecture boundaries, testing standards, coding style, build commands, and commit policy — lives in [`AGENTS.md`](AGENTS.md). Read it before opening a PR.

Highlights:
- `nova-core` depends only on Project Reactor and the R2DBC SPI.
- All persistence APIs return `Mono` / `Flux`. Do not expose `block()` / `blockFirst()` etc.
- Transaction state flows through Reactor `Context` only — no `ThreadLocal`.
- Database-specific behavior lives in dialect modules. Never push it into `nova-core`.
- Tests use JUnit 5 + Reactor `StepVerifier`. Introducing AssertJ / Mockito requires prior agreement.

---

## Security issues

Do not file security vulnerabilities as public issues. Email the maintainers directly. See [`SECURITY.md`](SECURITY.md) for the disclosure procedure, when available.

---

## License

Contributions are licensed under the [Apache License 2.0](LICENSE).
