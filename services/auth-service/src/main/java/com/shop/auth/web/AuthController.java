package com.shop.auth.web;

import com.shop.auth.service.AuthService;
import com.shop.auth.service.AuthService.TokenResponse;
import com.shop.auth.service.AuthService.UserDto;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.support.web.Access;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    public record RegisterRequest(@NotBlank @Email @Size(max = 255) String email,
                                  @NotBlank @Size(min = 6, max = 72, message = "Mật khẩu từ 6 đến 72 ký tự") String password,
                                  @NotBlank @Size(max = 150) String fullName,
                                  // Mã giới thiệu (tùy chọn) – Loyalty Service kiểm tra và thưởng khi đủ điều kiện
                                  @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9-]*$", message = "Mã giới thiệu không hợp lệ")
                                  String referralCode) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record RolesRequest(@NotEmpty Set<String> roles) {
    }

    public record StatusRequest(boolean enabled) {
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        return service.register(req.email(), req.password(), req.fullName(), req.referralCode());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return service.login(req.email(), req.password());
    }

    /** JWT là stateless: client chỉ cần xóa token. Endpoint tồn tại để thống nhất API. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
    }

    @GetMapping("/me")
    public UserDto me(AuthenticatedUser user) {
        return service.me(user.userId());
    }

    // ------------------------------------------- Quản trị (ADMIN)

    @GetMapping("/users")
    public PageResponse<UserDto> users(AuthenticatedUser user, @RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        Access.requireAnyRole(user, Roles.ADMIN);
        return service.list(q, page, size);
    }

    @PutMapping("/users/{id}/roles")
    public UserDto changeRoles(AuthenticatedUser user, @PathVariable long id, @Valid @RequestBody RolesRequest req) {
        Access.requireAnyRole(user, Roles.ADMIN);
        return service.changeRoles(user, id, req.roles());
    }

    @PutMapping("/users/{id}/status")
    public UserDto changeStatus(AuthenticatedUser user, @PathVariable long id, @RequestBody StatusRequest req) {
        Access.requireAnyRole(user, Roles.ADMIN);
        return service.setEnabled(user, id, req.enabled());
    }
}
