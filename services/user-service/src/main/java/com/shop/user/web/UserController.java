package com.shop.user.web;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.EventTypes;
import com.shop.common.event.Topics;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.support.kafka.EventReader;
import com.shop.support.web.Access;
import com.shop.support.web.PageResponse;
import com.shop.user.service.UserProfileService;
import com.shop.user.service.UserProfileService.AddressDto;
import com.shop.user.service.UserProfileService.AddressRequest;
import com.shop.user.service.UserProfileService.ProfileDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserProfileService service;
    private final EventReader reader;

    public UserController(UserProfileService service, EventReader reader) {
        this.service = service;
        this.reader = reader;
    }

    public record ProfileRequest(@NotBlank @Size(max = 150) String fullName,
                                 @Pattern(regexp = "^$|^[0-9+ ]{8,20}$", message = "Số điện thoại không hợp lệ") String phone,
                                 @Past LocalDate dateOfBirth,
                                 @Size(max = 1000) String avatarUrl) {
    }

    public record AddressBody(@Size(max = 50) String label,
                              @NotBlank @Size(max = 150) String recipient,
                              @NotBlank @Pattern(regexp = "^[0-9+ ]{8,20}$", message = "Số điện thoại không hợp lệ") String phone,
                              @NotBlank @Size(max = 300) String line,
                              @NotBlank @Size(max = 100) String city,
                              boolean makeDefault) {
        AddressRequest toRequest() {
            return new AddressRequest(label, recipient, phone, line, city, makeDefault);
        }
    }

    /** Tạo hồ sơ khi có tài khoản mới (consumer của topic user-events). */
    @KafkaListener(id = "user-service.user-events", topics = Topics.USER_EVENTS)
    public void onUserEvent(String message) {
        EventEnvelope env = reader.read(message);
        if (env.is(EventTypes.USER_REGISTERED)) {
            service.onUserRegistered(env, reader.payload(env, UserRegistered.class));
        }
    }

    @GetMapping("/me")
    public ProfileDto me(AuthenticatedUser user) {
        return service.me(user);
    }

    @PutMapping("/me")
    public ProfileDto update(AuthenticatedUser user, @Valid @RequestBody ProfileRequest req) {
        return service.update(user, req.fullName(), req.phone(), req.dateOfBirth(), req.avatarUrl());
    }

    @PostMapping("/me/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public AddressDto addAddress(AuthenticatedUser user, @Valid @RequestBody AddressBody req) {
        return service.saveAddress(user, null, req.toRequest());
    }

    @PutMapping("/me/addresses/{id}")
    public AddressDto updateAddress(AuthenticatedUser user, @PathVariable long id, @Valid @RequestBody AddressBody req) {
        return service.saveAddress(user, id, req.toRequest());
    }

    @DeleteMapping("/me/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddress(AuthenticatedUser user, @PathVariable long id) {
        service.deleteAddress(user, id);
    }

    @GetMapping
    public PageResponse<ProfileDto> list(AuthenticatedUser user, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        Access.requireAnyRole(user, Roles.ADMIN);
        return service.list(page, size);
    }
}
