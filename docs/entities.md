<!-- SPDX-License-Identifier: Apache-2.0 -->

# Entities

Nova maps entities with the **real JPA annotations** from `jakarta.persistence` — `@Entity`, `@Table`, `@Column`, `@Id`, `@GeneratedValue`, `@ManyToOne`, `@OneToMany`, `@Version`, `@Embeddable`, `@Enumerated`, the lifecycle callbacks, and so on. A JPA entity is source-compatible as-is; just add the `jakarta.persistence-api` dependency (Nova exports it transitively).

Attributes Nova cannot honor reactively are **rejected fail-fast** at metadata build time rather than silently ignored — see [Unsupported JPA attributes](#unsupported-jpa-attributes) below.

Nova-specific extensions that JPA has no equivalent for live in `io.nova.annotation`: `@CreatedAt`, `@UpdatedAt`, `@SoftDelete`, `@Json`.

## Annotation reference

| Annotation        | Purpose                                                                                  |
|-------------------|------------------------------------------------------------------------------------------|
| `@Entity`         | Marks a class as persistent. Without `name`, the class-name-based default naming applies. |
| `@Table`          | Explicit table name (+ optional `schema`; `catalog` is ignored). When omitted, the `NamingStrategy` decides. |
| `@Id`             | Identifier field. Exactly one is required per entity.                                     |
| `@GeneratedValue` | Identifier strategy (`IDENTITY`, `AUTO`, `SEQUENCE`, `UUID`). Omit `@GeneratedValue` for an application-assigned id. For `SEQUENCE`, `generator` is the sequence name directly, or the `name` of a `@SequenceGenerator` whose `sequenceName` is then used. |
| `@SequenceGenerator` | Maps a logical `@GeneratedValue(generator=...)` name to a real `sequenceName`. `allocationSize` / `initialValue` are ignored (Nova issues a plain `nextval` per insert). |
| `@Column`         | Column name, `nullable`, `length` / `precision` / `scale`, `insertable` / `updatable` / `unique` / `columnDefinition`. |
| `@Lob`            | Maps the column to the dialect LOB type — character LOB (`clob` / `text` / `longtext`) for `String`, binary LOB (`blob` / `bytea` / `longblob`) for `byte[]`. |
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
| `@MappedSuperclass` | TYPE-level marker on a non-entity base class. Its fields (e.g. an inherited id / audit columns) are mapped into every entity that extends it. |
| `@Inheritance`    | TYPE-level marker on a hierarchy root. Only `strategy = SINGLE_TABLE` is supported (the JPA default); `JOINED` / `TABLE_PER_CLASS` are rejected fail-fast. Optional — an `@Entity` extending another `@Entity` defaults to SINGLE_TABLE. |
| `@DiscriminatorColumn` | On the hierarchy root, names the discriminator column (default `dtype`) and its type (`STRING` default, `CHAR`, `INTEGER`). |
| `@DiscriminatorValue`  | On each concrete subtype, the value stored in the discriminator column. For `STRING` it defaults to the entity name; `CHAR` / `INTEGER` require it explicitly. |
| `@Transient`      | Excludes a field from mapping entirely (same effect as the Java `transient` keyword). |
| `@Index`          | Table-level secondary index, declared in `@Table(indexes = ...)` with a comma-separated `columnList`. Without `name`, generated as `ix_{table}_{cols}`. |
| `@UniqueConstraint` | Table-level unique constraint, declared in `@Table(uniqueConstraints = ...)` with a `columnNames` array. Without `name`, generated as `uk_{table}_{cols}`. |
| `@ManyToOne`      | Owning side of a single reference. `findById` / `findAll` automatically hydrate the parent with a single IN query. Target resolved via `targetEntity` or field type; nullability via `optional`. |
| `@OneToMany`      | Inverse-side collection. Requires `mappedBy` naming the child's `@ManyToOne` property. `findById` / `findAll` automatically hydrate children with a single IN query. |
| `@OrderBy`        | On `@OneToMany`, orders hydrated children. `@OrderBy("title DESC, id ASC")` adds the matching `ORDER BY` to the child query; an empty `@OrderBy` sorts by the child's `@Id` ascending. |
| `@AttributeOverride` | On an `@Embedded` field, overrides a sub-property's column name with an absolute name (e.g. `@AttributeOverride(name = "city", column = @Column(name = "ship_city"))`). |
| `@JoinColumn`     | FK column name, nullability, and `insertable` / `updatable` / `unique` seen by `@ManyToOne`. Defaults to `{field}_id`. A clash with a plain `@Column` of the same name raises an explicit error in `EntityMetadataFactory`. |
| `@Enumerated`     | Enum column mapping. `EnumType.ORDINAL` (default) or `EnumType.STRING`.                    |
| `@Convert`        | Applies a `jakarta.persistence.AttributeConverter<X, Y>` to a field. The column is created with the **converter's storage type `Y`** (e.g. an `AttributeConverter<Rgb, Integer>` field gets an `integer` column). `disableConversion = true` turns it off. |
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

## Custom types (`@Convert`)

Map an arbitrary field type to a column with a standard JPA
`jakarta.persistence.AttributeConverter<X, Y>` — `X` is the entity attribute type, `Y`
is the database column type. Annotate the field with `@Convert(converter = ...)`.

```java
public class RgbConverter implements jakarta.persistence.AttributeConverter<Rgb, Integer> {
    public Integer convertToDatabaseColumn(Rgb rgb)   { return rgb == null ? null : rgb.value(); }
    public Rgb     convertToEntityAttribute(Integer v) { return v == null ? null : new Rgb(v); }
}

@Entity
public class Swatch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Convert(converter = RgbConverter.class)
    private Rgb color;        // stored as an `integer` column (the converter's Y type)
}
```

- The column DDL and row decoding use the **converter's storage type `Y`** (here `integer`),
  not the domain type `X`. `@Convert(disableConversion = true)` turns the converter off.
- The converter class needs an accessible no-arg constructor; its `AttributeConverter<X, Y>`
  type arguments must be concrete (resolved by reflection). Both are checked fail-fast.
- `@Convert` cannot be combined with `@Enumerated` / `@Json` or a programmatically registered
  converter for the same type.
- **Not supported:** `@Converter(autoApply = true)` (auto-applying a converter to every field
  of a type) and the `@Convert(attributeName = ...)` form. For "apply to all of type `X`",
  register it programmatically via `EntityMetadataFactory#registerConverter`.

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

## Inheritance (`SINGLE_TABLE`)

Map an entity hierarchy onto one table with a discriminator column, exactly like JPA's
`InheritanceType.SINGLE_TABLE` (the only strategy Nova supports — `JOINED` and
`TABLE_PER_CLASS` are rejected fail-fast).

```java
@Entity
@Table(name = "vehicles")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING)
public abstract class Vehicle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String name;
}

@Entity
@DiscriminatorValue("CAR")
public class Car extends Vehicle {
    private int doors;
}

@Entity
@DiscriminatorValue("TRUCK")
public class Truck extends Vehicle {
    private double payload;
}
```

- **One table.** All subtype columns flatten into the root's table (`vehicles` gets `id`,
  `name`, `doors`, `payload`, `kind`). Subtype-only columns are created **nullable** since
  other subtypes leave them empty. Provision it once via the root —
  `schemaInitializer.create(Vehicle.class, Car.class, Truck.class)` collapses to a single
  `create table vehicles (...)`; the discriminator column (`kind varchar(31) not null`) is
  added automatically.
