<!-- SPDX-License-Identifier: Apache-2.0 -->

# Metamodel — type-safe property names

`nova-metamodel` is an opt-in annotation processor that scans `@Entity` classes
at compile time and generates a JPA-style `_` companion class per entity. The
processor is published as Java 17-compatible bytecode and can be used from
Java 17, 21, 25, or 26 projects. Each
companion exposes the entity's persistent properties as `public static final
String` constants whose values match the property names accepted by
`io.nova.query.Criteria`.

A typo is caught at compile time, but the rest of the query API is unchanged —
this is a thin safety layer over the existing string-based `Criteria` DSL, not a
full QueryDSL-style typed-path DSL. Typed paths are tracked separately as a
future module.

## Install

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.bssm-oss:nova:2.30.0")

    annotationProcessor("io.github.bssm-oss:nova-metamodel:2.30.0")
}
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.github.bssm-oss</groupId>
    <artifactId>nova</artifactId>
    <version>2.30.0</version>
</dependency>

<plugin>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.bssm-oss</groupId>
                <artifactId>nova-metamodel</artifactId>
                <version>2.30.0</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

The processor has no runtime dependency on Nova — generated companions are
plain `String` constants.

## Usage

Given:

```java
@Entity
public class Author {
    @Id
    private Long id;

    @Column
    private String email;

    private boolean active;
}
```

The processor emits `Author_`:

```java
@Generated("io.nova.metamodel.MetamodelProcessor")
public final class Author_ {
    public static final String id = "id";
    public static final String email = "email";
    public static final String active = "active";
}
```

Use it in any `Criteria` call:

```java
import static io.nova.query.Criteria.eq;

Mono<Author> first = operations.findAll(
        Author.class,
        QuerySpec.empty().where(eq(Author_.email, "x@nova.io")))
    .next();
```

If someone renames `email` to `emailAddress`, every call site fails to compile
instead of failing at runtime when `EntityMetadata` cannot resolve the property.

## Embedded composites

`@Embedded` host fields are flattened to dotted property paths — exactly what
Nova's runtime metadata produces. Since Java identifiers cannot contain `.`,
the generated field uses an underscore-joined safe identifier; the value is the
dotted path.

```java
@Entity
public class Customer {
    @Id
    private Long id;

    @Embedded
    private Address address;
}

@Embeddable
public class Address {
    private String street;
    private String city;
}
```

An otherwise-unmapped FIELD or PROPERTY whose declared type is `@Embeddable` is treated as
embedded even without `@Embedded`; generated constants still use the same dotted leaf paths.

```java
@Generated("io.nova.metamodel.MetamodelProcessor")
public final class Customer_ {
    public static final String id = "id";
    public static final String address_street = "address.street";  // value: dotted path
    public static final String address_city   = "address.city";
}

// at the call site:
eq(Customer_.address_city, "Seoul");
```

Nested `@Embedded` chains continue the pattern: `outer.middle.leaf` →
`outer_middle_leaf`.

## Selection rules

The processor mirrors `io.nova.metadata.EntityMetadataFactory` exactly — the
same field is either a column in both worlds, or in neither:

| Field shape | Emitted? |
|---|---|
| Regular field, `@Id`, `@Version`, `@CreatedAt`, `@UpdatedAt`, `@SoftDelete`, `@Json`, `@Enumerated`, `@Column` | yes |
| `@ManyToOne`, owning `@OneToOne` (`mappedBy` blank; FK column) | yes |
| `@Embedded` host | no — recursed into |
| `@OneToMany`, `@ManyToMany`, `@ElementCollection` | no — values are stored outside the entity table |
| Inverse `@OneToOne` (`mappedBy` nonblank) | no — the FK belongs to the owning side |
| `static`, `transient`, synthetic | no |

Identifier collisions (for example, an `@Embedded host` named `address` plus a
peer field literally named `address_city`) are reported as a compile-time
ERROR diagnostic. `@Embedded` cycles abort with an explicit error rather than
recursing forever.

## Limitations

- The generated constants are plain strings — there is no typed comparison
  (`StringPath#like`, `NumberPath#gt`, etc.). For that, wait for the typed-DSL
  module on the roadmap.
- Renaming a field still requires re-running the compiler before call sites
  resolve to the new constant. IDEs that re-run the processor on save (IntelliJ
  with annotation processing enabled) catch this immediately.
- Generated sources live under `build/generated/sources/annotationProcessor/`
  for Gradle, and `target/generated-sources/annotations/` for Maven. They
  should not be checked in.
