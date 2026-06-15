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
| `@Id`             | Single identifier field. Exactly one `@Id` **or** one `@EmbeddedId` is required per entity. |
| `@EmbeddedId`     | Composite primary key. The field's type is an `@Embeddable` whose fields become the key columns (no host-field prefix; `@AttributeOverride` renames them). The key is application-assigned — `save()` resolves insert vs. update with an existence check. `@GeneratedValue` on a component is rejected. |
| `@IdClass`        | Composite primary key declared as several top-level `@Id` fields plus a mirror id class. The id class must declare a matching field (name + type) for each `@Id` and a no-arg constructor. Same application-assigned semantics as `@EmbeddedId`; cannot be combined with it. |
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
| `@EntityListeners` | TYPE-level marker registering external listener classes whose methods carry the same lifecycle annotations. Listener callbacks take the entity as a single argument and fire **before** the entity's own callbacks. See [Entity listeners](#entity-listeners). |
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
| `@OneToOne`       | Single reference. **Owning** side (`@JoinColumn`, no `mappedBy`) holds a unique FK column and hydrates like `@ManyToOne`. **Inverse** side (`@OneToOne(mappedBy = "...")`) has no column and hydrates a single entity via the owner's FK. `fetch = LAZY` / `cascade` are rejected. |
| `@ManyToMany` / `@JoinTable` | Many-to-many via a link table. **Owning** side (`@JoinTable`, no `mappedBy`) defines the table + join columns; **inverse** side (`@ManyToMany(mappedBy = "...")`) reuses it. Both `List` and `Set` are supported. The link table is auto-created by the schema initializer. `save(owner)` reconciles the link rows (full-replace); both sides hydrate eagerly via a 2-hop IN-query. `cascade` is rejected; single-keyed owner/target only (v1). |
| `@ElementCollection` / `@CollectionTable` | A collection of **basic-typed** values (`List`/`Set` of `String`, `Integer`, …) stored in a separate `(owner_fk, value)` table, auto-created by the schema initializer. `@CollectionTable` / `@JoinColumn` / `@Column` override the table, owner-FK, and value column names. `save(owner)` reconciles the rows (full-replace); `findById` / `findAll` hydrate via one IN-query. `@Embeddable` element types are not supported yet (v1). |
| `@OrderBy`        | On `@OneToMany`, orders hydrated children. `@OrderBy("title DESC, id ASC")` adds the matching `ORDER BY` to the child query; an empty `@OrderBy` sorts by the child's `@Id` ascending. |
| `@AttributeOverride` | On an `@Embedded` field, overrides a sub-property's column name with an absolute name (e.g. `@AttributeOverride(name = "city", column = @Column(name = "ship_city"))`). |
| `@JoinColumn`     | FK column name, nullability, and `insertable` / `updatable` / `unique` seen by `@ManyToOne`. Defaults to `{field}_id`. A clash with a plain `@Column` of the same name raises an explicit error in `EntityMetadataFactory`. |
| `@Enumerated`     | Enum column mapping. `EnumType.ORDINAL` (default) or `EnumType.STRING`.                    |
| `@Convert`        | Applies a `jakarta.persistence.AttributeConverter<X, Y>` to a field. The column is created with the **converter's storage type `Y`** (e.g. an `AttributeConverter<Rgb, Integer>` field gets an `integer` column). `disableConversion = true` turns it off. |
| `@Json`           | JSON column mapping. Requires a `JsonCodec` SPI. Maps to `jsonb` on PostgreSQL, `clob` on Oracle, and `text` elsewhere. |

Entity metadata is parsed once and cached by `EntityMetadataFactory`. The factory enforces the following invariants:

- `@Entity` is required.
- Exactly one `@Id` field — or one `@EmbeddedId` composite key — must be present.
- A no-arg constructor is required (also on the `@Embeddable` key type for `@EmbeddedId`).
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

## Composite keys (`@EmbeddedId`)

When the primary key spans several columns, model it as an `@Embeddable` key type and reference it with `@EmbeddedId`:

```java
@Embeddable
public class OrderLineId {
    @Column(name = "order_id") private Long orderId;
    @Column(name = "line_no")  private Integer lineNo;
    // no-arg constructor + equals/hashCode
}

@Entity
@Table(name = "order_line")
public class OrderLine {
    @EmbeddedId
    private OrderLineId id;
    private Integer quantity;
}
```

