package com.shop.promotion.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.promotion.service.PromotionAdminService;
import com.shop.promotion.service.PromotionService;
import com.shop.promotion.web.PromotionDtos.FlashSaleDto;
import com.shop.promotion.web.PromotionDtos.FlashSaleRequest;
import com.shop.promotion.web.PromotionDtos.MyVoucherDto;
import com.shop.promotion.web.PromotionDtos.PublicVoucherDto;
import com.shop.promotion.web.PromotionDtos.QuoteDto;
import com.shop.promotion.web.PromotionDtos.QuoteRequest;
import com.shop.promotion.web.PromotionDtos.StatsDto;
import com.shop.promotion.web.PromotionDtos.StatusRequest;
import com.shop.promotion.web.PromotionDtos.VoucherDto;
import com.shop.promotion.web.PromotionDtos.VoucherRequest;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
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

import java.util.List;

@RestController
@RequestMapping("/api/promotions")
public class PromotionController {

    private final PromotionService service;
    private final PromotionAdminService admin;

    public PromotionController(PromotionService service, PromotionAdminService admin) {
        this.service = service;
        this.admin = admin;
    }

    // --------------------------------------------- Công khai

    /** Flash sale đang diễn ra và sắp diễn ra (7 ngày tới). */
    @GetMapping("/flash-sales/active")
    public List<FlashSaleDto> flashSales() {
        return service.currentFlashSales();
    }

    @GetMapping("/vouchers/public")
    public List<PublicVoucherDto> publicVouchers() {
        return service.publicVouchers();
    }

    // --------------------------------------------- Khách đã đăng nhập

    /** Báo giá ở checkout (Gateway giới hạn tần suất theo người dùng để chống dò mã). */
    @PostMapping("/quote")
    public QuoteDto quote(AuthenticatedUser user, @Valid @RequestBody QuoteRequest req) {
        return service.quote(user, req);
    }

    @GetMapping("/vouchers/mine")
    public List<MyVoucherDto> myVouchers(AuthenticatedUser user) {
        return service.myVouchers(user);
    }

    // --------------------------------------------- Quản trị

    @GetMapping("/admin/flash-sales")
    public PageResponse<FlashSaleDto> listFlashSales(AuthenticatedUser user,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return admin.listFlashSales(user, page, size);
    }

    @PostMapping("/admin/flash-sales")
    @ResponseStatus(HttpStatus.CREATED)
    public FlashSaleDto createFlashSale(AuthenticatedUser user, @Valid @RequestBody FlashSaleRequest req) {
        return admin.createFlashSale(user, req);
    }

    @PostMapping("/admin/flash-sales/{id}/cancel")
    public FlashSaleDto cancelFlashSale(AuthenticatedUser user, @PathVariable long id) {
        return admin.cancelFlashSale(user, id);
    }

    @GetMapping("/admin/vouchers")
    public PageResponse<VoucherDto> listVouchers(AuthenticatedUser user, @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String source,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return admin.listVouchers(user, q, source, page, size);
    }

    @PostMapping("/admin/vouchers")
    @ResponseStatus(HttpStatus.CREATED)
    public VoucherDto createVoucher(AuthenticatedUser user, @Valid @RequestBody VoucherRequest req) {
        return admin.createVoucher(user, req);
    }

    @PutMapping("/admin/vouchers/{id}/status")
    public VoucherDto voucherStatus(AuthenticatedUser user, @PathVariable long id, @RequestBody StatusRequest req) {
        return admin.setVoucherActive(user, id, req.active());
    }

    @GetMapping("/admin/stats")
    public StatsDto stats(AuthenticatedUser user, @RequestParam(defaultValue = "30") int days) {
        return admin.stats(user, days);
    }
}
