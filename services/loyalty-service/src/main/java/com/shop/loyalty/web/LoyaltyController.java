package com.shop.loyalty.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.loyalty.service.LoyaltyService;
import com.shop.loyalty.web.LoyaltyDtos.AccountDto;
import com.shop.loyalty.web.LoyaltyDtos.RedeemResultDto;
import com.shop.loyalty.web.LoyaltyDtos.ReferralCheckDto;
import com.shop.loyalty.web.LoyaltyDtos.RewardDto;
import com.shop.loyalty.web.LoyaltyDtos.TierDto;
import com.shop.loyalty.web.LoyaltyDtos.TransactionDto;
import com.shop.support.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/loyalty")
public class LoyaltyController {

    private final LoyaltyService service;

    public LoyaltyController(LoyaltyService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public AccountDto me(AuthenticatedUser user) {
        return service.me(user);
    }

    @GetMapping("/me/transactions")
    public PageResponse<TransactionDto> transactions(AuthenticatedUser user,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return service.myTransactions(user, page, size);
    }

    /** Công khai: kiểm tra mã giới thiệu ở form đăng ký. */
    @GetMapping("/referrals/{code}")
    public ReferralCheckDto checkReferral(@PathVariable String code) {
        return service.checkReferral(code);
    }

    @GetMapping("/tiers")
    public List<TierDto> tiers() {
        return service.tiers();
    }

    /** Công khai: danh mục phần thưởng đổi điểm. */
    @GetMapping("/rewards")
    public List<RewardDto> rewards() {
        return service.rewards();
    }

    @PostMapping("/rewards/{rewardId}/redeem")
    public RedeemResultDto redeem(AuthenticatedUser user, @PathVariable String rewardId) {
        return service.redeem(user, rewardId);
    }

    @GetMapping("/admin/accounts")
    public PageResponse<AccountDto> top(AuthenticatedUser user,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.topAccounts(user, page, size);
    }
}
