<!-- SPDX-License-Identifier: Apache-2.0 -->

# Entities

Nova의 어노테이션은 `io.nova.annotation` 패키지에 있으며, JPA와 의미를 최대한 일치시킵니다.

## Annotation reference

| Annotation        | Purpose                                                                                  |
|-------------------|------------------------------------------------------------------------------------------|
| `@Entity`         | 영속 가능 클래스 표시. `name` 미지정 시 클래스 이름 기반 기본 명명 규칙이 적용됩니다.            |
| `@Table`          | 테이블 이름 명시. 미지정 시 `NamingStrategy`가 결정합니다.                                    |
| `@Id`             | 식별자 필드 지정. 엔티티당 정확히 하나여야 합니다.                                              |
| `@GeneratedValue` | 식별자 생성 전략 (`IDENTITY`, `AUTO`, `SEQUENCE`, `UUID`, `NONE`). `SEQUENCE`는 `generator` 속성으로 시퀀스 이름 지정. |
| `@Column`         | 컬럼 이름, `nullable`, `length`/`precision`/`scale` 등 매핑 메타데이터.                       |
| `@CreatedAt`      | insert 시점에 현재 시각 자동 채움 (`Instant`/`LocalDateTime`/`OffsetDateTime`). 사용자가 값을 미리 set하면 보존. |
| `@UpdatedAt`      | insert / update / partial update / Updater 경로에서 항상 현재 시각으로 덮어쓰기.                |
| `@SoftDelete`     | delete 호출을 `UPDATE deleted_at = now`로 변환. 모든 SELECT 경로에 자동 `WHERE deleted_at IS NULL` 추가. |
| `@Version`        | optimistic locking. `Long`/`Integer`/`Short` 지원. 충돌 시 `OptimisticLockingFailureException`. |
| `@PrePersist`     | 엔티티 lifecycle 콜백 — insert 직전 호출 (`void` no-arg method).                              |
| `@PreUpdate`      | update / partial update 직전 호출.                                                            |
| `@PostLoad`       | findById / findAll hydration 직후 호출.                                                       |
| `@PreRemove`      | delete 직전 (soft/hard 무관) 호출.                                                            |
| `@Embeddable`     | 자체 식별자 없이 호스트 엔티티 테이블에 컬럼들로 펼쳐지는 composite value type 표시 (TYPE-level). |
| `@Embedded`       | 엔티티 필드가 `@Embeddable` 타입을 호스트 컬럼들로 펼쳐 매핑됨을 표시 (FIELD-level).             |
| `@Index`          | 테이블 레벨 secondary index (TYPE-level, `@Repeatable`). `name` 미지정 시 `ix_{table}_{cols}` 자동 생성. |
| `@UniqueConstraint` | 테이블 레벨 unique constraint (TYPE-level, `@Repeatable`). `name` 미지정 시 `uk_{table}_{cols}` 자동 생성. |
| `@ManyToOne`      | 단일 참조 owning side. `findById`/`findAll` 호출 시 자동으로 referenced parent를 IN-query 한 번으로 hydrate. `targetEntity` 또는 필드 타입으로 대상 결정, `optional` 로 nullable 제어. |
| `@OneToMany`      | inverse side collection. 필수 속성 `mappedBy`가 child 측 `@ManyToOne` property 이름. `findById`/`findAll` 호출 시 자동으로 children을 IN-query 한 번으로 hydrate. |
| `@JoinColumn`     | `@ManyToOne`이 보는 FK 컬럼 이름과 nullable. 미지정 시 `{field}_id` 기본 명명 적용. 동일 이름의 일반 `@Column`과 충돌하면 `EntityMetadataFactory`가 명시적 예외. |
| `@Enumerated`     | enum 컬럼 매핑. `EnumType.ORDINAL`(default) / `EnumType.STRING` 선택.                          |
| `@Json`           | JSON 컬럼 매핑. `JsonCodec` SPI 주입 필요. PostgreSQL은 `jsonb`, Oracle은 `clob`, 그 외는 `text`. |

엔티티 메타데이터는 `EntityMetadataFactory`가 한 번만 분석해 캐시하며, 다음 조건을 강제합니다.

- `@Entity`가 반드시 있어야 합니다.
- `@Id` 필드는 **정확히 하나** 존재해야 합니다.
- 기본 생성자가 필요합니다.
- 지원하지 않는 타입은 명시적으로 거부되며 `AttributeConverter`로 확장할 수 있습니다.
- `@CreatedAt`/`@UpdatedAt`/`@SoftDelete`/`@Version`이 중복 선언되거나 지원 외 타입에 붙으면 metadata 생성 시점에 fail-fast.
- `property name → PersistentProperty` 인덱스를 한 번 빌드해 모든 lookup이 O(1).

