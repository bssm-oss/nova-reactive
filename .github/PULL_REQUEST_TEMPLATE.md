<!--
PR authoring guide: see the "PR conventions" section in CONTRIBUTING.md.
Fill in every section. Use "N/A" for items that do not apply.
-->

## Summary
<!-- 1-3 lines: what changed and why. -->

## Related issue
<!-- For a PR that fully resolves a single issue: Closes #N -->
<!-- For a PR that is only adjacent: Refs #N -->


## Changes
<!-- One bullet per user-visible or structural change. -->
-
-

## Test plan
- [ ] `./gradlew build` passes
- [ ] Narrow test for the changed module passes (`./gradlew :nova-project:<module>:test`)
- [ ] New behavior or bug fix is covered by a regression test
- [ ] Documentation updated as needed — README / `docs/` / javadoc

## Breaking change
- [ ] No
- [ ] Yes — describe the impact and the migration path:

## Checklist
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
- [ ] PR is scoped to a single concern (unrelated changes go in a separate PR)
- [ ] User-visible changes are documented under `docs/` (as applicable)
- [ ] New dependencies or modules have prior agreement per `AGENTS.md` "Boundaries" (as applicable)
- [ ] No AI agent listed as `Co-Authored-By:`
