package io.nova.sql;

import io.nova.metadata.CollectionTableDefinition;
import io.nova.metadata.EntityMetadata;
import io.nova.metadata.ForeignKeyDefinition;
import io.nova.metadata.InheritanceLayout;
import io.nova.metadata.JoinTableDefinition;
import io.nova.metadata.PersistentProperty;
import io.nova.metadata.SecondaryTableInfo;
import io.nova.metadata.TableGeneratorInfo;

import java.util.List;

public interface SchemaGenerator {
    String createTable(EntityMetadata<?> metadata);

    /**
     * {@code @Inheritance(JOINED)} 루트 테이블 DDL을 만든다 — 공통(루트 테이블) 컬럼 + discriminator 컬럼.
     * 서브타입 테이블은 {@link #createJoinedSubtypeTable(InheritanceLayout, InheritanceLayout.ConcreteSubtype, boolean)}이
     * 만든다. 기본 구현은 미지원이며 dialect base({@link AbstractSchemaGenerator})가 override 한다.
     */
    default String createJoinedRootTable(InheritanceLayout layout, boolean ifNotExists) {
        throw new UnsupportedOperationException("createJoinedRootTable is not supported by this SchemaGenerator");
    }

    /**
     * {@code @Inheritance(JOINED)} 서브타입 테이블 DDL을 만든다 — 루트 PK를 FK PK로 공유하는 id 컬럼 +
     * 서브타입 자기 컬럼. 기본 구현은 미지원이며 dialect base가 override 한다.
     */
    default String createJoinedSubtypeTable(
            InheritanceLayout layout, InheritanceLayout.ConcreteSubtype subtype, boolean ifNotExists) {
        throw new UnsupportedOperationException("createJoinedSubtypeTable is not supported by this SchemaGenerator");
    }

    /**
     * {@code @GeneratedValue(TABLE)} generator 테이블을 만드는 {@code CREATE TABLE} 구문을 반환한다.
     * (pkColumn varchar pk, valueColumn bigint) 두 컬럼을 가지며, 식별자 발급 카운터를 보관한다. 여러 entity가
     * 같은 generator 테이블을 공유할 수 있으므로 호출자는 tableName으로 dedupe 한다. 기본 구현은 미지원이며
     * dialect base({@link AbstractSchemaGenerator})가 override 한다.
     */
    default String createTableGenerator(TableGeneratorInfo info) {
        throw new UnsupportedOperationException("createTableGenerator is not supported by this SchemaGenerator");
    }

    /**
     * {@link #createTableGenerator(TableGeneratorInfo)}의 idempotent 변형.
     */
    default String createTableGeneratorIfNotExists(TableGeneratorInfo info) {
        return createTableGenerator(info).replaceFirst("(?i)^create table\\s+", "create table if not exists ");
    }

    /**
     * generator 테이블에서 특정 generator 행을 {@code initialValue}에 맞춰 seed 하는 INSERT 구문을 반환한다.
     * 증가-후-읽기 모델에 맞춰 첫 발급 식별자가 정확히 {@code initialValue}가 되도록 seed 값을 계산한다.
     * 기본 구현은 미지원이며 dialect base가 override 한다.
     */
    default String seedTableGenerator(TableGeneratorInfo info) {
        throw new UnsupportedOperationException("seedTableGenerator is not supported by this SchemaGenerator");
    }

    /**
     * generator 테이블을 제거하는 idempotent {@code DROP TABLE IF EXISTS} 구문.
     */
    default String dropTableGeneratorIfExists(String generatorTableName) {
        return "drop table if exists " + generatorTableName;
    }

    /**
     * {@code @ManyToMany} link table을 만드는 {@code CREATE TABLE} 구문을 반환한다. 두 FK 컬럼(owner/target)과
     * 그 둘로 구성된 복합 PK를 가진다. dialect-aware 구현({@link AbstractSchemaGenerator})이 컬럼 타입을
     * 결정하고 식별자를 quote 한다. 기본 구현은 미지원이므로 dialect base가 override 해야 한다.
     */
    default String createJoinTable(JoinTableDefinition definition) {
        throw new UnsupportedOperationException("createJoinTable is not supported by this SchemaGenerator");
    }

