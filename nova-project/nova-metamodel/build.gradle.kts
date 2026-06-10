description = "Nova metamodel — compile-time property-name constants for type-safe Criteria references."

dependencies {
    // 어노테이션 심볼(@Entity/@Column/@Embedded/@ManyToOne/@OneToMany 등)을 참조하기 위해
    // compileOnly로만 의존한다 — 생성된 *_ 클래스는 nova-core에 대한 의존이 없는 순수 String 상수다.
    compileOnly(project(":nova-project:nova-core"))

    // 단위 테스트에서 in-memory JavaCompiler로 fixture entity를 컴파일하고 generated source를 검증한다.
    testImplementation(project(":nova-project:nova-core"))
}
