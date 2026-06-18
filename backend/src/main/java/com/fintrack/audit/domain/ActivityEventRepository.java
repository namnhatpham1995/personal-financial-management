package com.fintrack.audit.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ActivityEventRepository extends MongoRepository<ActivityEvent, String> {

    Page<ActivityEvent> findByUserIdOrderByTsDesc(Long userId, Pageable pageable);
}
