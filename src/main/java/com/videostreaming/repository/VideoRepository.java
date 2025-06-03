package com.videostreaming.repository;

import com.videostreaming.model.Video;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface VideoRepository extends ReactiveCrudRepository<Video, Long> {

    Mono<Video> findByFilename(String filename);

    Flux<Video> findByStatus(String status);

    Flux<Video> findAllByOrderByCreatedAtDesc();
}