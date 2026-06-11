package io.nova.metamodel;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 컴파일 시점에 {@code @Entity} 클래스를 스캔해 {@code <EntityName>_} 동반 클래스를
 * 생성한다. 생성된 클래스는 엔티티의 영속 프로퍼티마다 {@code public static final String}
 * 상수를 하나씩 노출하며, 값은 {@link io.nova.query.Criteria}가 받는 property 이름
 * (예: {@code "email"}, {@code "address.city"})과 일치한다.
 *
 * <p>JPA static metamodel과 유사하지만 typed path가 아닌 단순한 문자열 상수만 발행한다
 * — typo는 컴파일 시점에 잡되, 표현력은 기존 Criteria DSL 그대로 유지된다. 본격적인
 * typed path DSL은 L3({@code nova-querydsl}) 모듈로 별도 추적된다.
 *
 * <p>필드 선택 규칙은 {@link io.nova.metadata.EntityMetadataFactory}의 동작과 정확히
 * 일치한다:
 * <ul>
 *   <li>{@code static} / {@code transient} 필드는 제외한다.
 *   <li>{@code @OneToMany}는 부모 테이블 컬럼이 없는 inverse-only marker라 제외한다.
 *   <li>{@code @Embedded}는 host 필드 이름을 prefix로 하여 leaf 필드까지 평탄화한다.
 *       Java 식별자는 {@code host_leaf}, 값은 dot-notation {@code "host.leaf"}.
 *   <li>그 밖에 모든 필드({@code @Id}, {@code @Version}, {@code @CreatedAt},
 *       {@code @ManyToOne} FK, {@code @Json}, {@code @Enumerated} 등)는 한 상수씩
 *       발행한다.
 * </ul>
 */