---

## Composite types

여러 컬럼을 묶어 도메인 의미를 가진 단일 객체로 매핑하고 싶을 때 `@Embeddable` value type을 정의하고 호스트 엔티티에서 `@Embedded` 필드로 사용합니다. 컬럼은 호스트 테이블에 그대로 펼쳐지므로 별도 join이 발생하지 않습니다.

```java
@Embeddable
public static class Address {
    private String city;
    private String street;
    private String zip;

    public Address() {}

    public Address(String city, String street, String zip) {
        this.city = city;
        this.street = street;
        this.zip = zip;
    }
    // getters...
}

@Entity
@Table("customer")
public static class Customer {
    @Id
    private Long id;

    private String name;

    @Embedded
    private Address shipping;

    public Customer() {}
}
```

컬럼 이름은 `{field name (snake_case)}_{sub property column name}` 규칙으로 합성됩니다 — 위 예시는 `shipping_city`, `shipping_street`, `shipping_zip` 컬럼으로 펼쳐집니다.

`@Embeddable` 타입은 자체 식별자를 가질 수 없으며 (`@Id` 금지), 내부 sub-property에 `@Version`/`@SoftDelete` 같은 marker를 두는 것도 metadata 생성 시점에 fail-fast로 거부됩니다.

---

## Relationships (`@ManyToOne` / `@OneToMany`)

owning side는 `@ManyToOne` + `@JoinColumn`(선택)으로, inverse side는 `@OneToMany(mappedBy = "...")`로 선언합니다. `findById` / `findAll` 호출 시 자동으로 child IN-query 한 번이 발행되어 hydration됩니다 — N+1이 발생하지 않습니다.

```java
@Entity
@Table("authors")
public static class Author {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;

    @OneToMany(mappedBy = "author")    // author 테이블에 컬럼 없음, marker만
    private List<Book> books;
    // getters/setters...
}

@Entity
@Table("books")
public static class Book {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String title;

    @ManyToOne
    @JoinColumn(name = "author_id")    // FK 컬럼명, 미지정 시 author_id 기본 명명
    private Author author;
    // getters/setters...
}
```

- 명시적 fetch가 필요한 경우 `FetchGroup` DSL을 `findById(Class, ID, FetchGroup)` / `findAll(Class, FetchGroup)`에 넘기면 사용자 spec과 어노테이션 spec이 `(childType, FK column)` 기준으로 dedupe되어(user 우선) 한 번씩 fetch됩니다.
- `@ManyToOne`이 보는 FK 컬럼이 같은 entity의 일반 `@Column(name)`과 충돌하면 `EntityMetadataFactory`가 명시적 예외로 silent dedupe를 방지합니다.
- lazy proxy / 영속성 컨텍스트는 도입하지 않으므로 부분 collection을 원하면 `FetchGroup` + 명시 호출을 사용합니다.

---

## Indexes and unique constraints

테이블 레벨 secondary index와 unique constraint는 엔티티 타입에 `@Index` / `@UniqueConstraint`로 선언합니다. 두 어노테이션 모두 `@Repeatable`이므로 같은 엔티티에 여러 개를 자유롭게 붙일 수 있습니다.

```java
@Entity
@Table("accounts")
@Index(columns = {"email"})                                 // ix_accounts_email 로 자동 명명
@Index(name = "ix_active_created", columns = {"active", "created_at"})
@UniqueConstraint(columns = {"tenant_id", "email"})         // uk_accounts_tenant_id_email
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column("tenant_id")
    private Long tenantId;

    @Column("email")
    private String email;

    @Column("active")
    private boolean active;

    @CreatedAt
    @Column("created_at")
    private Instant createdAt;
}
```

- `name`이 빈 문자열이면 `ix_{table}_{col1}_{col2}_...` / `uk_{table}_{col1}_{col2}_...` 패턴으로 자동 생성됩니다.
- 자동 생성된 이름이 dialect 식별자 길이 한도를 넘기면 짧은 해시 접미사로 축약(truncate)됩니다.
- `columns()`는 1개 이상이어야 하며 실제 컬럼 이름(`@Column`이 명시한 값 또는 snake_case 변환 결과)을 사용해야 합니다. 메타데이터에 존재하지 않는 컬럼은 fail-fast로 거부됩니다.

실제 DDL은 [Dialects & Schema](dialects.md)의 `createIndexes(...)`로 발행합니다.
