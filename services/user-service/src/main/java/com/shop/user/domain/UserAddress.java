package com.shop.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_addresses", indexes = @Index(name = "idx_address_user", columnList = "userId"))
public class UserAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 50)
    private String label;

    @Column(nullable = false, length = 150)
    private String recipient;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(nullable = false, length = 300)
    private String line;

    @Column(nullable = false, length = 100)
    private String city;

    private boolean isDefault;

    protected UserAddress() {
    }

    public UserAddress(long userId) {
        this.userId = userId;
    }

    public void update(String label, String recipient, String phone, String line, String city) {
        this.label = label;
        this.recipient = recipient;
        this.phone = phone;
        this.line = line;
        this.city = city;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public String fullText() {
        return recipient + " - " + phone + " - " + line + ", " + city;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getPhone() {
        return phone;
    }

    public String getLine() {
        return line;
    }

    public String getCity() {
        return city;
    }

    public boolean isDefault() {
        return isDefault;
    }
}
