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

    public JsonNode ordersByStatus(String token, String status) {
        return call(HttpMethod.GET, "/api/orders?status=" + status + "&size=100", null, token);
    }

    public JsonNode updateOrderStatus(String token, long orderId, String status) {
        return call(HttpMethod.PUT, "/api/orders/" + orderId + "/status",
                Map.of("status", status, "note", "Cập nhật bởi nhân viên giao hàng ảo"), token);
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