    /**
     * {@link #createJoinTable(JoinTableDefinition)}의 idempotent 변형. 기본은 prefix를 재작성한다.
     */
    default String createJoinTableIfNotExists(JoinTableDefinition definition) {
        return createJoinTable(definition).replaceFirst("(?i)^create table\\s+", "create table if not exists ");
    }

    /**
     * link table을 제거하는 {@code DROP TABLE} 구문. 기본은 raw 이름을 쓰며 dialect base가 quote 한다.
     */
    default String dropJoinTable(String joinTableName) {
        return "drop table " + joinTableName;
    }

    /**
     * link table을 제거하는 idempotent {@code DROP TABLE IF EXISTS} 구문.
     */
    default String dropJoinTableIfExists(String joinTableName) {
        return "drop table if exists " + joinTableName;
    }

    /**
     * {@code @ElementCollection} collection table을 만드는 {@code CREATE TABLE} 구문. (owner FK, value) 컬럼을
     * 가지며 복합 PK는 없다(같은 owner가 같은 값을 List에서 중복 보유할 수 있으므로). dialect base가 override 한다.
     */
    default String createCollectionTable(CollectionTableDefinition definition) {
        throw new UnsupportedOperationException("createCollectionTable is not supported by this SchemaGenerator");
    }

    default String createCollectionTableIfNotExists(CollectionTableDefinition definition) {
        return createCollectionTable(definition).replaceFirst("(?i)^create table\\s+", "create table if not exists ");
    }

    /**
     * 테이블 생성과 별개로 발행할 secondary index 및 unique constraint DDL을 반환한다.
     * 기본 구현은 빈 리스트를 반환하므로, 구현체가 별도로 override 해야 실제 SQL이 만들어진다.
     */
    default List<String> createIndexes(EntityMetadata<?> metadata) {
        return List.of();
    }

    /**
     * Returns an idempotent {@code CREATE TABLE} statement that no-ops if the
     * table already exists. The default implementation rewrites the prefix of
     * {@link #createTable(EntityMetadata)} so any existing override is honored
     * verbatim. Dialects that need a different syntax (Oracle, which lacks
     * {@code IF NOT EXISTS} on {@code CREATE TABLE}) should override.
     */
    default String createTableIfNotExists(EntityMetadata<?> metadata) {
        String ddl = createTable(metadata);
        return ddl.replaceFirst("(?i)^create table\\s+", "create table if not exists ");
    }

    /**
     * Returns a {@code DROP TABLE} statement that fails if the table does not
     * exist. Dialect-aware implementations (see {@link AbstractSchemaGenerator})
     * quote the table identifier; the default is a best-effort fallback that
     * uses the raw {@code metadata.tableName()}.
     */
    default String dropTable(EntityMetadata<?> metadata) {
        return "drop table " + metadata.tableName();
    }

    /**
     * Returns an idempotent {@code DROP TABLE} statement that no-ops if the
     * table does not exist. Dialects without {@code IF EXISTS} support (Oracle)
     * should override with the equivalent error-swallowing block.
     */
    default String dropTableIfExists(EntityMetadata<?> metadata) {
        return "drop table if exists " + metadata.tableName();
    }

    /**
     * 기존 테이블에 컬럼을 추가하는 {@code ALTER TABLE ... ADD COLUMN} 구문을 반환한다.
     * 기본 구현은 미지원으로 처리하므로 dialect가 직접 override 해야 한다.
     */
    default String alterTableAddColumn(EntityMetadata<?> metadata, PersistentProperty newColumn) {
        throw new UnsupportedOperationException(
                "alterTableAddColumn is not supported by this SchemaGenerator");
    }

    /**
     * 기존 테이블에서 컬럼을 제거하는 {@code ALTER TABLE ... DROP COLUMN} 구문을 반환한다.
     * 기본 구현은 미지원으로 처리하므로 dialect가 직접 override 해야 한다.
     */
    default String alterTableDropColumn(EntityMetadata<?> metadata, String columnName) {
        throw new UnsupportedOperationException(
                "alterTableDropColumn is not supported by this SchemaGenerator");
    }

