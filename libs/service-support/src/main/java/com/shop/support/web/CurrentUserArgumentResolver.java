package com.shop.support.web;

import com.shop.common.security.AuthHeaders;
import com.shop.common.security.AuthenticatedUser;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cho phép controller khai báo tham số {@link AuthenticatedUser} (bắt buộc đăng nhập, thiếu → 401)
 * hoặc {@code Optional<AuthenticatedUser>} (không bắt buộc). Thông tin được dựng từ các header
 * do API Gateway gắn vào sau khi xác thực JWT.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AuthenticatedUser.class.equals(parameter.nestedIfOptional().getNestedParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest request, WebDataBinderFactory binderFactory) {
        AuthenticatedUser user = fromHeaders(request);
        if (Optional.class.equals(parameter.getParameterType())) {
            return Optional.ofNullable(user);
        }
        if (user == null) {
            throw ApiException.unauthorized("Yêu cầu đăng nhập");
        }
        return user;
    }

    private static AuthenticatedUser fromHeaders(NativeWebRequest request) {
        String id = request.getHeader(AuthHeaders.USER_ID);
        if (id == null || id.isBlank()) {
            return null;
        }
        String rolesHeader = request.getHeader(AuthHeaders.USER_ROLES);
        Set<String> roles = rolesHeader == null ? Set.of()
                : Arrays.stream(rolesHeader.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        try {
            return new AuthenticatedUser(Long.parseLong(id.trim()), request.getHeader(AuthHeaders.USER_EMAIL), roles);
        } catch (NumberFormatException e) {
            throw ApiException.unauthorized("Header người dùng không hợp lệ");
        }
    }
}
