package io.nova.fetch;

import io.nova.query.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 관계 어노테이션 없이도 호출자가 명시적으로 batch child fetch를 선언할 수 있게 하는 DSL이다.
 * <p>
 * parent 엔티티 타입에 묶인 여러 child spec을 누적해 immutable한 {@link FetchGroup}을 만든다.
 * 실행 단계는 {@link io.nova.core.ReactiveEntityOperations#findById(Class, Object, FetchGroup)}
 * 또는 {@link io.nova.core.ReactiveEntityOperations#findAll(Class, FetchGroup)}이 담당하며,
 * spec 하나당 child IN-query 한 번으로 묶여 N+1을 회피한다.
 * <p>
 * 사용 패턴 — annotation 기반 매핑이 부담스럽거나 ad-hoc fetch가 필요할 때:
 * <pre>{@code
 * FetchGroup<Author> group = FetchGroup.forParents(Author.class)
 *         .with(Book.class, "author_id", Author::getId, Author::setBooks)
 *         .build();
 * operations.findAll(Author.class, group);
 * }</pre>
 * <p>
 * child 타입은 entity로 매핑 가능해야 하며, parent 측의 collection 필드는
 * {@code transient} 키워드로 선언해 컬럼 매핑에서 제외한다.
 */
public final class FetchGroup<P> {
    private final Class<P> parentType;
    private final List<FetchSpec<P, ?>> specs;
    private final List<CompositeToOneSpec<P, ?>> compositeToOneSpecs;

    private FetchGroup(
            Class<P> parentType,
            List<FetchSpec<P, ?>> specs,
            List<CompositeToOneSpec<P, ?>> compositeToOneSpecs) {
        this.parentType = parentType;
        this.specs = List.copyOf(specs);
        this.compositeToOneSpecs = List.copyOf(compositeToOneSpecs);
    }

    /**
     * parent 엔티티 타입을 지정해 새로운 {@link Builder}를 만든다.
     */
    public static <P> Builder<P> forParents(Class<P> parentType) {
        Objects.requireNonNull(parentType, "parentType must not be null");
        return new Builder<>(parentType);
    }

    public Class<P> parentType() {
        return parentType;
    }

    public List<FetchSpec<P, ?>> specs() {
        return specs;
    }

    /**
     * 복합키({@code @EmbeddedId}/{@code @IdClass}) 엔티티를 참조하는 to-one({@code @ManyToOne}/owning
     * {@code @OneToOne}) 배치 로드 spec들. 단일컬럼 IN으로 표현 불가한 다중컬럼 FK를 별도 리스트로 분리해
     * {@link #specs()}(단일컬럼 IN 경로)와 뒤섞이지 않게 한다.
     */
    public List<CompositeToOneSpec<P, ?>> compositeToOneSpecs() {
        return compositeToOneSpecs;
    }

    /**
     * {@link FetchGroup}을 누적해서 만드는 immutable builder. 각 {@link #with(Class, String, Function, BiConsumer)}
     * 호출은 동일 builder 인스턴스에 child spec을 추가하며 마지막에 {@link #build()}로 동결한다.
     */
    public static final class Builder<P> {
        private final Class<P> parentType;
        private final List<FetchSpec<P, ?>> specs = new ArrayList<>();
        private final List<CompositeToOneSpec<P, ?>> compositeToOneSpecs = new ArrayList<>();

        private Builder(Class<P> parentType) {
            this.parentType = parentType;
        }

        /**
         * child fetch spec을 추가한다.
         *
         * @param childType              child 엔티티 타입(@Entity로 매핑 가능해야 함)
         * @param childForeignKeyColumn  child 측 FK 컬럼 이름. parent id와 비교되는 컬럼이며,
         *                               child 메타데이터에서 이 컬럼을 가진 property를 찾아 IN 조건에 쓴다.
         * @param parentIdExtractor      parent 인스턴스에서 비교 키를 꺼내는 함수. 일반적으로 {@code Parent::getId}.
         * @param setter                 같은 parent에 묶인 child 리스트를 주입하는 함수.
         */
        public <C> Builder<P> with(
                Class<C> childType,
                String childForeignKeyColumn,
                Function<P, Object> parentIdExtractor,
                BiConsumer<P, List<C>> setter
        ) {
            return with(childType, childForeignKeyColumn, parentIdExtractor, setter, null);
        }

        /**
         * child 리스트 정렬({@code @OneToMany}의 {@code @OrderBy})을 함께 지정하는 변형. {@code orderBy}가
         * {@code null}이면 정렬 없이 IN-query 결과 순서를 그대로 쓴다.
         */
        public <C> Builder<P> with(
                Class<C> childType,
                String childForeignKeyColumn,
                Function<P, Object> parentIdExtractor,
                BiConsumer<P, List<C>> setter,
                Sort orderBy
        ) {
            return with(childType, childForeignKeyColumn, parentIdExtractor, setter, orderBy, null);
        }

        /**
         * child 리스트 정렬을 {@code @OrderColumn}으로 지정하는 변형 — {@code orderColumn}은 child 테이블의 순서
         * 정수 컬럼 이름이며, hydration 시 그 컬럼 값으로 child를 정렬한다(컬럼이 child 엔티티 property가 아니므로
         * property 기반 {@code orderBy}와는 별개 경로). {@code orderColumn}이 {@code null}이면 정렬 컬럼을 쓰지 않는다.
         */
        public <C> Builder<P> with(
                Class<C> childType,
                String childForeignKeyColumn,
                Function<P, Object> parentIdExtractor,
                BiConsumer<P, List<C>> setter,
                Sort orderBy,
                String orderColumn
        ) {
            Objects.requireNonNull(childType, "childType must not be null");
            Objects.requireNonNull(childForeignKeyColumn, "childForeignKeyColumn must not be null");
            if (childForeignKeyColumn.isBlank()) {
                throw new IllegalArgumentException("childForeignKeyColumn must not be blank");
            }
            Objects.requireNonNull(parentIdExtractor, "parentIdExtractor must not be null");
            Objects.requireNonNull(setter, "setter must not be null");
            specs.add(new FetchSpec<>(
                    childType, childForeignKeyColumn, parentIdExtractor, setter, false, orderBy, orderColumn));
            return this;
        }

        /**
         * parent가 다른 entity를 단건 참조하는 경우(예: {@code @ManyToOne})의 fetch spec을 추가한다.
         * 내부 실행은 list-기반 spec과 동일한 IN-query를 사용하며, 결과 child가 비어 있으면 parent에
         * {@code null}을 주입하고, 매칭되는 child가 한 건이라도 있으면 첫 번째를 주입한다.
         *
         * @param childType                referenced child entity 타입 (target of the ManyToOne)
         * @param childPrimaryKeyColumn    child의 PK 컬럼 이름 — parent 측 FK 값과 비교된다.
         * @param parentForeignKeyExtractor parent 인스턴스에서 FK 값(=child id)을 꺼내는 함수.
         * @param singleSetter             parent에 단건 child 인스턴스를 주입하는 함수.
         */
        public <C> Builder<P> withReferencedParent(
                Class<C> childType,
                String childPrimaryKeyColumn,
                Function<P, Object> parentForeignKeyExtractor,
                BiConsumer<P, C> singleSetter
        ) {
            Objects.requireNonNull(childType, "childType must not be null");
            Objects.requireNonNull(childPrimaryKeyColumn, "childPrimaryKeyColumn must not be null");
            if (childPrimaryKeyColumn.isBlank()) {
                throw new IllegalArgumentException("childPrimaryKeyColumn must not be blank");
            }
            Objects.requireNonNull(parentForeignKeyExtractor, "parentForeignKeyExtractor must not be null");
            Objects.requireNonNull(singleSetter, "singleSetter must not be null");
            // singleSetter를 list 기반 BiConsumer로 adapt — 호출자가 boilerplate를 짜지 않게 한다.
            BiConsumer<P, List<C>> listSetter = (parent, children) ->
                    singleSetter.accept(parent, children == null || children.isEmpty() ? null : children.get(0));
            specs.add(new FetchSpec<>(
                    childType, childPrimaryKeyColumn, parentForeignKeyExtractor, listSetter, true, null, null));
            return this;
        }

        /**
         * inverse-side {@code @OneToOne}({@code mappedBy})의 fetch spec을 추가한다. 소유 측이 FK를 가지므로
         * {@code @OneToMany}와 같은 by-FK IN-query를 쓰되, parent당 child 한 건만 주입한다(있으면 첫 번째).
         *
         * @param childType                 소유 측(반대편) entity 타입
         * @param childForeignKeyColumn     child 측 FK 컬럼 이름(이 parent의 id를 가리킴)
         * @param parentIdExtractor         parent 인스턴스에서 id를 꺼내는 함수
         * @param singleSetter              parent에 단건 child를 주입하는 함수
         */
        public <C> Builder<P> withInverseReference(
                Class<C> childType,
                String childForeignKeyColumn,
                Function<P, Object> parentIdExtractor,
                BiConsumer<P, C> singleSetter
        ) {
            Objects.requireNonNull(childType, "childType must not be null");
            Objects.requireNonNull(childForeignKeyColumn, "childForeignKeyColumn must not be null");
            if (childForeignKeyColumn.isBlank()) {
                throw new IllegalArgumentException("childForeignKeyColumn must not be blank");
            }
            Objects.requireNonNull(parentIdExtractor, "parentIdExtractor must not be null");
            Objects.requireNonNull(singleSetter, "singleSetter must not be null");
            BiConsumer<P, List<C>> listSetter = (parent, children) ->
                    singleSetter.accept(parent, children == null || children.isEmpty() ? null : children.get(0));
            specs.add(new FetchSpec<>(
                    childType, childForeignKeyColumn, parentIdExtractor, listSetter, true, null, null));
            return this;
        }

        /**
         * 복합키({@code @EmbeddedId}/{@code @IdClass}) 엔티티를 참조하는 to-one의 배치 로드 spec을 추가한다.
         * 참조 대상의 다중컬럼 FK는 단일컬럼 IN으로 표현할 수 없으므로, hydration 단계가 참조 대상의 복합 {@code @Id}
         * 컴포넌트별 동등 조건을 AND로 묶고 부모 튜플들을 OR로 확장한 한 번의 쿼리로 배치 로드한다(레벨당 1쿼리, N+1 없음).
         *
         * @param targetType      참조 대상(복합키) 엔티티 타입
         * @param referenceReader parent 인스턴스에서 참조 stub(복합 {@code @Id}가 채워진)을 읽는 함수. 참조가 없으면 {@code null}.
         * @param singleSetter    parent에 완전 로드된 참조 엔티티를 주입하는 함수.
         */
        public <C> Builder<P> withCompositeReferencedParent(
                Class<C> targetType,
                Function<P, Object> referenceReader,
                BiConsumer<P, C> singleSetter
        ) {
            Objects.requireNonNull(targetType, "targetType must not be null");
            Objects.requireNonNull(referenceReader, "referenceReader must not be null");
            Objects.requireNonNull(singleSetter, "singleSetter must not be null");
            compositeToOneSpecs.add(new CompositeToOneSpec<>(targetType, referenceReader, singleSetter));
            return this;
        }

        public FetchGroup<P> build() {
            return new FetchGroup<>(parentType, specs, compositeToOneSpecs);
        }
    }

    /**
     * 단일 child fetch 선언. immutable record로 builder가 누적해두며 실행 단계에서 spec 단위로
     * IN-query 한 번씩 발화된다.
     * <p>
     * {@code single}이 {@code true}이면 parent당 child 한 건이 주입된다 (e.g. {@link Builder#withReferencedParent}).
     * {@code false}이면 parent당 child 리스트가 그대로 주입된다 (e.g. {@link Builder#with}).
     */
    public record FetchSpec<P, C>(
            Class<C> childType,
            String childForeignKeyColumn,
            Function<P, Object> parentIdExtractor,
            BiConsumer<P, List<C>> setter,
            boolean single,
            Sort orderBy,
            String orderColumn
    ) {
        public FetchSpec {
            Objects.requireNonNull(childType, "childType must not be null");
            Objects.requireNonNull(childForeignKeyColumn, "childForeignKeyColumn must not be null");
            Objects.requireNonNull(parentIdExtractor, "parentIdExtractor must not be null");
            Objects.requireNonNull(setter, "setter must not be null");
            // orderBy / orderColumn은 선택값(null 허용). 둘 다 정렬 수단이지만 orderColumn은 child property가
            // 아닌 child 테이블의 물리 순서 컬럼이며 @OrderColumn @OneToMany에서만 채워진다.
        }
    }

    /**
     * 복합키 엔티티를 참조하는 to-one({@code @ManyToOne}/owning {@code @OneToOne}) 배치 로드 선언. 참조 대상의
     * 다중컬럼 FK 때문에 단일컬럼 IN을 쓰는 {@link FetchSpec}으로는 표현할 수 없어 별도 record로 분리한다.
     * <p>
     * {@code referenceReader}는 parent에서 참조 stub(row 디코딩 시 복합 {@code @Id}만 채워진)을 읽는다. 실행 단계는
     * stub의 복합 {@code @Id}로 대상 튜플을 모아 컴포넌트별 동등 조건의 OR 확장 쿼리 한 번으로 완전 엔티티를 로드하고,
     * {@code setter}로 stub을 완전 엔티티로 교체한다. 참조가 {@code null}(옵셔널 관계)인 parent는 그대로 둔다.
     */
    public record CompositeToOneSpec<P, C>(
            Class<C> targetType,
            Function<P, Object> referenceReader,
            BiConsumer<P, C> setter
    ) {
        public CompositeToOneSpec {
            Objects.requireNonNull(targetType, "targetType must not be null");
            Objects.requireNonNull(referenceReader, "referenceReader must not be null");
            Objects.requireNonNull(setter, "setter must not be null");
        }
    }
}
