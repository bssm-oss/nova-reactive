package io.nova.metamodel;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.beans.Introspector;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Generates Criteria property-name constants using Nova's effective JPA access rules. */
@SupportedAnnotationTypes("jakarta.persistence.Entity")
public final class MetamodelProcessor extends AbstractProcessor {
    private static final String ENTITY = "jakarta.persistence.Entity";
    private static final String EMBEDDABLE = "jakarta.persistence.Embeddable";
    private static final String ACCESS = "jakarta.persistence.Access";
    private static final String ACCESS_FIELD = "FIELD";
    private static final String ACCESS_PROPERTY = "PROPERTY";
    private static final String EMBEDDED = "jakarta.persistence.Embedded";
    private static final String EMBEDDED_ID = "jakarta.persistence.EmbeddedId";
    private static final String ID = "jakarta.persistence.Id";
    private static final String MAPPED_SUPERCLASS = "jakarta.persistence.MappedSuperclass";
    private static final String ONE_TO_MANY = "jakarta.persistence.OneToMany";
    private static final String TRANSIENT = "jakarta.persistence.Transient";
    private static final int MAX_EMBEDDED_DEPTH = 8;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            if (!annotation.getQualifiedName().contentEquals(ENTITY)) continue;
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.CLASS) generateFor((TypeElement) element);
            }
        }
        return true;
    }

    private void generateFor(TypeElement entityType) {
        List<Property> properties = new ArrayList<>();
        try {
            collectProperties(entityType, null, List.of(), new LinkedHashSet<>(), properties);
        } catch (IllegalStateException ex) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, ex.getMessage(), entityType);
            return;
        }
        rejectDuplicateSafeNames(entityType, properties);
        emit(entityType, properties);
    }

    private void collectProperties(TypeElement type, String inheritedAccess, List<String> hostPath,
            Set<String> visited, List<Property> out) {
        if (hostPath.size() > MAX_EMBEDDED_DEPTH) {
            throw new IllegalStateException("Metamodel @Embedded recursion exceeded " + MAX_EMBEDDED_DEPTH
                    + " levels at " + type.getQualifiedName() + " — likely an @Embedded cycle.");
        }
        String typeKey = type.getQualifiedName().toString();
        if (!visited.add(typeKey)) throw new IllegalStateException("Metamodel detected an @Embedded cycle through " + typeKey);
        try {
            List<TypeElement> declarations = hasAnnotation(type, EMBEDDABLE) ? List.of(type) : hierarchy(type);
            validateIdentifierPlacement(declarations, type);
            String defaultAccess = inheritedAccess != null ? inheritedAccess : hierarchyDefault(declarations, type);
            Map<String, List<Property>> selectedProperties = new LinkedHashMap<>();
            for (TypeElement declaration : declarations) {
                String declarationAccess = classAccess(declaration);
                if (declarationAccess == null) declarationAccess = defaultAccess;
                Map<String, VariableElement> fields = new LinkedHashMap<>();
                for (Element member : declaration.getEnclosedElements()) {
                    if (member.getKind() == ElementKind.FIELD && candidateField((VariableElement) member)) {
                        VariableElement field = (VariableElement) member;
                        fields.put(field.getSimpleName().toString(), field);
                    }
                }
                Map<String, ExecutableElement> getters = getters(declaration);
                Map<String, List<ExecutableElement>> setters = setters(declaration);
                Set<String> names = new LinkedHashSet<>(fields.keySet());
                names.addAll(getters.keySet());
                for (String name : names) {
                    VariableElement field = fields.get(name);
                    ExecutableElement getter = getters.get(name);
                    String selectedAccess = memberAccess(field, getter, type, name);
                    if (selectedAccess == null) selectedAccess = declarationAccess;
                    List<Property> declarationProperties = new ArrayList<>();
                    if (ACCESS_FIELD.equals(selectedAccess)) {
                        if (field == null) {
                            if (getter != null && hasMappingAnnotation(getter)) inactiveMember(type, name, "PROPERTY");
                            continue;
                        }
                        rejectInactive(field, getter, type, name);
                        if (hasAnnotation(field, TRANSIENT)) continue;
                        collectSelectedProperty(type, field, name, ACCESS_FIELD, hostPath, visited, declarationProperties);
                    } else {
                        if (getter == null) {
                            if (field != null && hasMappingAnnotation(field)) inactiveMember(type, name, "FIELD");
                            if (field != null && ACCESS_PROPERTY.equals(accessValue(field))) {
                                throw new IllegalStateException(type.getQualifiedName() + "." + name
                                        + " declares @Access(PROPERTY) on a field; @Access(PROPERTY) belongs on a getter");
                            }
                            continue;
                        }
                        if (hasAnnotation(getter, TRANSIENT)) continue;
                        if (!declaration.getKind().equals(ElementKind.RECORD)) {
                            ExecutableElement setter = setter(type, name, getter, setters.get(name));
                            if (setter == null) {
                                throw new IllegalStateException(type.getQualifiedName() + "." + name
                                        + " has no JavaBean setter required by PROPERTY access");
                            }
                            if (!setter.getParameters().get(0).asType().equals(getter.getReturnType())) {
                                throw new IllegalStateException(type.getQualifiedName() + "." + name
                                        + " getter/setter types are incompatible");
                            }
                        }
                        rejectInactive(getter, field, type, name);
                        collectSelectedProperty(type, getter, name, ACCESS_PROPERTY, hostPath, visited, declarationProperties);
                    }
                    selectedProperties.put(name, declarationProperties);
                }
            }
            selectedProperties.values().forEach(out::addAll);
        } finally {
            visited.remove(typeKey);
        }
    }

    private void collectSelectedProperty(TypeElement owner, Element selected, String name, String access,
            List<String> hostPath, Set<String> visited, List<Property> out) {
        if (hasAnnotation(selected, TRANSIENT) || hasAnnotation(selected, ONE_TO_MANY)) return;
        if (!hasAnnotation(selected, EMBEDDED) && !hasAnnotation(selected, EMBEDDED_ID)) {
            out.add(toProperty(hostPath, name));
            return;
        }
        TypeElement embeddedType = resolveTypeElement(memberType(selected));
        if (embeddedType == null) {
            throw new IllegalStateException("@Embedded member type cannot be resolved as a class element: "
                    + owner.getQualifiedName() + "." + name);
        }
        List<String> nextPath = new ArrayList<>(hostPath);
        nextPath.add(name);
        collectProperties(embeddedType, access, nextPath, visited, out);
    }

    private List<TypeElement> hierarchy(TypeElement type) {
        List<TypeElement> result = new ArrayList<>();
        for (TypeElement current = type; current != null
                && !current.getQualifiedName().contentEquals(Object.class.getName());
                current = superclass(current)) {
            if (current.equals(type) || hasAnnotation(current, ENTITY) || hasAnnotation(current, MAPPED_SUPERCLASS)) {
                result.add(0, current);
            }
        }
        return result;
    }

    private TypeElement superclass(TypeElement type) {
        TypeMirror parent = type.getSuperclass();
        return parent == null ? null : resolveTypeElement(parent);
    }

    private String hierarchyDefault(List<TypeElement> declarations, TypeElement type) {
        for (TypeElement current : declarations) {
            for (Element member : current.getEnclosedElements()) {
                if (member.getKind() == ElementKind.FIELD
                        && (hasAnnotation(member, ID) || hasAnnotation(member, EMBEDDED_ID))) return ACCESS_FIELD;
            }
        }
        boolean propertyIdentifier = false;
        for (TypeElement current : declarations) {
            for (Element member : current.getEnclosedElements()) {
                if (member.getKind() == ElementKind.METHOD
                        && (hasAnnotation(member, ID) || hasAnnotation(member, EMBEDDED_ID))) propertyIdentifier = true;
            }
        }
        return propertyIdentifier ? ACCESS_PROPERTY : ACCESS_FIELD;
    }

    private void validateIdentifierPlacement(List<TypeElement> declarations, TypeElement type) {
        boolean fieldIdentifier = false;
        boolean propertyIdentifier = false;
        for (TypeElement current : declarations) {
            for (Element member : current.getEnclosedElements()) {
                if (member.getKind() == ElementKind.FIELD
                        && (hasAnnotation(member, ID) || hasAnnotation(member, EMBEDDED_ID))) {
                    fieldIdentifier = true;
                }
                if (member.getKind() == ElementKind.METHOD
                        && (hasAnnotation(member, ID) || hasAnnotation(member, EMBEDDED_ID))) {
                    propertyIdentifier = true;
                }
            }
        }
        if (fieldIdentifier && propertyIdentifier) {
            throw new IllegalStateException(type.getQualifiedName()
                    + " mixes field and property identifier placement; use one consistent access strategy");
        }
    }

    private Map<String, ExecutableElement> getters(TypeElement type) {
        Map<String, List<ExecutableElement>> candidates = new TreeMap<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) member;
            String name = getterName(method);
            if (name != null) candidates.computeIfAbsent(name, ignored -> new ArrayList<>()).add(method);
        }
        Map<String, ExecutableElement> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<ExecutableElement>> entry : candidates.entrySet()) {
            result.put(entry.getKey(), getter(type, entry.getKey(), entry.getValue()));
        }
        if (type.getKind() == ElementKind.RECORD) {
            for (RecordComponentElement component : type.getRecordComponents()) {
                String name = component.getSimpleName().toString();
                ExecutableElement accessor = component.getAccessor();
                ExecutableElement previous = result.put(name, accessor);
                if (previous != null && !previous.equals(accessor)) {
                    throw new IllegalStateException(type.getQualifiedName()
                            + " has ambiguous accessor for " + name);
                }
            }
        }
        return result;
    }

    private Map<String, List<ExecutableElement>> setters(TypeElement type) {
        Map<String, List<ExecutableElement>> result = new TreeMap<>();
        for (Element member : type.getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) continue;
            ExecutableElement method = (ExecutableElement) member;
            String methodName = method.getSimpleName().toString();
            if (method.getModifiers().contains(Modifier.STATIC) || method.getParameters().size() != 1
                    || method.getReturnType().getKind() != TypeKind.VOID
                    || !methodName.startsWith("set") || methodName.length() == 3) continue;
            String name = Introspector.decapitalize(methodName.substring(3));
            result.computeIfAbsent(name, ignored -> new ArrayList<>()).add(method);
        }
        return result;
    }

    private static ExecutableElement getter(TypeElement type, String property, List<ExecutableElement> candidates) {
        List<ExecutableElement> booleanGetters = candidates.stream()
                .filter(method -> method.getSimpleName().toString().startsWith("is"))
                .sorted(Comparator.comparing(ExecutableElement::toString))
                .toList();
        List<ExecutableElement> regularGetters = candidates.stream()
                .filter(method -> method.getSimpleName().toString().startsWith("get"))
                .sorted(Comparator.comparing(ExecutableElement::toString))
                .toList();
        if (booleanGetters.size() > 1) {
            throw new IllegalStateException(type.getQualifiedName() + " has ambiguous getter for " + property);
        }
        if (!booleanGetters.isEmpty()) {
            ExecutableElement booleanGetter = booleanGetters.get(0);
            for (ExecutableElement regularGetter : regularGetters) {
                if (!regularGetter.getReturnType().equals(booleanGetter.getReturnType())) {
                    throw new IllegalStateException(type.getQualifiedName() + "." + property
                            + " has incompatible JavaBean getters");
                }
            }
            return booleanGetter;
        }
        if (regularGetters.size() != 1) {
            throw new IllegalStateException(type.getQualifiedName() + " has ambiguous getter for " + property);
        }
        return regularGetters.get(0);
    }

    private static ExecutableElement setter(TypeElement type, String property, ExecutableElement getter,
            List<ExecutableElement> candidates) {
        if (candidates == null) return null;
        List<ExecutableElement> exactMatches = candidates.stream()
                .filter(method -> method.getParameters().get(0).asType().equals(getter.getReturnType()))
                .sorted(Comparator.comparing(ExecutableElement::toString))
                .toList();
        if (exactMatches.size() == 1) return exactMatches.get(0);
        if (exactMatches.size() > 1) {
            throw new IllegalStateException(type.getQualifiedName() + " has ambiguous setter for " + property);
        }
        throw new IllegalStateException(type.getQualifiedName() + "." + property
                + " getter/setter types are incompatible");
    }

    private static String getterName(ExecutableElement method) {
        Set<Modifier> modifiers = method.getModifiers();
        if (modifiers.contains(Modifier.STATIC) || method.getParameters().size() != 0
                || method.getReturnType().getKind() == TypeKind.VOID) return null;
        String name = method.getSimpleName().toString();
        if (name.startsWith("get") && name.length() > 3) return Introspector.decapitalize(name.substring(3));
        if (name.startsWith("is") && name.length() > 2 && method.getReturnType().getKind() == TypeKind.BOOLEAN) {
            return Introspector.decapitalize(name.substring(2));
        }
        return null;
    }

    private static boolean candidateField(VariableElement field) {
        Set<Modifier> modifiers = field.getModifiers();
        return !modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.TRANSIENT);
    }

    private String memberAccess(VariableElement field, ExecutableElement getter, TypeElement type, String name) {
        String fieldAccess = field == null ? null : accessValue(field);
        String getterAccess = getter == null ? null : accessValue(getter);
        if (fieldAccess != null && getterAccess != null && !fieldAccess.equals(getterAccess)) {
            throw new IllegalStateException(type.getQualifiedName() + "." + name + " has conflicting member @Access declarations");
        }
        return fieldAccess != null ? fieldAccess : getterAccess;
    }

    private void rejectInactive(Element selected, Element inactive, TypeElement type, String name) {
        if (inactive != null && hasMappingAnnotation(inactive) && accessValue(inactive) == null) {
            throw new IllegalStateException(type.getQualifiedName() + "." + name + " has mapping annotations on inactive access member");
        }
    }

    private static void inactiveMember(TypeElement type, String name, String memberKind) {
        throw new IllegalStateException(type.getQualifiedName() + "." + name
                + " has mapping annotations on inactive " + memberKind + " member");
    }

    private String classAccess(TypeElement type) {
        return accessValue(type);
    }

    private String accessValue(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annotation = mirror.getAnnotationType().asElement();
            if (!(annotation instanceof TypeElement type) || !type.getQualifiedName().contentEquals(ACCESS)) continue;
            for (Map.Entry<? extends ExecutableElement, ? extends javax.lang.model.element.AnnotationValue> value
                    : processingEnv.getElementUtils().getElementValuesWithDefaults(mirror).entrySet()) {
                if (value.getKey().getSimpleName().contentEquals("value")) {
                    return value.getValue().getValue().toString();
                }
            }
        }
        return null;
    }

    private static TypeMirror memberType(Element member) {
        return member instanceof VariableElement field ? field.asType() : ((ExecutableElement) member).getReturnType();
    }

    private static Property toProperty(List<String> hostPath, String name) {
        if (hostPath.isEmpty()) return new Property(name, name);
        StringBuilder path = new StringBuilder();
        StringBuilder safe = new StringBuilder();
        for (String host : hostPath) {
            path.append(host).append('.');
            safe.append(host).append('_');
        }
        return new Property(safe.append(name).toString(), path.append(name).toString());
    }

    private void rejectDuplicateSafeNames(TypeElement entityType, List<Property> properties) {
        Set<String> seen = new LinkedHashSet<>();
        for (Iterator<Property> it = properties.iterator(); it.hasNext();) {
            Property property = it.next();
            if (!seen.add(property.safeName())) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Metamodel name collision on " + entityType.getQualifiedName()
                                + ": property paths produce the same Java identifier '" + property.safeName()
                                + "'. Rename the conflicting embedded host or leaf field.", entityType);
                it.remove();
            }
        }
    }

    private TypeElement resolveTypeElement(TypeMirror typeMirror) {
        Element element = processingEnv.getTypeUtils().asElement(typeMirror);
        return element instanceof TypeElement typeElement ? typeElement : null;
    }

    private static boolean hasAnnotation(Element element, String qualifiedName) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annotation = mirror.getAnnotationType().asElement();
            if (annotation instanceof TypeElement type && type.getQualifiedName().contentEquals(qualifiedName)) return true;
        }
        return false;
    }

    private static boolean hasMappingAnnotation(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            Element annotation = mirror.getAnnotationType().asElement();
            if (!(annotation instanceof TypeElement type)) continue;
            String name = type.getQualifiedName().toString();
            if ((name.startsWith("jakarta.persistence.") || name.startsWith("io.nova.annotation."))
                    && !name.equals(ACCESS) && !name.equals(TRANSIENT)) return true;
        }
        return false;
    }

    private void emit(TypeElement entityType, List<Property> properties) {
        PackageElement pkg = processingEnv.getElementUtils().getPackageOf(entityType);
        String packageName = pkg.getQualifiedName().toString();
        String simpleName = entityType.getSimpleName() + "_";
        String qualifiedName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        try {
            JavaFileObject file = processingEnv.getFiler().createSourceFile(qualifiedName, entityType);
            try (Writer writer = file.openWriter()) {
                writer.write(renderSource(packageName, simpleName, properties));
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write metamodel companion " + qualifiedName + ": " + e.getMessage(), entityType);
        }
    }

    private static String renderSource(String packageName, String simpleName, List<Property> properties) {
        StringBuilder out = new StringBuilder(256 + properties.size() * 64);
        if (!packageName.isEmpty()) out.append("package ").append(packageName).append(";\n\n");
        out.append("import javax.annotation.processing.Generated;\n\n");
        out.append("/**\n * Metamodel companion generated by Nova for type-safe Criteria property references.\n")
                .append(" * Each constant maps to the property name accepted by {@code io.nova.query.Criteria}.\n")
                .append(" * Do not edit by hand.\n */\n")
                .append("@Generated(\"io.nova.metamodel.MetamodelProcessor\")\n")
                .append("public final class ").append(simpleName).append(" {\n")
                .append("    private ").append(simpleName).append("() {\n    }\n");
        for (Property property : properties) {
            out.append("\n    public static final String ").append(property.safeName()).append(" = \"")
                    .append(property.path()).append("\";\n");
        }
        return out.append("}\n").toString();
    }

    private record Property(String safeName, String path) { }
}
