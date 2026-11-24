package com.shop.gateway;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.regex.Pattern;

/**
 * Xác định IP thật của client cho rate limit và nhật ký kiểm toán.
 * <p>
 * Chỉ tin header {@code X-Real-IP} khi request đến từ một proxy trong mạng nội bộ (Nginx của frontend nằm cùng
 * mạng Docker). Client ngoài Internet gửi thẳng header này sẽ bị bỏ qua và dùng địa chỉ kết nối thực tế.
 */
public final class ClientIp {

    public static final String REAL_IP_HEADER = "X-Real-IP";
    private static final Pattern IP_CHARS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientIp() {
    }

    public static String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        InetAddress address = remote == null ? null : remote.getAddress();
        String remoteIp = address == null ? "unknown" : address.getHostAddress();
        if (address != null && isInternal(address)) {
            String real = request.getHeaders().getFirst(REAL_IP_HEADER);
            if (real != null && IP_CHARS.matcher(real.trim()).matches()) {
                return real.trim();
            }
        }
        return remoteIp;
    }

    static boolean isInternal(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
            return true;
        }
        // IPv6 Unique Local Address fc00::/7
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xfe) == 0xfc;
    }
}
