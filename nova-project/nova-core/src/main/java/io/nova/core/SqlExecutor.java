package io.nova.core;

import io.nova.sql.SqlStatement;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
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

    /**
     * 동일한 SQL을 여러 바인딩 집합으로 일괄 실행한다. 기본 구현은 {@link #execute(SqlStatement)}로 한 건씩
     * 폴백한다. R2DBC와 같은 어댑터는 {@code Statement.add()} 기반의 진짜 배치 경로로 override한다.
     */
    default Mono<Long> executeBatch(String sql, List<List<Object>> bindingsList) {
        if (bindingsList.isEmpty()) {
            return Mono.just(0L);
        }
        return Flux.fromIterable(bindingsList)
                .concatMap(bindings -> execute(new SqlStatement(sql, bindings)))
                .reduce(0L, Long::sum);
    }

    /**
     * 동일한 SQL을 여러 바인딩 집합으로 일괄 실행하면서 데이터베이스가 생성한 키를 반환한다.
     * 기본 구현은 키를 반환하지 않으며, dialect/driver 차이를 처리할 수 있는 executor가 재정의해야 한다.
     * 반환되는 키는 입력 바인딩과 동일한 순서를 따른다.
     */
    default <T> Flux<T> executeBatchAndReturnGeneratedKeys(
            String sql, List<List<Object>> bindingsList, String idColumn, Class<T> idType) {
        return Flux.error(new UnsupportedOperationException(
                "executeBatchAndReturnGeneratedKeys must be overridden"));
    }
}
