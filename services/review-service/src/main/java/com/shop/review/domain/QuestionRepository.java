package com.shop.review.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuestionRepository extends JpaRepository<Question, Long> {

    Page<Question> findByProductIdOrderByCreatedAtDesc(long productId, Pageable pageable);

    Page<Question> findByAnsweredOrderByCreatedAtAsc(boolean answered, Pageable pageable);
}
