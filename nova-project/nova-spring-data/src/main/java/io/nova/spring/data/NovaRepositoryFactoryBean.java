package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import io.nova.metadata.EntityMetadataFactory;
import io.nova.query.jpql.JpqlExecutor;
import io.nova.sql.Dialect;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 단일 {@link ReactiveCrudRepository} 인터페이스에 대한 JDK proxy를 생성해 Spring 컨테이너에
 * 노출하는 {@link FactoryBean}이다. {@link NovaRepositoriesRegistrar}가 스캔 결과를 등록할 때
 * 각 repository 인터페이스마다 한 개씩 만든다.
 *
 * <p>{@link ReactiveEntityOperations}는 setter로 주입되며 — Spring runtime bean reference 또는
 * 단위 테스트가 직접 주입할 수 있도록 — proxy는 {@link InitializingBean#afterPropertiesSet()}에서
 * 한 번만 만들어 캐싱된다.
 */
public final class NovaRepositoryFactoryBean
        implements FactoryBean<Object>, InitializingBean, BeanFactoryAware {
    private final Class<?> repositoryInterface;
    private ReactiveEntityOperations entityOperations;
    private Dialect dialect;
    private EntityMetadataFactory entityMetadataFactory;
    private List<Class<?>> jpqlEntities = new ArrayList<>();
    private BeanFactory beanFactory;
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
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
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
        resolveOptionalBeansByType();
        RepositoryMetadata metadata = RepositoryMetadata.resolve(repositoryInterface);
        Supplier<JpqlExecutor> jpqlExecutorSupplier = jpqlExecutorSupplier(metadata.entityType());
        SimpleReactiveRepository handler = new SimpleReactiveRepository(
                metadata.entityType(), metadata.idType(), entityOperations, jpqlExecutorSupplier, dialect);
        ClassLoader classLoader = repositoryInterface.getClassLoader();
        this.proxy = Proxy.newProxyInstance(classLoader, new Class<?>[]{repositoryInterface}, handler);
    }

    /**
     * JPQL {@code @Query}용 {@link JpqlExecutor}를 <b>lazy</b>하게 만드는 supplier를 반환한다. supplier는
     * 첫 JPQL {@code @Query} 실행 시점에만 호출되므로, 스캔되었지만 JPQL {@code @Query}를 실제로 쓰지
     * 않는 repository(특히 엔티티가 {@code @Entity}가 아닌 경우)의 부팅을 깨지 않는다 — {@link JpqlExecutor}
     * 생성이 등록 엔티티 메타데이터를 eager 해석하기 때문이다.
     *
     * <p>JPQL {@code @Query} 메서드가 없거나 {@link Dialect}/{@link EntityMetadataFactory}가 없으면
     * {@code null}을 반환하고, JPQL {@code @Query} 호출 시점에 명확한 예외로 fail-fast한다.
     */
    private Supplier<JpqlExecutor> jpqlExecutorSupplier(Class<?> entityType) {
        if (!declaresJpqlQuery() || dialect == null || entityMetadataFactory == null) {
            return null;
        }
        Set<Class<?>> entities = new LinkedHashSet<>();
        entities.add(entityType);
        entities.addAll(jpqlEntities);
        List<Class<?>> registered = new ArrayList<>(entities);
        Dialect resolvedDialect = dialect;
        EntityMetadataFactory resolvedFactory = entityMetadataFactory;
        ReactiveEntityOperations ops = entityOperations;
        return () -> new JpqlExecutor(ops, resolvedDialect, resolvedFactory, registered);
    }

    /** repository가 JPQL(비-native) {@code @Query} 메서드를 하나라도 선언하는지. */
    private boolean declaresJpqlQuery() {
        for (java.lang.reflect.Method method : repositoryInterface.getMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query != null && !query.nativeQuery()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 명시적으로 주입되지 않은 {@link Dialect}/{@link EntityMetadataFactory}를 컨테이너에서 <b>타입 기준</b>
     * 유일 후보로 해석한다({@code @EnableNovaRepositories} 경로에서 {@code @Query}가 별도 설정 없이도
     * 동작하도록). 후보가 0개이거나 2개 이상이면 {@code null}로 남겨 optionality를 보존한다 — 이 경우
     * {@code @Query} 호출 시점에만 명확한 예외로 fail-fast하고, 비-{@code @Query} repository 동작은
     * 그대로 유지된다. 명시 ref가 필요하면 {@code @EnableNovaRepositories.dialectRef}/
     * {@code entityMetadataFactoryRef}로 지정한다.
     */
    private void resolveOptionalBeansByType() {
        if (beanFactory == null) {
            return;
        }
        if (dialect == null) {
            dialect = beanFactory.getBeanProvider(Dialect.class).getIfUnique();
        }
        if (entityMetadataFactory == null) {
            entityMetadataFactory = beanFactory.getBeanProvider(EntityMetadataFactory.class).getIfUnique();
        }
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
