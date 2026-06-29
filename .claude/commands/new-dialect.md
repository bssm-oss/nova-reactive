---
description: Nova의 새 DB dialect 모듈을 스캐폴드한다 (디렉토리, build.gradle.kts, Dialect/SqlRenderer/SchemaGenerator 골격, 테스트 골격, settings.gradle.kts 등록).
argument-hint: <db-name> (예: h2, mariadb, oracle)
---

새 dialect 모듈을 스캐폴드합니다: **$ARGUMENTS**

## 절차

1. **인자 정규화**
   - 모듈 디렉토리: `nova-dialect-{인자를 모두 소문자로}`
   - 패키지: `io.nova.dialect.{소문자}`
   - 클래스 prefix: 인자를 PascalCase로 (예: `mariadb` → `Mariadb`, `h2` → `H2`, `oracle` → `Oracle`)
   - 인자가 비어 있으면 즉시 중단하고 사용자에게 DB 이름을 요청한다.

2. **사전 점검**
   - `nova-dialect-{name}/` 디렉토리가 이미 존재하면 중단하고 사용자에게 알린다 (덮어쓰지 않음).
   - `settings.gradle.kts`에 동일 이름의 `include`가 이미 있는지 확인.

3. **디렉토리 생성**
   ```
   nova-dialect-{name}/
     build.gradle.kts
     src/main/java/io/nova/dialect/{name}/{Prefix}Dialect.java
     src/main/java/io/nova/dialect/{name}/{Prefix}SqlRenderer.java
     src/main/java/io/nova/dialect/{name}/{Prefix}SchemaGenerator.java
     src/test/java/io/nova/dialect/{name}/{Prefix}DialectTest.java
     src/test/java/io/nova/dialect/{name}/{Prefix}SampleAccount.java
   ```

4. **`build.gradle.kts`** — 기존 dialect와 동일하게:
   ```kotlin
   dependencies {
       api(project(":nova-core"))

       testImplementation(platform("org.junit:junit-bom:5.12.0"))
       testImplementation("org.junit.jupiter:junit-jupiter")
       testRuntimeOnly("org.junit.platform:junit-platform-launcher")
   }
   ```
   **그 외 의존성 추가 금지.**

5. **클래스 골격** — 기존 `PostgresqlDialect`와 `MySqlDialect` 중 더 가까운 쪽을 베이스로 삼아 차이만 override.
   - `{Prefix}Dialect` — `Dialect` 인터페이스 5개 메서드 구현
   - `{Prefix}SqlRenderer extends AbstractSqlRenderer` — bind marker, quote
   - `{Prefix}SchemaGenerator extends AbstractSchemaGenerator` — identity column 문법
   - **확인 안 된 문법은 골격에 그대로 두지 말고 `TODO({name}): ...` 주석으로 명시**

6. **테스트 골격** — `PostgresqlDialectTest` 구조 그대로:
   - bind marker 형식 검증 1 케이스
   - `CREATE TABLE` 출력 검증 1 케이스
   - 픽스처는 in-file `{Prefix}SampleAccount`

7. **`settings.gradle.kts` 갱신** — `include(...)` 블록에 새 모듈 추가.

8. **검증** — `./gradlew :nova-dialect-{name}:compileJava :nova-dialect-{name}:compileTestJava` 통과 확인. 실패하면 즉시 사용자에게 보고.

9. **마무리 안내** — 사용자에게:
   - 생성된 파일 트리
   - TODO 항목 목록 (어떤 SQL 문법을 채워야 하는지)
   - 후속 작업으로 `dialect-implementer` subagent에 위임할 수 있음을 안내

## 주의

- 코어(`nova-core`) 인터페이스는 절대 수정하지 않는다.
- 새 데이터베이스 드라이버 의존성을 모듈 `build.gradle.kts`에 추가하지 않는다.
- 작업 전후 `git status`로 변경 범위가 새 dialect 모듈 + `settings.gradle.kts` 한 줄에 한정되는지 확인.
