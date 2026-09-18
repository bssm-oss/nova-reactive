# Nova Release

Nova를 Maven Central(`io.github.bssm-oss`)에 발행한다. **발행은 로컬에서 아무것도 빌드/업로드하지 않는다** — `v<version>` 태그를 push하면 GitHub Actions `release.yml`이 전부 수행한다. 이 스킬은 태그를 안전하게 찍고 GHA를 모니터링하는 절차 + 과거에 실제로 막혔던 함정 회피다.

## Trigger 조건

- 사용자가 "릴리즈"/"배포"/"발행"/"태그 찍어"/"vX.Y.Z 내보내"를 요청
- 하나 이상의 cycle이 main에 머지된 뒤 버전을 내보낼 때

**Invoke하지 않는 경우**: 아직 미머지 PR이 있거나, 사용자가 버전 번호를 확정하지 않았을 때(먼저 `AskUserQuestion`으로 확정).

## Phase 0 — 사전 조건 (반드시 확인)

1. `git switch main && git pull --ff-only` — 로컬 main이 origin/main과 일치.
2. 발행 대상 커밋이 전부 main에 있음(미머지 PR 없음). `gh pr list --state open`.
3. **버전 번호 확정**: 최신 태그 `git tag --sort=-v:refname | head -3` 확인 후 SemVer 판단 — 기능 추가=minor, 버그픽스/문서만=patch, breaking=major. 애매하면 `AskUserQuestion`으로 사용자 확정(자율 결정 금지).
4. 태그 중복 확인: `git tag -l v<version>`이 비어 있어야 함.

## Phase 1 — 태그 push (= 발행 트리거)

```
git tag -a v<version> -m "<version> — <한 줄 요약>"
git push origin v<version>
```

- 태그 이름은 **반드시 `v<version>`** (`release.yml`이 `v`를 떼고 `-Pnova.version`으로 주입). `build.gradle.kts` 파일 버전 범프 **불필요** — property override됨.
- `-SNAPSHOT`으로 끝나면 Central snapshots로, 아니면 staging→자동 release로 간다.
- **로컬 시크릿 불필요**: 서명·업로드·transfer는 전부 GHA Secrets(`CENTRAL_USERNAME`/`CENTRAL_PASSWORD`/`SIGNING_KEY`/`SIGNING_PASSWORD`)로 수행.

## Phase 2 — GHA 모니터링

```
gh run list --workflow=release.yml --limit 3
gh run watch <run-id>          # 또는 gh run view <run-id> --log-failed
```

워크플로가 수행하는 것: build → `publishAllPublicationsToCentralRepository`(OSSRH staging PUT) → **transfer POST**(staging→Portal, `publishing_type=automatic`) → 검증 통과 시 자동 release. `-SNAPSHOT`은 transfer 단계 skip.

## Phase 3 — 발행의 3대 사일런트 함정 (실패 시 진단 순서)

GHA가 실패하거나 태그 push 후 Central에 안 뜨면 이 순서로 확인:

1. **OSSRH 업로드 성공했는데 Portal Deployments에 안 뜸** — 정상이다. OSSRH staging PUT은 buffer일 뿐이고, **같은 runner IP에서 transfer POST**(`https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/<namespace>?publishing_type=automatic`, `Authorization: Bearer <base64(user:pass)>`)가 있어야 Portal에 노출된다. `release.yml`이 이미 수행하므로, 이 단계 로그가 있는지 확인.

2. **transfer가 HTTP 400 `Could not find a public key by the key fingerprint`** — PGP public key가 keyserver 미발행. Sonatype은 `keyserver.ubuntu.com`/`keys.openpgp.org`를 쿼리. 해결: `gpg --keyserver hkps://keyserver.ubuntu.com --send-keys <FINGERPRINT>`.

3. **`gpg --send-keys`가 네트워크 차단(port 11371/dirmngr)** — curl로 우회:
   ```
   gpg --armor --export <FINGERPRINT> > /tmp/pubkey.asc
   curl -X POST --data-urlencode "keytext@/tmp/pubkey.asc" https://keyserver.ubuntu.com/pks/add
   ```
   HTTP 200 + `{"inserted":[...]}`면 성공. 확인: `curl -s "https://keyserver.ubuntu.com/pks/lookup?search=0x<KEYID>&op=index"`가 `Not Found`가 아니면 OK.

**추가 함정**: `build.gradle.kts`는 `SIGNING_KEY`가 blank면 signing 플러그인을 skip → secret 누락이 build failure가 아니라 **silent unsigned upload**로 이어짐. GHA 로그에서 `signMavenPublication` task 존재 여부로 확인.

## Phase 4 — 발행 확인

- 태그 push 후 `repo1.maven.org` 노출은 **10~30분 전파 지연**. 즉시 안 보여도 정상.
- 확인: `curl -sI https://repo1.maven.org/maven2/io/github/bssm-oss/nova-core/<version>/nova-core-<version>.pom`가 200이면 발행 완료.
- 사용자에게 발행 버전 + 전파 지연 안내로 보고.

## 보안 hard rules (절대 위반 금지)

- **PGP private key(`gpg --armor --export-secret-keys` 출력) 절대 채팅/로그/Bash에 노출 금지.** 사용자가 직접 GitHub Secrets 웹 UI에 붙여넣는다. 어시스턴트가 대신 실행하지 않는다.
- `gh secret set`도 어시스턴트가 직접 실행하지 않는다(Bash 로그 노출 위험). 사용자에게 `! <command>` 안내.
- PGP fingerprint와 public key는 공개 정보이므로 취급 가능.

## Memory 참조

- `maven-central-publish-flow` — 3대 함정 상세(이 스킬의 근거)
- `project_jpa_compat_backlog.md` — 발행된 버전 이력
