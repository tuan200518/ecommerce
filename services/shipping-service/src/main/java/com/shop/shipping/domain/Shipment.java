package com.shop.shipping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "shipments",
        uniqueConstraints = @UniqueConstraint(name = "uk_shipments_order", columnNames = "orderId"),
        indexes = {
                @Index(name = "idx_shipments_status", columnList = "status,updatedAt"),
                @Index(name = "idx_shipments_user", columnList = "userId")
        })
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(length = 500)
    private String address;

    @Column(precision = 14, scale = 2)
    private BigDecimal orderTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Carrier carrier;

    @Column(nullable = false, length = 32)
    private String trackingNumber;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal fee;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShipmentStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant deliveredAt;

    @Version
    private long version;

    protected Shipment() {
    }

    public Shipment(long orderId, long userId, String address, BigDecimal orderTotal) {
        this.orderId = orderId;
        this.userId = userId;
        this.address = address;
        this.orderTotal = orderTotal;
        this.status = ShipmentStatus.CREATED;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        assignCarrier(Carrier.defaultFor(orderId, orderTotal));
    }

    public void assignCarrier(Carrier carrier) {
        this.carrier = carrier;
        this.trackingNumber = carrier.trackingNumberFor(orderId);
        this.fee = carrier.baseFee();
        this.updatedAt = Instant.now();
    }

    /** @return trạng thái cũ */
    public ShipmentStatus transitionTo(ShipmentStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Không thể chuyển vận đơn từ " + status + " sang " + next);
        }
        ShipmentStatus previous = status;
        status = next;
        updatedAt = Instant.now();
        if (next == ShipmentStatus.DELIVERED) {
            deliveredAt = updatedAt;
        }
        return previous;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAddress() {
        return address;
    }

    public BigDecimal getOrderTotal() {
        return orderTotal;
    }

    public Carrier getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
