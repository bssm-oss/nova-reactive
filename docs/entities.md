<!-- SPDX-License-Identifier: Apache-2.0 -->

# Entities

Nova maps entities with the **real JPA annotations** from `jakarta.persistence` — `@Entity`, `@Table`, `@Column`, `@Id`, `@GeneratedValue`, `@ManyToOne`, `@OneToMany`, `@Version`, `@Embeddable`, `@Enumerated`, the lifecycle callbacks, and so on. A JPA entity is source-compatible as-is; just add the `jakarta.persistence-api` dependency (Nova exports it transitively).

Attributes Nova cannot honor reactively are **rejected fail-fast** at metadata build time rather than silently ignored — see [Unsupported JPA attributes](#unsupported-jpa-attributes) below.

Nova-specific extensions that JPA has no equivalent for live in `io.nova.annotation`: `@CreatedAt`, `@UpdatedAt`, `@SoftDelete`, `@Json`.

## Annotation reference

| Annotation        | Purpose                                                                                  |
|-------------------|------------------------------------------------------------------------------------------|
| `@Entity`         | Marks a class as persistent. Without `name`, the class-name-based default naming applies. |
| `@Table`          | Explicit table name. When omitted, the `NamingStrategy` decides.                          |
| `@Id`             | Identifier field. Exactly one is required per entity.                                     |
| `@GeneratedValue` | Identifier strategy (`IDENTITY`, `AUTO`, `SEQUENCE`, `UUID`). Omit `@GeneratedValue` for an application-assigned id. For `SEQUENCE`, set `generator` to the sequence name. |
| `@Column`         | Column name, `nullable`, `length` / `precision` / `scale`, and other mapping metadata.    |
| `@CreatedAt`      | Auto-populates the field with the current time on insert (`Instant` / `LocalDateTime` / `OffsetDateTime`). Preserves a value the user pre-sets. |
| `@UpdatedAt`      | Overwritten with the current time on insert, update, partial update, and Updater paths.   |
| `@SoftDelete`     | Rewrites DELETE as `UPDATE deleted_at = now`. Every SELECT path automatically gets a `WHERE deleted_at IS NULL` guard. |
| `@Version`        | Optimistic locking. `Long` / `Integer` / `Short` supported. Surfaces `OptimisticLockingFailureException` on conflict. |
| `@PrePersist`     | Entity lifecycle callback — invoked just before insert (`void`, no-arg).                  |
| `@PostPersist`    | Invoked right after a successful insert (generated id already assigned).                   |
| `@PreUpdate`      | Invoked just before update / partial update.                                              |
| `@PostUpdate`     | Invoked right after a successful update / partial update.                                  |
| `@PostLoad`       | Invoked right after hydration in `findById` / `findAll`.                                  |
| `@PreRemove`      | Invoked just before delete (soft or hard).                                                |
| `@PostRemove`     | Invoked right after a successful delete (soft or hard).                                    |
| `@Embeddable`     | TYPE-level marker for a composite value type with no identifier of its own; columns flatten into the host entity's table. |
| `@Embedded`       | FIELD-level marker indicating that an entity field is an `@Embeddable` flattened into host columns. |
| `@Index`          | Table-level secondary index, declared in `@Table(indexes = ...)` with a comma-separated `columnList`. Without `name`, generated as `ix_{table}_{cols}`. |
| `@UniqueConstraint` | Table-level unique constraint, declared in `@Table(uniqueConstraints = ...)` with a `columnNames` array. Without `name`, generated as `uk_{table}_{cols}`. |
| `@ManyToOne`      | Owning side of a single reference. `findById` / `findAll` automatically hydrate the parent with a single IN query. Target resolved via `targetEntity` or field type; nullability via `optional`. |
| `@OneToMany`      | Inverse-side collection. Requires `mappedBy` naming the child's `@ManyToOne` property. `findById` / `findAll` automatically hydrate children with a single IN query. |
| `@JoinColumn`     | FK column name and nullability seen by `@ManyToOne`. Defaults to `{field}_id`. A clash with a plain `@Column` of the same name raises an explicit error in `EntityMetadataFactory`. |
| `@Enumerated`     | Enum column mapping. `EnumType.ORDINAL` (default) or `EnumType.STRING`.                    |
| `@Json`           | JSON column mapping. Requires a `JsonCodec` SPI. Maps to `jsonb` on PostgreSQL, `clob` on Oracle, and `text` elsewhere. |

Entity metadata is parsed once and cached by `EntityMetadataFactory`. The factory enforces the following invariants:

- `@Entity` is required.
- Exactly one `@Id` field must be present.
- A no-arg constructor is required.
- Unsupported types are rejected explicitly and can be extended via `AttributeConverter`.
- Duplicate `@CreatedAt` / `@UpdatedAt` / `@SoftDelete` / `@Version`, or those markers on unsupported types, fail-fast at metadata build time.
- A `property name → PersistentProperty` index is built once so every lookup is O(1).

---

## Composite types

When you want to map several columns as a single domain object, define an `@Embeddable` value type and use it as an `@Embedded` field on the host entity. The columns flatten into the host's table, so no join is involved.

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
@Table(name = "customer")
public static class Customer {
    @Id
    private Long id;

    private String name;

    @Embedded
    private Address shipping;

    public Customer() {}
}
```

Column names compose as `{field name (snake_case)}_{sub-property column name}` — the example above flattens to `shipping_city`, `shipping_street`, `shipping_zip`.

An `@Embeddable` type cannot declare its own identifier (`@Id` is rejected). Markers such as `@Version` / `@SoftDelete` on the embedded sub-properties are also rejected at metadata build time.

---

## Relationships (`@ManyToOne` / `@OneToMany`)

Declare the owning side with `@ManyToOne` (+ optional `@JoinColumn`) and the inverse side with `@OneToMany(mappedBy = "...")`. `findById` / `findAll` automatically issue a single child IN-query per hit, so N+1 does not occur.

```java
@Entity
@Table(name = "authors")
public static class Author {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;

    @OneToMany(mappedBy = "author")    // no column on author table — just the marker
    private List<Book> books;
    // getters/setters...
}

@Entity
@Table(name = "books")
public static class Book {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String title;

    @ManyToOne
    @JoinColumn(name = "author_id")    // FK column name; defaults to author_id
    private Author author;
    // getters/setters...
}
```

- For explicit fetch control, pass a `FetchGroup` to `findById(Class, ID, FetchGroup)` / `findAll(Class, FetchGroup)`. User-supplied and annotation-derived specs are deduped by `(childType, FK column)` with the user spec winning, so each child is fetched exactly once.
- If the FK column seen by `@ManyToOne` clashes with another `@Column(name)` on the same entity, `EntityMetadataFactory` raises an explicit error rather than silently merging them.
- There is no lazy proxy and no persistence context. For partial collections, drive `FetchGroup` explicitly.

---

## Indexes and unique constraints

Declare table-level secondary indexes and unique constraints as members of `@Table`
(`indexes` / `uniqueConstraints`), exactly like JPA. Each member array takes as many
`@Index` / `@UniqueConstraint` as needed.

```java
@Entity
@Table(name = "accounts",
        indexes = {
                @Index(columnList = "email"),                              // auto-named ix_accounts_email
                @Index(name = "ix_active_created", columnList = "active, created_at")
        },
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "email"}))  // uk_accounts_tenant_id_email
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "email")
    private String email;

    @Column(name = "active")
    private boolean active;

    @CreatedAt
    @Column(name = "created_at")
    private Instant createdAt;
}
```

- When `name` is blank, names are generated as `ix_{table}_{col1}_{col2}_...` / `uk_{table}_{col1}_{col2}_...`.
- If an auto-generated name exceeds the dialect's identifier-length limit, it is truncated with a short hash suffix.
- `@Index#columnList()` is a comma-separated list (JPA style); `@UniqueConstraint#columnNames()` is a string array. Both require at least one entry and must use the actual column names (the `@Column(name)` value or the snake_case-converted name). Names that do not exist in the entity metadata are rejected fail-fast.