- The key columns flatten into the entity table **without** a host-field prefix (`order_id`, `line_no`) — unlike `@Embedded`. Use `@AttributeOverride` on the `@EmbeddedId` field to rename a component column.
- DDL emits a table-level `primary key (order_id, line_no)` constraint instead of a per-column `primary key`.
- `findById` / `deleteById` take the key instance: `findById(OrderLine.class, new OrderLineId(100L, 1))`. The `WHERE` clause expands to `order_id = ? and line_no = ?`.
- Composite keys are **application-assigned** (`@GeneratedValue` on a component is rejected). Because the key is always populated, `save()` cannot use the id-null "is new" heuristic; it performs a JPA-`merge`-style existence check (one `SELECT`) to decide insert vs. update. Single-`@Id` entities keep the zero-overhead path.
- Not yet supported with composite keys: `@SoftDelete`, batch delete-by-ids, and use as a `@ManyToOne` / `@OneToOne` target — these are rejected fail-fast rather than emitting wrong SQL.

### `@IdClass` — the alternative form

`@IdClass` models the same composite key as several top-level `@Id` fields plus a separate mirror class, instead of an embedded value type:

```java
public class BookId {           // mirror class — plain class, no-arg ctor + equals/hashCode
    private Long publisherId;
    private String isbn;
}

@Entity
@Table(name = "book")
@IdClass(BookId.class)
public class Book {
    @Id @Column(name = "publisher_id") private Long publisherId;
    @Id private String isbn;
    private String title;
}
```

- The id class must declare a field with the **same name and type** as each `@Id`, plus a no-arg constructor — both are validated fail-fast at metadata build time. `@IdClass` and `@EmbeddedId` cannot be combined.
- `findById` / `deleteById` take an id-class instance: `findById(Book.class, new BookId(7L, "978-1"))`. Insert/update/DDL and the existence-check `save()` behave exactly as for `@EmbeddedId`.

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

### `@OneToOne`

```java
@Entity @Table(name = "person")
public class Person {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @OneToOne
    @JoinColumn(name = "passport_id")     // owning side — unique FK column
    private Passport passport;
}

@Entity @Table(name = "passport")
public class Passport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @OneToOne(mappedBy = "passport")      // inverse side — no column
    private Person holder;
}
```

- The **owning** side (the one with `@JoinColumn` / no `mappedBy`) carries a unique FK column and
  is hydrated exactly like `@ManyToOne` — a single IN query, FK bound from the referenced `@Id`.
- The **inverse** side (`mappedBy`) has no column; `findById` / `findAll` hydrate the single owner
  by querying the owning table's FK. As with `@OneToMany`, declare `targetEntity` when the type
  cannot be inferred, and the inverse field is excluded from column mapping automatically.
- `@OneToOne(fetch = LAZY)` and `cascade` are rejected fail-fast (no lazy proxy / no cascade graph).

---

### `@ManyToMany`

Many-to-many associations map through a **link table**. Declare the owning side with `@JoinTable` and the inverse side with `mappedBy`:

```java
@Entity @Table(name = "student")
class Student {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;

    @ManyToMany
    @JoinTable(name = "student_course",
            joinColumns = @JoinColumn(name = "student_id"),
            inverseJoinColumns = @JoinColumn(name = "course_id"))
    Set<Course> courses = new LinkedHashSet<>();
}

@Entity @Table(name = "course")
class Course {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;

    @ManyToMany(mappedBy = "courses")
    Set<Student> students = new LinkedHashSet<>();
}
```

- **Link table** — auto-created by `SchemaInitializer.create(...)` as `(owner_fk, target_fk)` with a composite primary key. When `@JoinTable` (or its columns) is omitted, JPA default names apply (`owner_table_target_table`, `entity_id`). `List` and `Set` are both supported.
- **Write (full-replace)** — `save(owner)` reconciles the owner's link rows: it deletes them and re-inserts the current collection, eagerly within the surrounding transaction. Targets must already be persisted (non-null id) — persist them first, like other relations. A `null` collection is left untouched; an empty collection clears all links. This yields the same end state as JPA for both adds and removes.
- **Read (2-hop, no N+1)** — `findById` / `findAll` hydrate the collection on **both** owning and inverse sides with two IN-queries per association (the link table, then the targets). Declare `targetEntity` when the element type can't be inferred from a raw collection.
- `cascade` is rejected fail-fast. v1 supports single-keyed owner/target only (composite-keyed `@ManyToMany` is rejected); `@OrderBy` on `@ManyToMany`, link cleanup on owner delete, and session collection-diff are deferred.

