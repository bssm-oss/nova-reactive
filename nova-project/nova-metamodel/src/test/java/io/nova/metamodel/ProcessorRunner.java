package io.nova.metamodel;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory JavaCompiler 호출 헬퍼. 외부 toolchain 의존 없이 표준 {@code javax.tools} API만
 * 사용해 fixture entity 소스를 {@link MetamodelProcessor}에 통과시키고, 결과로 생성된 동반
 * 소스와 diagnostic을 캡처한다.
 */
final class ProcessorRunner {

    private ProcessorRunner() {
    }

    record Source(String qualifiedName, String code) {
    }

    record Compilation(
            boolean success,
            List<Diagnostic<? extends JavaFileObject>> diagnostics,
            Map<String, String> generatedSources) {

        Diagnostic<? extends JavaFileObject> firstError() {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .findFirst()
                    .orElse(null);
        }
    }

    static Compilation compile(Source... sources) {
        JavaCompiler compiler = Objects.requireNonNull(
                ToolProvider.getSystemJavaCompiler(),
                "JavaCompiler not available — run tests on a JDK, not a JRE.");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standardManager =
                compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8);
        InMemoryFileManager fileManager = new InMemoryFileManager(standardManager);

        List<JavaFileObject> compilationUnits = new ArrayList<>();
        for (Source source : sources) {
            compilationUnits.add(new StringSourceFile(source.qualifiedName(), source.code()));
        }

        List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-proc:full"
        );

        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics, options, null, compilationUnits);
        task.setProcessors(List.of(new MetamodelProcessor()));
        boolean success = task.call();

        return new Compilation(
                success,
                diagnostics.getDiagnostics(),
                fileManager.snapshotGeneratedSources());
    }

    private static final class StringSourceFile extends SimpleJavaFileObject {
        private final String code;

        StringSourceFile(String qualifiedName, String code) {
            super(URI.create("string:///" + qualifiedName.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, ByteArrayOutputStream> generatedSources = new LinkedHashMap<>();

        InMemoryFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                Location location,
                String className,
                JavaFileObject.Kind kind,
                FileObject sibling) throws IOException {
            if (location == StandardLocation.SOURCE_OUTPUT && kind == JavaFileObject.Kind.SOURCE) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                generatedSources.put(className, buffer);
                return new GeneratedSourceFile(className, buffer);
            }
            // CLASS_OUTPUT, NATIVE_HEADER_OUTPUT 등 그 외 모든 출력은 메모리에서만 받고 버린다.
            // 위임하지 않으면 standard file manager가 cwd 아래에 .class 파일을 남긴다.
            return new DiscardingOutputFile(className, kind);
        }

        Map<String, String> snapshotGeneratedSources() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<String, ByteArrayOutputStream> entry : generatedSources.entrySet()) {
                out.put(entry.getKey(), entry.getValue().toString(StandardCharsets.UTF_8));
            }
            return Collections.unmodifiableMap(out);
        }
    }

    /**
     * 컴파일러가 SOURCE_OUTPUT 외에 쓰려는(주로 CLASS_OUTPUT의 .class) 산출물을 받기만 하고
     * 그대로 버리는 sink. 디스크에 흘러나가 cwd를 더럽히는 것을 막는다.
     */
    private static final class DiscardingOutputFile extends SimpleJavaFileObject {
        DiscardingOutputFile(String className, JavaFileObject.Kind kind) {
            super(URI.create("mem:///discard/" + className.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return OutputStream.nullOutputStream();
        }
    }

    private static final class GeneratedSourceFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream buffer;

        GeneratedSourceFile(String className, ByteArrayOutputStream buffer) {
            super(URI.create("mem:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.buffer = buffer;
        }

        @Override
        public OutputStream openOutputStream() {
            return buffer;
        }

        /**
         * 다음 annotation processing round에서 compiler가 방금 생성된 소스를 다시 읽어
         * 컴파일하려 할 때 호출된다. 기본 {@link SimpleJavaFileObject} 구현은 UOE를 던지므로
         * buffer에 누적된 문자열을 그대로 돌려준다.
         */
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