Emit the DDL with `createIndexes(...)` — see [Dialects & Schema](dialects.md).

---

## Unsupported JPA attributes

Nova reuses the JPA annotations but is a non-blocking, persistence-context-free ORM, so a few JPA attributes cannot be honored. Rather than silently ignoring them (a debugging trap), Nova **rejects them fail-fast** when entity metadata is first built:

| Annotation / attribute | Why rejected |
|------------------------|--------------|
| `@ManyToOne(fetch = LAZY)` | No lazy proxy. Relations are fetched eagerly with a single IN-query, or explicitly via `FetchGroup`. The JPA default `EAGER` is honored. |
| `@ManyToOne(cascade = ...)` / `@OneToMany(cascade = ...)` | No persistence-context graph; persist related entities explicitly with `save` / `saveAll`. |
| `@OneToMany(orphanRemoval = true)` | No dirty-tracking; delete children explicitly. |
| `@Column(insertable = false)` / `@Column(updatable = false)` | Nova always binds the column on insert/update. |
| `@Column(unique = true)` | Use `@Table(uniqueConstraints = ...)` instead. |
| `@Column(table = ...)` | Secondary tables are not supported. |
| `@Column(columnDefinition = ...)` | Column DDL is derived from the field type by the dialect. |
| `@GeneratedValue(strategy = TABLE)` | Use `IDENTITY`, `SEQUENCE`, `UUID`, or `AUTO`. |

`@OneToMany`'s default `fetch = LAZY` is the one exception: it is treated as eager (Nova's only mode) rather than rejected, since rejecting the default would reject every collection.