---

### `@ElementCollection`

A collection of basic-typed values mapped to a side table (no separate entity):

```java
@Entity @Table(name = "person")
class Person {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;

    @ElementCollection
    List<String> tags = new ArrayList<>();   // → person_tags (person_id, tags)
}
```

- **Collection table** — auto-created as `(owner_fk, value)`. Defaults follow JPA (`owner_table_attribute`, `entity_id`, `attribute`); override with `@CollectionTable(name=…, joinColumns=@JoinColumn(name=…))` and `@Column(name=…)` for the value column. `List` and `Set` are supported. No composite PK is emitted, so duplicate values in a `List` are allowed.
- **Write / read** — like `@ManyToMany`, `save(owner)` reconciles the rows by full-replace (delete + re-insert the current values, eagerly within the transaction); `findById` / `findAll` hydrate with a single IN-query (the values are inline — no second hop).
- v1 supports **basic element types** (`String`, `Integer`, `Long`, `Boolean`, `Double`, …). `@Embeddable` element collections are rejected fail-fast and deferred.

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

## Entity listeners

Beyond the in-entity callbacks (`@PrePersist`, `@PostLoad`, …, which are `void`, no-arg
methods on the entity itself), Nova supports **external listener classes** registered with
`@EntityListeners`. A listener is a plain class whose methods carry the same lifecycle
annotations, but each takes the **entity as a single argument**:

```java
public class AuditListener {
    @PrePersist
    public void stamp(Document document) {   // one argument — the entity being persisted
        document.setCreatedBy(currentUser());
    }
}

@Entity
@Table(name = "document")
@EntityListeners(AuditListener.class)        // one or more listener classes
public class Document {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @PrePersist
    void onPrePersist() { /* the entity's own callback */ }
}
```

Rules, matching JPA:

- The listener class needs an **accessible no-arg constructor**; Nova instantiates it **once**
  when the entity metadata is built and reuses that instance (treat listeners as stateless).
- A listener callback method takes **exactly one argument** assignable from the entity type
  (`Document`, a supertype, or `Object`) and returns `void`. Wrong arity, a non-assignable
  parameter, a non-`void` return, or a `static` method is rejected **fail-fast** at metadata build.
- **Ordering:** for a given phase, listener callbacks fire **before** the entity's own
  callback. Multiple classes in `@EntityListeners({A.class, B.class})` fire in declaration order,
  and listeners declared on a `@MappedSuperclass` / superclass `@Entity` fire before subclass listeners.
- All seven phases are supported (`@PrePersist`, `@PostPersist`, `@PreUpdate`, `@PostUpdate`,
  `@PostLoad`, `@PreRemove`, `@PostRemove`). A checked exception thrown from a listener is
  rewrapped as `IllegalStateException` preserving the original cause, exactly like in-entity callbacks.

> Listener *inheritance* (lifecycle methods inherited by a listener class from its own
> superclass) and `@ExcludeDefaultListeners` / `@ExcludeSuperclassListeners` are not supported;
> declare callbacks directly on each registered listener class.

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
| `@ManyToOne(fetch = LAZY)` / `@OneToOne(fetch = LAZY)` | No lazy proxy. Relations are fetched eagerly with a single IN-query, or explicitly via `FetchGroup`. The JPA default `EAGER` is honored. |
| `@ManyToOne(cascade = ...)` / `@OneToMany(cascade = ...)` / `@OneToOne(cascade = ...)` | No persistence-context graph; persist related entities explicitly with `save` / `saveAll`. |
| `@OneToMany(orphanRemoval = true)` | No dirty-tracking; delete children explicitly. |
| `@Column(table = ...)` | Secondary tables are not supported. |
| `@GeneratedValue(strategy = TABLE)` | Use `IDENTITY`, `SEQUENCE`, `UUID`, or `AUTO`. |
| `@Inheritance(strategy = JOINED \| TABLE_PER_CLASS)` | Only `SINGLE_TABLE` is supported. |

`@OneToMany`'s default `fetch = LAZY` is the one exception: it is treated as eager (Nova's only mode) rather than rejected, since rejecting the default would reject every collection.
