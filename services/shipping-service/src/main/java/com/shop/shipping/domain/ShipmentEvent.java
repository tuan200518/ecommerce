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

import java.time.Instant;

/** Một mốc trên timeline vận chuyển (hiển thị cho khách ở trang chi tiết đơn). */
@Entity
@Table(name = "shipment_events", indexes = @Index(name = "idx_shipment_events_shipment", columnList = "shipmentId"))
public class ShipmentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shipmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShipmentStatus status;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private Instant at;

    protected ShipmentEvent() {
    }

    public ShipmentEvent(long shipmentId, ShipmentStatus status, String note) {
        this.shipmentId = shipmentId;
        this.status = status;
        this.note = note;
        this.at = Instant.now();
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public Instant getAt() {
        return at;
    }
}
