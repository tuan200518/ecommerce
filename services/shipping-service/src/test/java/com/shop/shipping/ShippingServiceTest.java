package com.shop.shipping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.payload.OrderEvents.OrderStatusChanged;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.shipping.domain.ShipmentRepository;
import com.shop.shipping.service.ShippingService;
import com.shop.shipping.web.ShippingDtos.ShipmentDto;
import com.shop.support.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:shipping;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.kafka.listener.auto-startup=false",
        "shop.outbox.relay-enabled=false",
        "shop.kafka.create-topics=false"
})
class ShippingServiceTest {

    private static final AuthenticatedUser WAREHOUSE = new AuthenticatedUser(3, "warehouse@shop.local",
            Set.of(Roles.WAREHOUSE_MANAGER));
    private static final AuthenticatedUser OWNER = new AuthenticatedUser(77, "c@shop.local", Set.of(Roles.CUSTOMER));
    private static final AuthenticatedUser OTHER = new AuthenticatedUser(78, "d@shop.local", Set.of(Roles.CUSTOMER));

    @Autowired
    ShippingService service;
    @Autowired
    ShipmentRepository shipments;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    private EventEnvelope orderEvent(String type, long orderId, String status, String total) {
        return EventEnvelope.create(mapper, type, "test", String.valueOf(orderId),
                new OrderStatusChanged(orderId, 77, "X", status, new BigDecimal(total), "lý do", List.of(),
                        "12 Lê Lợi, Q1"));
    }

    private int outbox(String type, long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type = ? AND message_key = ?",
                Integer.class, type, String.valueOf(orderId));
    }

    @Test
    void confirmedOrder_createsShipmentOnce_andStaffDrivesItToDelivered() {
        EventEnvelope confirmed = orderEvent(EventTypes.ORDER_CONFIRMED, 10, "CONFIRMED", "24990000");
        service.handleOrderEvent(confirmed);
        service.handleOrderEvent(confirmed); // gửi lại

        ShipmentDto s = service.byOrder(OWNER, 10);
        assertThat(shipments.count()).isGreaterThanOrEqualTo(1);
        assertThat(s.status()).isEqualTo("CREATED");
        assertThat(s.carrier()).isEqualTo("GHN"); // đơn giá trị cao
        assertThat(s.trackingNumber()).isEqualTo("GHN000000010");
        assertThat(s.address()).isEqualTo("12 Lê Lợi, Q1");
        assertThat(outbox(EventTypes.SHIPMENT_CREATED, 10)).isEqualTo(1);

        // Không bỏ qua bước
        assertThatThrownBy(() -> service.updateStatus(WAREHOUSE, s.id(), "DELIVERED", null))
                .isInstanceOf(ApiException.class);
        // Khách không thao tác được
        assertThatThrownBy(() -> service.updateStatus(OWNER, s.id(), "PACKED", null)).isInstanceOf(ApiException.class);

        service.changeCarrier(WAREHOUSE, s.id(), "VTP");
        service.updateStatus(WAREHOUSE, s.id(), "PACKED", null);
        service.changeCarrier(WAREHOUSE, s.id(), "GHN"); // vẫn đổi được khi chưa bàn giao
        service.updateStatus(WAREHOUSE, s.id(), "HANDED_OVER", null);
        assertThatThrownBy(() -> service.changeCarrier(WAREHOUSE, s.id(), "GHTK")).isInstanceOf(ApiException.class);
        service.updateStatus(WAREHOUSE, s.id(), "IN_TRANSIT", "Shipper Minh");
        ShipmentDto done = service.updateStatus(WAREHOUSE, s.id(), "DELIVERED", null);

        assertThat(done.status()).isEqualTo("DELIVERED");
        assertThat(done.deliveredAt()).isNotNull();
        assertThat(done.timeline()).extracting(t -> t.status())
                .containsSubsequence("CREATED", "PACKED", "HANDED_OVER", "IN_TRANSIT", "DELIVERED");
        assertThat(outbox(EventTypes.SHIPMENT_DELIVERED, 10)).isEqualTo(1);
        assertThat(outbox(EventTypes.SHIPMENT_STATUS_CHANGED, 10)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void customerCannotSeeSomeoneElsesShipment() {
        service.handleOrderEvent(orderEvent(EventTypes.ORDER_CONFIRMED, 20, "CONFIRMED", "990000"));
        assertThat(service.byOrder(OWNER, 20).carrier()).isEqualTo("GHTK"); // orderId chẵn, giá trị thấp
        assertThatThrownBy(() -> service.byOrder(OTHER, 20)).isInstanceOf(ApiException.class);
    }

    @Test
    void cancelledOrder_cancelsOpenShipment() {
        service.handleOrderEvent(orderEvent(EventTypes.ORDER_CONFIRMED, 30, "CONFIRMED", "990000"));
        service.handleOrderEvent(orderEvent(EventTypes.ORDER_CANCELLED, 30, "CANCELLED", "990000"));
        assertThat(service.byOrder(OWNER, 30).status()).isEqualTo("CANCELLED");
        assertThat(service.byOrder(OWNER, 30).nextStatuses()).isEmpty();
    }

    @Test
    void legacyOrderStatusChanges_areSyncedWithoutPublishingEvents() {
        // Đọc lại lịch sử: đơn cũ đã được chuyển SHIPPING/DELIVERED trực tiếp ở Order Service
        service.handleOrderEvent(orderEvent(EventTypes.ORDER_CONFIRMED, 40, "CONFIRMED", "990000"));
        service.handleOrderEvent(orderEvent(EventTypes.ORDER_STATUS_CHANGED, 40, "SHIPPING", "990000"));
        service.handleOrderEvent(orderEvent(EventTypes.ORDER_STATUS_CHANGED, 40, "DELIVERED", "990000"));
        assertThat(service.byOrder(OWNER, 40).status()).isEqualTo("DELIVERED");
        assertThat(outbox(EventTypes.SHIPMENT_STATUS_CHANGED, 40)).isZero();
        assertThat(outbox(EventTypes.SHIPMENT_DELIVERED, 40)).isZero();
    }
}
