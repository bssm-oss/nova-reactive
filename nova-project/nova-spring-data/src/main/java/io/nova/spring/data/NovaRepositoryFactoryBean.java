package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.sql.Dialect;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 단일 {@link ReactiveCrudRepository} 인터페이스에 대한 JDK proxy를 생성해 Spring 컨테이너에
 * 노출하는 {@link FactoryBean}이다. {@link NovaRepositoriesRegistrar}가 스캔 결과를 등록할 때
 * 각 repository 인터페이스마다 한 개씩 만든다.
 *
 * <p>{@link ReactiveEntityOperations}는 setter로 주입되며 — Spring runtime bean reference 또는
 * 단위 테스트가 직접 주입할 수 있도록 — proxy는 {@link InitializingBean#afterPropertiesSet()}에서
 * 한 번만 만들어 캐싱된다.
 */
public final class NovaRepositoryFactoryBean implements FactoryBean<Object>, InitializingBean {
    private final Class<?> repositoryInterface;
    private ReactiveEntityOperations entityOperations;
    private Dialect dialect;
    private EntityMetadataFactory entityMetadataFactory;
    private List<Class<?>> jpqlEntities = new ArrayList<>();
    private Object proxy;

    public NovaRepositoryFactoryBean(Class<?> repositoryInterface) {
        this.repositoryInterface = Objects.requireNonNull(repositoryInterface, "repositoryInterface");
        if (!repositoryInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "repositoryInterface must be an interface: " + repositoryInterface.getName());
        }
    }

    /**
     * {@link ReactiveEntityOperations}를 주입한다. Spring 컨테이너가 빈 reference로 호출하거나
     * 단위 테스트가 fake를 직접 넘긴다.
     */
    public void setEntityOperations(ReactiveEntityOperations entityOperations) {
        this.entityOperations = entityOperations;
    }

    /**
     * (선택) {@code @Query} bind marker 렌더링 및 JPQL 실행에 사용할 {@link Dialect}를 주입한다. native
     * {@code @Query}에 필요하며, {@link EntityMetadataFactory}와 함께 주어지면 JPQL {@code @Query}용
     * {@link JpqlExecutor}가 구성된다. 주입하지 않으면 {@code @Query} 경로만 fail-fast하고 나머지
     * repository 동작은 그대로 유지된다.
     *
     * @param dialect bind marker 렌더링/JPQL 실행에 사용할 dialect
     */
    public void setDialect(Dialect dialect) {
        this.dialect = dialect;
    }

    /**
     * (선택) JPQL {@code @Query} 실행에 사용할 {@link EntityMetadataFactory}를 주입한다.
     * {@link Dialect}와 함께 주어질 때만 {@link JpqlExecutor}가 구성된다.
     *
     * @param entityMetadataFactory JPQL 실행에 사용할 메타데이터 팩토리
     */
    public void setEntityMetadataFactory(EntityMetadataFactory entityMetadataFactory) {
        this.entityMetadataFactory = entityMetadataFactory;
    }

    /**
     * (선택) JPQL {@code @Query}가 이름으로 참조할 수 있는 추가 엔티티 클래스들을 등록한다. repository의
     * 엔티티 타입은 항상 자동 등록되므로, JOIN 등으로 다른 엔티티를 참조할 때만 지정하면 된다.
     *
     * @param jpqlEntities JPQL이 이름으로 참조할 추가 엔티티 클래스들(nullable)
     */
    public void setJpqlEntities(List<Class<?>> jpqlEntities) {
        this.jpqlEntities = jpqlEntities == null ? new ArrayList<>() : new ArrayList<>(jpqlEntities);
    }

    @Override
    public void afterPropertiesSet() {
        if (entityOperations == null) {
            throw new IllegalStateException(
                    "ReactiveEntityOperations must be set before initializing " + repositoryInterface.getName());
        }
        if (proxy != null) {
            return;
        }
        RepositoryMetadata metadata = RepositoryMetadata.resolve(repositoryInterface);
        JpqlExecutor jpqlExecutor = buildJpqlExecutor(metadata.entityType());
        SimpleReactiveRepository handler = new SimpleReactiveRepository(
                metadata.entityType(), metadata.idType(), entityOperations, jpqlExecutor, dialect);
        ClassLoader classLoader = repositoryInterface.getClassLoader();
        this.proxy = Proxy.newProxyInstance(classLoader, new Class<?>[]{repositoryInterface}, handler);
    }

    /**
     * {@link Dialect}와 {@link EntityMetadataFactory}가 모두 주입된 경우에만 JPQL {@code @Query}용
     * {@link JpqlExecutor}를 구성한다. 엔티티 등록 셋은 repository 엔티티 타입 + 추가 등록 클래스다.
     * 하나라도 없으면 {@code null}을 반환하고, JPQL {@code @Query} 호출 시점에 명확한 예외로 fail-fast한다.
     */
    private JpqlExecutor buildJpqlExecutor(Class<?> entityType) {
        if (dialect == null || entityMetadataFactory == null) {
            return null;
        }
        Set<Class<?>> entities = new LinkedHashSet<>();
        entities.add(entityType);
        entities.addAll(jpqlEntities);
        return new JpqlExecutor(entityOperations, dialect, entityMetadataFactory, new ArrayList<>(entities));
    }

    @Override
    public Object getObject() {
        if (proxy == null) {
            // lazy fallback: afterPropertiesSet이 호출되지 않은 경우에도 동일한 invariant로 생성.
            afterPropertiesSet();
        }
        return proxy;
    }

    @Override
    public Class<?> getObjectType() {
        return repositoryInterface;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
