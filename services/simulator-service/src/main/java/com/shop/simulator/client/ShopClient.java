package com.shop.simulator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.simulator.SimulatorProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gọi hệ thống qua API Gateway giống hệt trình duyệt của khách hàng thật
 * (JWT, REST công khai và REST cần đăng nhập).
 */
@Component
public class ShopClient {

    private final RestClient http;
    private final ObjectMapper mapper;

    public ShopClient(RestClient.Builder builder, SimulatorProperties props, ObjectMapper mapper) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = builder.baseUrl(props.gatewayUrl()).requestFactory(factory).build();
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ Khách hàng

    public String login(String email, String password) {
        return call(HttpMethod.POST, "/api/auth/login", Map.of("email", email, "password", password), null)
                .path("accessToken").asText();
    }

    public String register(String email, String password, String fullName) {
        return call(HttpMethod.POST, "/api/auth/register",
                Map.of("email", email, "password", password, "fullName", fullName), null)
                .path("accessToken").asText();
    }

    public JsonNode products(String query) {
        return call(HttpMethod.GET, "/api/products?" + query, null, null);
    }

    public JsonNode product(long id) {
        return call(HttpMethod.GET, "/api/products/" + id, null, null);
    }

    public JsonNode availability(long id) {
        return call(HttpMethod.GET, "/api/inventory/availability?productIds=" + id, null, null);
    }

    public JsonNode addToCart(String token, long productId, int quantity) {
        return call(HttpMethod.POST, "/api/cart/items", Map.of("productId", productId, "quantity", quantity), token);
    }

    public JsonNode cart(String token) {
        return call(HttpMethod.GET, "/api/cart", null, token);
    }

    public JsonNode placeOrder(String token, List<Map<String, Object>> items, String address, String paymentMethod) {
        return call(HttpMethod.POST, "/api/orders",
                Map.of("items", items, "shippingAddress", address, "paymentMethod", paymentMethod), token);
    }

    public JsonNode cancelOrder(String token, long orderId) {
        return call(HttpMethod.POST, "/api/orders/" + orderId + "/cancel", Map.of(), token);
    }

    public JsonNode notifications(String token) {
        return call(HttpMethod.GET, "/api/notifications?size=5", null, token);
    }

    // ------------------------------------------------------------------ Nhân viên

    public JsonNode lowStock(String token) {
        return call(HttpMethod.GET, "/api/inventory?lowStock=true&size=200", null, token);
    }

    public JsonNode restock(String token, long productId, int quantity) {
        return call(HttpMethod.POST, "/api/inventory/" + productId + "/restock",
                Map.of("quantity", quantity, "note", "Nhân viên kho ảo nhập hàng"), token);
    }

    public JsonNode shipments(String token, String status) {
        return call(HttpMethod.GET, "/api/shipments?status=" + status + "&size=50", null, token);
    }

    public JsonNode updateShipment(String token, long shipmentId, String status) {
        return call(HttpMethod.PUT, "/api/shipments/" + shipmentId + "/status",
                Map.of("status", status, "note", "Nhân viên vận chuyển ảo"), token);
    }

    public JsonNode riskCases(String token, String status) {
        return call(HttpMethod.GET, "/api/fraud/cases?status=" + status + "&size=50", null, token);
    }

    public JsonNode reviewRisk(String token, long orderId, boolean approved, String note) {
        return call(HttpMethod.POST, "/api/fraud/cases/" + orderId + "/review",
                Map.of("approved", approved, "note", note), token);
    }

    // ------------------------------------------------------------------ Sau mua hàng

    public JsonNode myOrders(String token) {
        return call(HttpMethod.GET, "/api/orders/my?size=10", null, token);
    }

    public JsonNode reviewEligibility(String token, long productId) {
        return call(HttpMethod.GET, "/api/reviews/products/" + productId + "/eligibility", null, token);
    }

    public JsonNode submitReview(String token, long productId, int rating, String comment) {
        return call(HttpMethod.POST, "/api/reviews/products/" + productId,
                Map.of("rating", rating, "comment", comment), token);
    }

    public JsonNode askQuestion(String token, long productId, String content) {
        return call(HttpMethod.POST, "/api/reviews/products/" + productId + "/questions",
                Map.of("content", content), token);
    }

    // ------------------------------------------------------------------ Khuyến mãi / đổi điểm

    public JsonNode flashSales() {
        return call(HttpMethod.GET, "/api/promotions/flash-sales/active", null, null);
    }

    public JsonNode publicVouchers() {
        return call(HttpMethod.GET, "/api/promotions/vouchers/public", null, null);
    }

    public JsonNode quote(String token, List<Map<String, Object>> items, String voucherCode) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        body.put("voucherCode", voucherCode);
        return call(HttpMethod.POST, "/api/promotions/quote", body, token);
    }

    public JsonNode placeOrder(String token, List<Map<String, Object>> items, String address, String paymentMethod,
                               String voucherCode) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("items", items, "shippingAddress", address,
                "paymentMethod", paymentMethod));
        if (voucherCode != null) {
            body.put("voucherCode", voucherCode);
        }
        return call(HttpMethod.POST, "/api/orders", body, token);
    }

    public JsonNode loyalty(String token) {
        return call(HttpMethod.GET, "/api/loyalty/me", null, token);
    }

    public JsonNode redeem(String token, String rewardId) {
        return call(HttpMethod.POST, "/api/loyalty/rewards/" + rewardId + "/redeem", Map.of(), token);
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode call(HttpMethod method, String path, Object body, String token) {
        try {
            RestClient.RequestBodySpec spec = http.method(method).uri(path).accept(MediaType.APPLICATION_JSON);
            if (token != null) {
                spec.header("Authorization", "Bearer " + token);
            }
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            String json = spec.retrieve().body(String.class);
            return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (RestClientResponseException e) {
            throw new ShopApiException(e.getStatusCode().value(), detail(e));
        } catch (ResourceAccessException e) {
            throw new ShopApiException(0, "Không kết nối được API Gateway: " + e.getMessage());
        } catch (ShopApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ShopApiException(0, e.getMessage());
        }
    }

    private String detail(RestClientResponseException e) {
        try {
            JsonNode node = mapper.readTree(e.getResponseBodyAsString());
            return node.path("detail").asText(e.getStatusText());
        } catch (Exception ignored) {
            return e.getStatusText();
        }
    }
}
