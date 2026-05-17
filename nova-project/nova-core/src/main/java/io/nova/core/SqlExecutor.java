package io.nova.core;

import io.nova.sql.SqlStatement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

public interface SqlExecutor {
    Mono<Long> execute(SqlStatement statement);

    <T> Mono<T> queryOne(SqlStatement statement, Function<RowAccessor, T> mapper);

    <T> Flux<T> queryMany(SqlStatement statement, Function<RowAccessor, T> mapper);

    /**
     * insert 후 데이터베이스가 생성한 키 한 개를 회수한다. 기본 구현은 키를 반환하지 않으며,
     * dialect/driver 차이를 처리할 수 있는 executor가 재정의해야 한다.
     */
    default <T> Mono<T> executeAndReturnGeneratedKey(SqlStatement statement, String idColumn, Class<T> idType) {
        return execute(statement).then(Mono.empty());
    }
}
