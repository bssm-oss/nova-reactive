<!-- SPDX-License-Identifier: Apache-2.0
     https://www.apache.org/licenses/LICENSE-2.0 -->

# AGENTS instructions

Nova is a lightweight reactive ORM for Java 21, built on R2DBC SPI and
Project Reactor.

## Architecture Boundaries

1. `nova-core` production code depends only on Project Reactor and R2DBC SPI.
2. `nova-r2dbc` owns R2DBC connection handling, statement execution, row
   adaptation, and transaction resource handling.
3. Dialect modules depend on `nova-core` only for production code. Bind markers,
   identifier quoting, identity columns, and database type mapping belong there.
4. Public persistence APIs return `Mono` or `Flux`. Do not expose blocking calls
   such as `block()`, `blockFirst()`, `blockLast()`, `toIterable()`, or
   `Future.get()`.
5. Transaction state flows through Reactor `Context`. Do not introduce
   `ThreadLocal` or `InheritableThreadLocal` for transaction propagation.
6. Ask before changing core contract signatures such as `Dialect`,
   `SqlRenderer`, `SchemaGenerator`, `SqlExecutor`, `ReactiveEntityOperations`,
   `ReactiveTransactionOperations`, or `ReactiveTransactionManager`.

## Boundaries

Ask first before:

- Adding production dependencies to `nova-core` or `nova-r2dbc`.
- Performing broad cross-module refactors.
- Adding a new supported database dialect.
- Changing build publication shape or Maven coordinates.

Never:

- Commit secrets, credentials, or tokens.
- Run destructive git operations unless explicitly requested.
- Edit generated files by hand when a generation workflow exists.
- Move database-specific behavior from dialect modules into `nova-core`.

## Commands

- Full build and tests: `./gradlew build`
- All tests: `./gradlew test`
- Fast compile check: `./gradlew compileJava`
- Core tests: `./gradlew :nova-project:nova-core:test`
- R2DBC adapter tests: `./gradlew :nova-project:nova-r2dbc:test`
- PostgreSQL dialect tests: `./gradlew :nova-project:nova-dialects:nova-dialect-postgresql:test`
- MySQL dialect tests: `./gradlew :nova-project:nova-dialects:nova-dialect-mysql:test`

Always use the Gradle Wrapper. Do not require a host Gradle or a host JDK
different from the Java 21 toolchain configured by Gradle.

After changing source, tests, or build logic, run the relevant narrow test first
when useful, then finish with `./gradlew build`.

## Testing Standards

- Test files follow `{ClassUnderTest}Test.java`.
- Use JUnit 5 and `org.junit.jupiter.api.Assertions.*`.
- Use Reactor `StepVerifier` for `Mono` and `Flux` behavior.
- Do not introduce AssertJ, Hamcrest, or Mockito without explicit approval.
- Keep SQL, executor, and transaction test doubles inside the relevant test
  class.
- Shared entity fixtures belong in
  `io.nova.support.fixtures.FixtureEntities` as static nested POJOs.

## Coding Standards

- Keep code inside the existing `io.nova.*` package boundaries.
- Use constructor injection.
- Keep fields `final` unless JavaBean-style entity mapping requires mutation.
- Keep metadata types such as `EntityMetadata` and `PersistentProperty`
  immutable after construction.
- Name default implementations with `Simple*`, extension bases with
  `Abstract*`, and helpers with suffixes such as `*Factory`, `*Strategy`, and
  `*Detector`.
- Do not write code that assumes unsupported annotations or features already
  exist, such as relationship mapping or optimistic locking.

## Repository Structure

Gradle project paths are nested under `:nova-project`, while Maven coordinates
stay flat under `io.nova`.

- `nova-project/nova-core/` — annotations, metadata, query DSL, SQL rendering,
  schema generation, transaction abstractions, and core operations.
- `nova-project/nova-r2dbc/` — R2DBC connection, statement, row, and transaction
  adapters.
- `nova-project/nova-dialects/nova-dialect-postgresql/` — PostgreSQL dialect.
- `nova-project/nova-dialects/nova-dialect-mysql/` — MySQL dialect.
- `nova-project/nova/` — aggregate module and `io.nova.Nova` factory entry
  point.

The container projects `:nova-project` and `:nova-project:nova-dialects` are
source-less grouping projects. Keep the root `build.gradle.kts` guard:
`if (childProjects.isNotEmpty()) return@subprojects`.

## Commits and PRs

- Use Conventional Commits prefixes such as `feat:`, `fix:`, `test:`,
  `refactor:`, `docs:`, and `chore:`.
- Keep each PR focused on one change.
- Do not add an AI agent as `Co-Authored-By`.
- Never write a bare `@Name` in a commit or PR title. JPA annotation names
  (`@Id`, `@Entity`, `@OneToMany`, `@ManyToOne`, `@Convert`, `@Embeddable`, ...)
  resolve to real GitHub accounts and organizations, so a bare `@` notifies
  unrelated people and can pull them into the contributor view. Write the
  annotation without the `@` (e.g. `OneToMany`, `IdClass`) or wrap it in
  backticks (`` `@OneToMany` ``). Release notes escape this automatically via
  release-drafter's `change-title-escapes`, but commit/PR titles themselves are
  not escaped by GitHub.

## Default work pattern

For any multi-feature batch (3+ features in one request, "cycle"/"Pack X"/"batch"
keywords, or follow-up bundles), invoke the `parallel-cycle` project skill
(`.claude/skills/parallel-cycle/SKILL.md`) rather than improvising. It encodes
the 4-worktree spawn → 4-reviewer → sequential ff-merge → cleanup → next-scope
confirm workflow, including hub-conflict abort criteria and marker-namespace
separation for parallel metadata changes. Single isolated changes (typo fix,
one-file refactor) skip the skill and proceed directly.
