package com.shop.shipping.web;

import com.shop.shipping.domain.Shipment;
import com.shop.shipping.domain.ShipmentEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ShippingDtos {

    private ShippingDtos() {
    }

    public record TimelineDto(String status, String note, Instant at) {
        static TimelineDto from(ShipmentEvent e) {
            return new TimelineDto(e.getStatus().name(), e.getNote(), e.getAt());
        }
    }

    /** @param nextStatuses các bước nhân viên có thể thao tác tiếp */
    public record ShipmentDto(long id, long orderId, long userId, String address, BigDecimal orderTotal,
                              String carrier, String carrierName, String trackingNumber, BigDecimal fee,
                              String status, List<String> nextStatuses, Instant createdAt, Instant updatedAt,
                              Instant deliveredAt, List<TimelineDto> timeline) {

        public static ShipmentDto from(Shipment s, List<ShipmentEvent> events) {
            return new ShipmentDto(s.getId(), s.getOrderId(), s.getUserId(), s.getAddress(), s.getOrderTotal(),
                    s.getCarrier().name(), s.getCarrier().displayName(), s.getTrackingNumber(), s.getFee(),
                    s.getStatus().name(), s.getStatus().staffNext().stream().map(Enum::name).sorted().toList(),
                    s.getCreatedAt(), s.getUpdatedAt(), s.getDeliveredAt(),
                    events == null ? null : events.stream().map(TimelineDto::from).toList());
        }
    }

    public record StatusRequest(@NotBlank String status, @Size(max = 300) String note) {
    }

    public record CarrierRequest(@NotBlank String carrier) {
    }

    public record CarrierDto(String code, String name, BigDecimal baseFee) {
    }

    public record SummaryDto(Map<String, Long> byStatus, Map<String, Long> byCarrier, List<CarrierDto> carriers) {
    }
}
