package com.shop.user.service;

import com.shop.common.event.EventEnvelope;
import com.shop.common.event.payload.UserEvents.UserRegistered;
import com.shop.common.security.AuthenticatedUser;
import com.shop.support.outbox.IdempotencyGuard;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import com.shop.user.domain.UserAddress;
import com.shop.user.domain.UserAddressRepository;
import com.shop.user.domain.UserProfile;
import com.shop.user.domain.UserProfileRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class UserProfileService {

    public record AddressDto(long id, String label, String recipient, String phone, String line, String city,
                             boolean isDefault, String fullText) {
        static AddressDto from(UserAddress a) {
            return new AddressDto(a.getId(), a.getLabel(), a.getRecipient(), a.getPhone(), a.getLine(), a.getCity(),
                    a.isDefault(), a.fullText());
        }
    }

    public record ProfileDto(long userId, String email, String fullName, String phone, LocalDate dateOfBirth,
                             String avatarUrl, List<AddressDto> addresses) {
    }

    public record AddressRequest(String label, String recipient, String phone, String line, String city,
                                 boolean makeDefault) {
    }

    private final UserProfileRepository profiles;
    private final UserAddressRepository addresses;
    private final IdempotencyGuard guard;

    public UserProfileService(UserProfileRepository profiles, UserAddressRepository addresses, IdempotencyGuard guard) {
        this.profiles = profiles;
        this.addresses = addresses;
        this.guard = guard;
    }

    @Transactional
    public void onUserRegistered(EventEnvelope env, UserRegistered e) {
        if (!guard.firstTime(env, "user-service") || profiles.existsById(e.userId())) {
            return;
        }
        profiles.save(new UserProfile(e.userId(), e.email(), e.fullName()));
    }

    @Transactional
    public ProfileDto me(AuthenticatedUser user) {
        UserProfile p = profileOf(user);
        return toDto(p);
    }

    @Transactional
    public ProfileDto update(AuthenticatedUser user, String fullName, String phone, LocalDate dob, String avatarUrl) {
        UserProfile p = profileOf(user);
        p.update(fullName, phone, dob, avatarUrl);
        return toDto(p);
    }

    @Transactional
    public AddressDto saveAddress(AuthenticatedUser user, Long addressId, AddressRequest req) {
        profileOf(user);
        List<UserAddress> mine = addresses.findByUserIdOrderByIdAsc(user.userId());
        UserAddress address;
        if (addressId == null) {
            if (mine.size() >= 10) {
                throw ApiException.badRequest("Tối đa 10 địa chỉ");
            }
            address = new UserAddress(user.userId());
        } else {
            address = mine.stream().filter(a -> a.getId().equals(addressId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Không tìm thấy địa chỉ"));
        }
        address.update(req.label(), req.recipient(), req.phone(), req.line(), req.city());
        if (req.makeDefault() || mine.isEmpty()) {
            mine.forEach(a -> a.setDefault(false));
            address.setDefault(true);
        }
        return AddressDto.from(addresses.save(address));
    }

    @Transactional
    public void deleteAddress(AuthenticatedUser user, long addressId) {
        UserAddress a = addresses.findById(addressId)
                .filter(x -> x.getUserId().equals(user.userId()))
                .orElseThrow(() -> ApiException.notFound("Không tìm thấy địa chỉ"));
        addresses.delete(a);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProfileDto> list(int page, int size) {
        return PageResponse.of(profiles.findAllByOrderByUserIdAsc(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), this::toDto);
    }

    /** Hồ sơ có thể chưa tồn tại nếu sự kiện UserRegistered chưa tới → tạo tạm từ thông tin JWT. */
    private UserProfile profileOf(AuthenticatedUser user) {
        return profiles.findById(user.userId()).orElseGet(() ->
                profiles.save(new UserProfile(user.userId(), user.email(), user.email())));
    }

    private ProfileDto toDto(UserProfile p) {
        return new ProfileDto(p.getUserId(), p.getEmail(), p.getFullName(), p.getPhone(), p.getDateOfBirth(),
                p.getAvatarUrl(), addresses.findByUserIdOrderByIdAsc(p.getUserId()).stream()
                .map(AddressDto::from).toList());
    }
}