- **Polymorphic reads.** `findById(Vehicle.class, id)` / `findAll(Vehicle.class, ...)` read
  the discriminator on each row and return the concrete subtype instance (`Car` / `Truck`).
- **Subtype reads are restricted.** `findById(Car.class, id)` / `findAll(Car.class, ...)` /
  `count` / `exists` add `where kind = 'CAR'`, so they only see that exact type's rows.
- **Writes record the discriminator.** `save(new Car(...))` inserts `kind = 'CAR'`
  automatically; the discriminator column is never part of `update`.
- **Defaults.** Without `@DiscriminatorColumn` the column is `dtype` / `STRING`; without
  `@DiscriminatorValue` a `STRING` discriminator defaults to the entity name. `@Inheritance`
  itself is optional — an `@Entity` that simply extends another `@Entity` is treated as a
  SINGLE_TABLE hierarchy (JPA's default).

Subtype discovery follows JPA's persistence-unit model: every entity's metadata must be
built before a polymorphic root query can dispatch rows. The Spring starter does this for
you — it eagerly builds metadata for all `@Entity` classes in `nova.entity-packages` at
startup (regardless of `nova.ddl-auto`). In standalone use, build each subtype's metadata
(e.g. via `schemaInitializer.create(...)` or a `findAll`/`save` on it) before querying the
root polymorphically.

> Single-level leaves are the common case. Querying a non-leaf mid-hierarchy type restricts
> to that type's own discriminator value (not its descendants); `JOINED`,
> `TABLE_PER_CLASS`, and `@DiscriminatorFormula` are not supported.

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

## `@Column` attributes

Nova honors the column attributes that have a clear non-blocking meaning:

| Attribute | Behavior |
|-----------|----------|
| `name`, `nullable`, `length`, `precision`, `scale` | Mapping + DDL as in JPA. |
| `insertable = false` | Column is excluded from generated `INSERT` statements. |
| `updatable = false` | Column is excluded from generated `UPDATE` statements. |
| `unique = true` | Emits an inline `UNIQUE` constraint in the column DDL. |
| `columnDefinition = "..."` | Used verbatim as the column's type in `CREATE TABLE`, replacing the dialect-derived type. |

## Unsupported JPA attributes

Nova reuses the JPA annotations but is a non-blocking, persistence-context-free ORM, so a few attributes cannot be honored. Rather than silently ignoring them (a debugging trap), Nova **rejects them fail-fast** when entity metadata is first built:

| Annotation / attribute | Why rejected |
|------------------------|--------------|
| `@ManyToOne(fetch = LAZY)` | No lazy proxy. Relations are fetched eagerly with a single IN-query, or explicitly via `FetchGroup`. The JPA default `EAGER` is honored. |
| `@ManyToOne(cascade = ...)` / `@OneToMany(cascade = ...)` | No persistence-context graph; persist related entities explicitly with `save` / `saveAll`. |
| `@OneToMany(orphanRemoval = true)` | No dirty-tracking; delete children explicitly. |
| `@Column(table = ...)` | Secondary tables are not supported. |
| `@GeneratedValue(strategy = TABLE)` | Use `IDENTITY`, `SEQUENCE`, `UUID`, or `AUTO`. |
| `@Inheritance(strategy = JOINED \| TABLE_PER_CLASS)` | Only `SINGLE_TABLE` is supported. |

`@OneToMany`'s default `fetch = LAZY` is the one exception: it is treated as eager (Nova's only mode) rather than rejected, since rejecting the default would reject every collection.
