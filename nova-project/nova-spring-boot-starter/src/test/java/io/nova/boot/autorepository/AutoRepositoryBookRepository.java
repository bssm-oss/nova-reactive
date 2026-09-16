package io.nova.boot.autorepository;

import io.nova.spring.data.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AutoRepositoryBookRepository extends ReactiveCrudRepository<AutoRepositoryBook, Long> {

    Mono<AutoRepositoryBook> findByTitle(String title);
}
