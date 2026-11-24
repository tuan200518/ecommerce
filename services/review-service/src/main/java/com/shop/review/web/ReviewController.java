package com.shop.review.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.review.service.ReviewService;
import com.shop.review.web.ReviewDtos.ContentRequest;
import com.shop.review.web.ReviewDtos.EligibilityDto;
import com.shop.review.web.ReviewDtos.QuestionDto;
import com.shop.review.web.ReviewDtos.ReviewDto;
import com.shop.review.web.ReviewDtos.ReviewRequest;
import com.shop.review.web.ReviewDtos.SummaryDto;
import com.shop.review.web.ReviewDtos.VisibilityRequest;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * GET /api/reviews/products/** là công khai (Gateway); các thao tác ghi cần đăng nhập;
 * /api/reviews/admin/** dành cho ADMIN / PRODUCT_MANAGER.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    // ------------------------------------------------ Đánh giá

    @GetMapping("/products/{productId}")
    public PageResponse<ReviewDto> list(@PathVariable long productId,
                                        @RequestParam(required = false) Integer rating,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        return service.productReviews(productId, rating, page, size);
    }

    @GetMapping("/products/{productId}/summary")
    public SummaryDto summary(@PathVariable long productId) {
        return service.summary(productId);
    }

    @GetMapping("/products/{productId}/eligibility")
    public EligibilityDto eligibility(Optional<AuthenticatedUser> user, @PathVariable long productId) {
        return service.eligibility(user, productId);
    }

    @PostMapping("/products/{productId}")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto submit(AuthenticatedUser user, @PathVariable long productId, @Valid @RequestBody ReviewRequest req) {
        return service.submit(user, productId, req);
    }

    // ------------------------------------------------ Hỏi đáp

    @GetMapping("/products/{productId}/questions")
    public List<QuestionDto> questions(@PathVariable long productId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.productQuestions(productId, page, size);
    }

    @PostMapping("/products/{productId}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionDto ask(AuthenticatedUser user, @PathVariable long productId, @Valid @RequestBody ContentRequest req) {
        return service.ask(user, productId, req.content());
    }

    @PostMapping("/questions/{questionId}/answers")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionDto answer(AuthenticatedUser user, @PathVariable long questionId,
                              @Valid @RequestBody ContentRequest req) {
        return service.answer(user, questionId, req.content());
    }

    // ------------------------------------------------ Kiểm duyệt

    @GetMapping("/admin/reviews")
    public PageResponse<ReviewDto> moderation(AuthenticatedUser user,
                                              @RequestParam(required = false) Boolean visible,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.moderationList(user, visible, page, size);
    }

    @PutMapping("/admin/reviews/{id}/visibility")
    public ReviewDto visibility(AuthenticatedUser user, @PathVariable long id, @Valid @RequestBody VisibilityRequest req) {
        return service.setVisibility(user, id, req.visible(), req.note());
    }

    @GetMapping("/admin/questions")
    public PageResponse<QuestionDto> questionQueue(AuthenticatedUser user,
                                                   @RequestParam(defaultValue = "false") boolean answered,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return service.questionQueue(user, answered, page, size);
    }
}
