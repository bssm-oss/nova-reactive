package io.nova.spring.data;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.util.Set;

/**
 * 지정 base 패키지 아래에서 {@link ReactiveCrudRepository}를 상속하는 인터페이스만 골라내는
 * classpath scanner다. Spring Framework의 {@link ClassPathScanningCandidateComponentProvider}를
 * 재구성해 인터페이스를 candidate로 허용하도록 한다.
 */
public final class RepositoryScanner {
    private final ClassPathScanningCandidateComponentProvider provider;

    public RepositoryScanner() {
        // useDefaultFilters=false로 component-scan의 표준 stereotype 필터를 끄고,
        // {@link ReactiveCrudRepository} 상속 여부 한 가지 조건만 include filter로 적용한다.
        // {@code isCandidateComponent(MetadataReader)}는 default 구현(includeFilters/excludeFilters
        // 적용)을 그대로 사용해 type filter가 실제로 평가되도록 둔다. 인터페이스 candidate를 허용하기
        // 위해서는 {@code isCandidateComponent(AnnotatedBeanDefinition)}만 override 한다.
        this.provider = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isInterface()
                        && beanDefinition.getMetadata().isIndependent();
            }
        };
        this.provider.addIncludeFilter(new AssignableTypeFilter(ReactiveCrudRepository.class));
    }

    /**
     * 프레임워크가 제공하는 base repository 인터페이스들. 이들은 구체 엔티티 타입 인자가 없는
     * 제네릭 계약이므로 스캔 결과에서 제외한다(엔티티 타입 resolve 불가 → 잘못 등록 시 부팅 실패).
     */
    private static final Set<String> FRAMEWORK_BASE_INTERFACES = Set.of(
            ReactiveCrudRepository.class.getName(),
            SpringDataReactiveCrudRepository.class.getName());

    /**
     * 주어진 base 패키지 트리에서 {@link ReactiveCrudRepository}의 서브 인터페이스를 찾아
     * {@link BeanDefinition} 집합으로 반환한다. {@link ReactiveCrudRepository}와
     * {@link SpringDataReactiveCrudRepository} 같은 프레임워크 base 인터페이스는 결과에서 제외된다.
     */
    public Set<BeanDefinition> scan(String basePackage) {
        Set<BeanDefinition> candidates = provider.findCandidateComponents(basePackage);
        candidates.removeIf(definition -> {
            String beanClassName = (definition instanceof AbstractBeanDefinition abd && abd.hasBeanClass())
                    ? abd.getBeanClass().getName()
                    : definition.getBeanClassName();
            return FRAMEWORK_BASE_INTERFACES.contains(beanClassName);
        });
        return candidates;
    }

    /**
     * 외부에서 {@link MetadataReaderFactory}를 교체하거나 추가 필터를 적용해야 할 때 사용한다.
     */
    public ClassPathScanningCandidateComponentProvider provider() {
        return provider;
    }
}
