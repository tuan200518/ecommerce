package com.shop.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Câu trả lời: từ nhân viên (staff = true) hoặc từ khách đã mua sản phẩm. */
@Entity
@Table(name = "answers", indexes = @Index(name = "idx_answers_question", columnList = "questionId"))
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long questionId;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 120)
    private String authorName;

    private boolean staff;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    protected Answer() {
    }

    public Answer(long questionId, long userId, String authorName, boolean staff, String content) {
        this.questionId = questionId;
        this.userId = userId;
        this.authorName = authorName;
        this.staff = staff;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public boolean isStaff() {
        return staff;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
