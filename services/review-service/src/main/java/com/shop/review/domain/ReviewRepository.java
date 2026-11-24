package com.shop.review.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByProductIdAndVisibleTrueOrderByCreatedAtDesc(long productId, Pageable pageable);

    Page<Review> findByProductIdAndVisibleTrueAndRatingOrderByCreatedAtDesc(long productId, int rating, Pageable pageable);

    Page<Review> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<Review> findByVisibleOrderByCreatedAtDesc(boolean visible, Pageable pageable);

    boolean existsByUserIdAndProductId(long userId, long productId);

    @Query("select r.rating, count(r) from Review r where r.productId = :productId and r.visible = true group by r.rating")
    List<Object[]> ratingDistribution(long productId);
}
