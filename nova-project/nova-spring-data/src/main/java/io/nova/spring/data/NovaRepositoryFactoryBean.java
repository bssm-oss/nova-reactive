package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;

import java.lang.reflect.Proxy;
import java.util.Objects;

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
        SimpleReactiveRepository handler = new SimpleReactiveRepository(
                metadata.entityType(), metadata.idType(), entityOperations);
        ClassLoader classLoader = repositoryInterface.getClassLoader();
        this.proxy = Proxy.newProxyInstance(classLoader, new Class<?>[]{repositoryInterface}, handler);
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