    /**
     * {@code @OneToMany(mappedBy)} + {@code @OrderColumn}의 순서 정수 컬럼을 child 테이블에 추가하는
     * {@code ALTER TABLE ... ADD COLUMN ... integer} 구문을 반환한다. 순서 컬럼은 child 엔티티의 필드가 아니라
     * parent의 {@code @OneToMany} 매핑이 소유하므로 child 테이블 생성 후 별도 ALTER로 더한다.
     * <p>기본 구현은 미지원으로 처리하므로 dialect 베이스({@link AbstractSchemaGenerator})가 override 한다.
     */
    default String addOneToManyOrderColumn(EntityMetadata<?> childMetadata, String orderColumnName) {
        throw new UnsupportedOperationException(
                "addOneToManyOrderColumn is not supported by this SchemaGenerator");
    }

    /**
     * 외래키(FOREIGN KEY) 제약을 추가하는 {@code ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY (...)
     * REFERENCES ...} 구문을 반환한다 — JPA {@code @ForeignKey(ConstraintMode.CONSTRAINT)} 소스 호환의
     * emission 지점이다. 모든 테이블이 만들어진 뒤 별도 phase로 발행되므로 forward reference(자식이 부모보다
     * 먼저 생성된 경우)도 안전하다. 표준 ANSI FK 문법은 dialect 무관이므로 식별자 quoting만 dialect가 담당한다
     * ({@link AbstractSchemaGenerator}가 {@link Dialect#quote(String)}로 구현). FK 제약을 지원하지 않는
     * 커스텀 generator는 기본 구현대로 명시적으로 실패한다.
     */
    default String addForeignKey(ForeignKeyDefinition definition) {
        throw new UnsupportedOperationException(
                "addForeignKey is not supported by this SchemaGenerator");
    }

    /**
     * {@link #addForeignKey(ForeignKeyDefinition)}가 실제로 발행할 제약 이름을 반환한다 — 명시적 이름이 있으면
     * 그대로, 없으면 결정적 자동 이름. 멱등 발행({@code ddl-auto=UPDATE} 재시작) 시 이미 존재하는 제약을
     * 거르는 데 쓰이므로 {@code addForeignKey}와 반드시 같은 이름을 돌려줘야 한다. 기본 구현은 명시적 이름만
     * 알 수 있으므로 자동 이름을 쓰는 generator({@link AbstractSchemaGenerator})는 override 한다.
     */
    default String foreignKeyName(ForeignKeyDefinition definition) {
        return definition.constraintName();
    }

    /**
     * {@code @SecondaryTable} 보조 테이블 DDL을 만든다 — primary PK를 FK PK로 공유하는 조인 컬럼 +
     * 그 보조 테이블로 라우팅된 컬럼들. 기본 구현은 미지원이며 dialect base({@link AbstractSchemaGenerator})가
     * override 한다.
     */
    default String createSecondaryTable(EntityMetadata<?> metadata, SecondaryTableInfo secondaryTable) {
        throw new UnsupportedOperationException("createSecondaryTable is not supported by this SchemaGenerator");
    }

    /**
     * {@link #createSecondaryTable(EntityMetadata, SecondaryTableInfo)}의 idempotent 변형.
     */
    default String createSecondaryTableIfNotExists(EntityMetadata<?> metadata, SecondaryTableInfo secondaryTable) {
        return createSecondaryTable(metadata, secondaryTable)
                .replaceFirst("(?i)^create table\\s+", "create table if not exists ");
    }

    /**
     * 보조 테이블을 제거하는 {@code DROP TABLE} 구문. 기본은 raw 이름을 쓰며 dialect base가 quote 한다.
     */
    default String dropSecondaryTable(SecondaryTableInfo secondaryTable) {
        return "drop table " + secondaryTable.tableName();
    }

    /**
     * 보조 테이블을 제거하는 idempotent {@code DROP TABLE IF EXISTS} 구문.
     */
    default String dropSecondaryTableIfExists(SecondaryTableInfo secondaryTable) {
        return "drop table if exists " + secondaryTable.tableName();
    }
}
