package com.shop.review.web;

import com.shop.review.domain.Answer;
import com.shop.review.domain.Question;
import com.shop.review.domain.Review;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record ReviewRequest(
            @Min(value = 1, message = "Số sao từ 1 đến 5") @Max(value = 5, message = "Số sao từ 1 đến 5") int rating,
            @Size(max = 2000, message = "Nội dung tối đa 2000 ký tự") String comment,
            @Size(max = 5, message = "Tối đa 5 ảnh")
            List<@Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "Ảnh phải là URL http(s)") String> images) {
    }

    public record ReviewDto(long id, long productId, String productName, long userId, String authorName, int rating,
                            String comment, List<String> images, boolean visible, String moderationNote,
                            Instant createdAt) {
        public static ReviewDto from(Review r) {
            return new ReviewDto(r.getId(), r.getProductId(), r.getProductName(), r.getUserId(), r.getAuthorName(),
                    r.getRating(), r.getComment(), r.imageList(), r.isVisible(), r.getModerationNote(),
                    r.getCreatedAt());
        }
    }

    public record SummaryDto(long productId, double average, long count, Map<Integer, Long> distribution) {
    }

    /** @param reason lý do không được đánh giá (null nếu được) */
    public record EligibilityDto(boolean canReview, boolean alreadyReviewed, String reason) {
    }

    public record VisibilityRequest(@NotNull Boolean visible, @Size(max = 300) String note) {
    }

    public record ContentRequest(@NotBlank(message = "Vui lòng nhập nội dung") @Size(max = 1000) String content) {
    }

    public record AnswerDto(long id, String authorName, boolean staff, String content, Instant createdAt) {
        public static AnswerDto from(Answer a) {
            return new AnswerDto(a.getId(), a.getAuthorName(), a.isStaff(), a.getContent(), a.getCreatedAt());
        }
    }

    public record QuestionDto(long id, long productId, String authorName, String content, boolean answered,
                              Instant createdAt, List<AnswerDto> answers) {
        public static QuestionDto from(Question q, List<AnswerDto> answers) {
            return new QuestionDto(q.getId(), q.getProductId(), q.getAuthorName(), q.getContent(), q.isAnswered(),
                    q.getCreatedAt(), answers);
        }
    }
}