@SupportedAnnotationTypes("jakarta.persistence.Entity")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class MetamodelProcessor extends AbstractProcessor {

    private static final String ENTITY = "jakarta.persistence.Entity";
    private static final String EMBEDDED = "jakarta.persistence.Embedded";
    private static final String ONE_TO_MANY = "jakarta.persistence.OneToMany";

    /**
     * 잘못 정의된 {@code @Embedded} 사이클로 인한 무한 재귀를 방지하는 안전 한계.
     * 실제 nested embedded는 2–3 단계를 거의 넘지 않는다.
     */
    private static final int MAX_EMBEDDED_DEPTH = 8;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            if (!annotation.getQualifiedName().contentEquals(ENTITY)) {
                continue;
            }
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() != ElementKind.CLASS) {
                    continue;
                }
                generateFor((TypeElement) element);
            }
        }
        return true;
    }

    private void generateFor(TypeElement entityType) {
        List<Property> properties = new ArrayList<>();
        try {
            collectProperties(entityType, List.of(), new LinkedHashSet<>(), properties);
        } catch (IllegalStateException ex) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, ex.getMessage(), entityType);
            return;
        }
        rejectDuplicateSafeNames(entityType, properties);
        emit(entityType, properties);
    }

    private void collectProperties(
            TypeElement type,
            List<String> hostPath,
            Set<String> visited,
            List<Property> out) {
        if (hostPath.size() > MAX_EMBEDDED_DEPTH) {
            throw new IllegalStateException(
                    "Metamodel @Embedded recursion exceeded " + MAX_EMBEDDED_DEPTH
                            + " levels at " + type.getQualifiedName()
                            + " — likely an @Embedded cycle.");
        }
        String typeKey = type.getQualifiedName().toString();
        if (!visited.add(typeKey)) {
            throw new IllegalStateException(
                    "Metamodel detected an @Embedded cycle through " + typeKey);
        }
        try {
            for (Element member : type.getEnclosedElements()) {
                if (member.getKind() != ElementKind.FIELD) {
                    continue;
                }
                VariableElement field = (VariableElement) member;
                Set<Modifier> mods = field.getModifiers();
                if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.TRANSIENT)) {
                    continue;
                }
                if (hasAnnotation(field, ONE_TO_MANY)) {
                    continue;
                }
                String fieldName = field.getSimpleName().toString();
                if (hasAnnotation(field, EMBEDDED)) {
                    TypeElement embeddedType = resolveTypeElement(field.asType());
                    if (embeddedType == null) {
                        processingEnv.getMessager().printMessage(
                                Diagnostic.Kind.ERROR,
                                "@Embedded field type cannot be resolved as a class element",
                                field);
                        continue;
                    }
                    List<String> nextPath = new ArrayList<>(hostPath);
                    nextPath.add(fieldName);
                    collectProperties(embeddedType, nextPath, visited, out);
                    continue;
                }
                out.add(toProperty(hostPath, fieldName));
            }
        } finally {
            visited.remove(typeKey);
        }
    }

    private static Property toProperty(List<String> hostPath, String fieldName) {
        if (hostPath.isEmpty()) {
            return new Property(fieldName, fieldName);
        }
        StringBuilder path = new StringBuilder();
        StringBuilder safe = new StringBuilder();
        for (String host : hostPath) {
            path.append(host).append('.');
            safe.append(host).append('_');
        }
        path.append(fieldName);
        safe.append(fieldName);
        return new Property(safe.toString(), path.toString());
    }

    private void rejectDuplicateSafeNames(TypeElement entityType, List<Property> properties) {
        Set<String> seen = new LinkedHashSet<>();
        for (Iterator<Property> it = properties.iterator(); it.hasNext(); ) {
            Property property = it.next();
            if (!seen.add(property.safeName())) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "Metamodel name collision on " + entityType.getQualifiedName()
                                + ": property paths produce the same Java identifier '"
                                + property.safeName()
                                + "'. Rename the conflicting embedded host or leaf field.",
                        entityType);
                it.remove();
            }
        }
    }

    private TypeElement resolveTypeElement(TypeMirror typeMirror) {
        Element element = processingEnv.getTypeUtils().asElement(typeMirror);
        if (element instanceof TypeElement typeElement) {
            return typeElement;
        }
        return null;
    }

    private static boolean hasAnnotation(Element element, String qualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annotationElement = mirror.getAnnotationType().asElement();
            if (annotationElement instanceof TypeElement typeElement
                    && typeElement.getQualifiedName().contentEquals(qualifiedName)) {
                return true;
            }
        }
        return false;
    }

    private void emit(TypeElement entityType, List<Property> properties) {
        PackageElement pkg = processingEnv.getElementUtils().getPackageOf(entityType);
        String packageName = pkg.getQualifiedName().toString();
        String simpleName = entityType.getSimpleName() + "_";
        String qualifiedName = packageName.isEmpty()
                ? simpleName
                : packageName + "." + simpleName;

        String source = renderSource(packageName, simpleName, properties);
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, entityType);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to write metamodel companion " + qualifiedName + ": " + e.getMessage(),
                    entityType);
        }
    }

    private static String renderSource(String packageName, String simpleName, List<Property> properties) {
        StringBuilder out = new StringBuilder(256 + properties.size() * 64);
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("import javax.annotation.processing.Generated;\n\n");
        out.append("/**\n");
        out.append(" * Metamodel companion generated by Nova for type-safe Criteria property references.\n");
        out.append(" * Each constant maps to the property name accepted by {@code io.nova.query.Criteria}.\n");
        out.append(" * Do not edit by hand.\n");
        out.append(" */\n");
        out.append("@Generated(\"io.nova.metamodel.MetamodelProcessor\")\n");
        out.append("public final class ").append(simpleName).append(" {\n");
        out.append("    private ").append(simpleName).append("() {\n");
        out.append("    }\n");
        for (Property property : properties) {
            out.append("\n    public static final String ")
                    .append(property.safeName())
                    .append(" = \"")
                    .append(property.path())
                    .append("\";\n");
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * 발행할 단일 상수 정보. {@code safeName}은 Java 식별자, {@code path}는 Criteria 호출에
     * 그대로 사용 가능한 dot-notation 문자열이다.
     */
    private record Property(String safeName, String path) {
    }
}
