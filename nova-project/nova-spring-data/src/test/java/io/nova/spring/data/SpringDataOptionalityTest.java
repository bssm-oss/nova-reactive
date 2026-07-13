package io.nova.spring.data;

import io.nova.core.ReactiveEntityOperations;
import io.nova.query.QuerySpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spring-data-commons가 런타임 클래스패스에 <b>없는</b> 상황을 격리 classloader로 재현하여,
 * 순수 {@link ReactiveCrudRepository} 서브인터페이스의 JDK proxy가 정상 생성·사용됨을 검증한다.
 * 이 테스트는 표준 타입 오버로드를 공용 인터페이스가 아니라 opt-in
 * {@link SpringDataReactiveCrudRepository}로 분리한 optionality 계약의 회귀 가드다.
 *
 * <p>{@link SpringHidingClassLoader}는 {@code org.springframework.data.*}를 로드 불가로 막고
 * (=런타임 부재 시뮬레이션), repository 인터페이스들은 child-first로 다시 정의해 그들의 메서드
 * 타입 resolve가 이 loader를 통해 일어나도록 한다. {@link NovaRepositoryFactoryBean}이 하는 것과
 * 동일하게 {@link Proxy#newProxyInstance}로 eager proxy를 만든다.
 */
class SpringDataOptionalityTest {

    @Test
    @DisplayName("spring-data-commons 부재 시 plain ReactiveCrudRepository 서브인터페이스 proxy는 생성·사용 가능")
    void plainRepositoryProxyWorksWithoutSpringDataOnClasspath() throws Exception {
        SpringHidingClassLoader loader = new SpringHidingClassLoader(
                SpringDataOptionalityTest.class.getClassLoader());

        // 전제: 이 loader로는 Spring Data 타입을 절대 로드할 수 없다(런타임 부재 시뮬레이션).
        assertThrows(ClassNotFoundException.class,
                () -> loader.loadClass("org.springframework.data.domain.Pageable"));

        Class<?> plainRepo = loader.loadClass(
                "io.nova.spring.data.SpringDataOptionalityTest$PlainAccountRepository");

        StubOperations ops = new StubOperations();
        ops.nextFindAll = Flux.just(new Account(1L, "a"), new Account(2L, "b"));
        SimpleReactiveRepository handler =
                new SimpleReactiveRepository(Account.class, Long.class, ops);

        // 핵심: eager proxy 생성이 Spring 타입을 resolve하지 않아야 성공한다.
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{plainRepo}, handler);
        assertNotNull(proxy);

        // 사용까지 검증: findAll()이 정상 위임된다. 인터페이스 메서드로 invoke한다(생성된 proxy
        // 클래스는 접근 불가할 수 있으므로 public 인터페이스의 Method를 사용).
        java.lang.reflect.Method findAll = plainRepo.getMethod("findAll");
        findAll.setAccessible(true);
        @SuppressWarnings("unchecked")
        Flux<Object> result = (Flux<Object>) findAll.invoke(proxy);
        StepVerifier.create(result).expectNextCount(2).verifyComplete();
    }

    @Test
    @DisplayName("대조: SpringDataReactiveCrudRepository 서브인터페이스는 Spring 타입 부재 시 proxy 생성 실패")
    void springBridgeRepositoryProxyFailsWithoutSpringDataOnClasspath() throws Exception {
        SpringHidingClassLoader loader = new SpringHidingClassLoader(
                SpringDataOptionalityTest.class.getClassLoader());

        Class<?> springRepo = loader.loadClass(
                "io.nova.spring.data.SpringDataOptionalityTest$SpringAccountRepository");

        StubOperations ops = new StubOperations();
        SimpleReactiveRepository handler =
                new SimpleReactiveRepository(Account.class, Long.class, ops);

        // proxy 생성 과정에서 org.springframework.data.domain.Pageable/Sort 파라미터 타입을
        // resolve하려다 실패한다 → 이것이 바로 이 메서드들을 opt-in 서브인터페이스로 격리해야 하는 이유.
        Throwable failure = assertThrows(Throwable.class,
                () -> Proxy.newProxyInstance(loader, new Class<?>[]{springRepo}, handler));
        String trace = stringify(failure);
        assertTrue(trace.contains("springframework"),
                "failure must be caused by the missing Spring Data type, got: " + trace);
    }

    private static String stringify(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c).append(" | ");
        }
        return sb.toString();
    }

    // --- 격리용 classloader ---------------------------------------------------------------------

    /**
     * {@code org.springframework.data.*}를 로드 불가로 막고(런타임 부재 시뮬레이션), 지정된 repository
     * 관련 클래스는 부모 리소스 bytes에서 child-first로 재정의해 그들의 링크가 이 loader를 통해
     * 일어나도록 하는 classloader. 그 외 이름은 부모에 위임한다.
     */
    static final class SpringHidingClassLoader extends ClassLoader {
        SpringHidingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.springframework.data.")) {
                throw new ClassNotFoundException("hidden to simulate absent spring-data-commons: " + name);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = childFirst(name) ? defineFromParent(name) : super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private boolean childFirst(String name) {
            return name.equals("io.nova.spring.data.ReactiveCrudRepository")
                    || name.equals("io.nova.spring.data.SpringDataReactiveCrudRepository")
                    || name.startsWith("io.nova.spring.data.SpringDataOptionalityTest$");
        }

        private Class<?> defineFromParent(String name) throws ClassNotFoundException {
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = getParent().getResourceAsStream(path)) {
                if (in == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    // --- 테스트 fixtures -------------------------------------------------------------------------

    static final class Account {
        final Long id;
        final String name;

        Account(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** Spring 타입을 전혀 참조하지 않는 순수 repository 인터페이스. */
    interface PlainAccountRepository extends ReactiveCrudRepository<Account, Long> {
    }

    /** Spring 타입 브릿지를 opt-in한 repository 인터페이스(대조군). */
    interface SpringAccountRepository extends SpringDataReactiveCrudRepository<Account, Long> {
    }

    /** findAll(Class, QuerySpec)만 의미 있게 구현하는 최소 stub. */
    static final class StubOperations implements ReactiveEntityOperations {
        Flux<?> nextFindAll = Flux.empty();

        @Override
        @SuppressWarnings("unchecked")
        public <T> Flux<T> findAll(Class<T> entityType, QuerySpec querySpec) {
            return (Flux<T>) nextFindAll;
        }

        @Override
        public <T> Mono<T> save(T entity) {
            return Mono.empty();
        }

        @Override
        public <T, ID> Mono<T> findById(Class<T> entityType, ID id) {
            return Mono.empty();
        }

        @Override
        public <T> Mono<Long> delete(T entity) {
            return Mono.just(0L);
        }

        @Override
        public <T, ID> Mono<Long> deleteById(Class<T> entityType, ID id) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Long> count(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(0L);
        }

        @Override
        public <T> Mono<Boolean> exists(Class<T> entityType, QuerySpec querySpec) {
            return Mono.just(Boolean.FALSE);
        }

        @Override
        public <R> Mono<R> inTransaction(Function<ReactiveEntityOperations, Mono<R>> callback) {
            return callback.apply(this);
        }
    }
}
