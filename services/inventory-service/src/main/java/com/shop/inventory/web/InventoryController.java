package com.shop.inventory.web;

import com.shop.common.security.AuthenticatedUser;
import com.shop.common.security.Roles;
import com.shop.inventory.domain.Inventory;
import com.shop.inventory.domain.InventoryRepository;
import com.shop.inventory.domain.InventoryTransaction;
import com.shop.inventory.domain.InventoryTransactionRepository;
import com.shop.inventory.service.InventoryService;
import com.shop.support.web.Access;
import com.shop.support.web.ApiException;
import com.shop.support.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final String[] STAFF = {Roles.ADMIN, Roles.WAREHOUSE_MANAGER, Roles.PRODUCT_MANAGER, Roles.ANALYST};

    private final InventoryRepository inventories;
    private final InventoryTransactionRepository transactions;
    private final InventoryService service;

    public InventoryController(InventoryRepository inventories, InventoryTransactionRepository transactions,
                               InventoryService service) {
        this.inventories = inventories;
        this.transactions = transactions;
        this.service = service;
    }

    public record InventoryDto(long productId, String productName, int available, int reserved,
                               int lowStockThreshold, boolean lowStock, Instant updatedAt) {
        static InventoryDto from(Inventory i) {
            return new InventoryDto(i.getProductId(), i.getProductName(), i.getAvailable(), i.getReserved(),
                    i.getLowStockThreshold(), i.isLowStock(), i.getUpdatedAt());
        }
    }

    public record AvailabilityDto(long productId, int available, boolean inStock) {
    }

    public record TransactionDto(long id, Long orderId, String type, int quantity, String note, Instant createdAt) {
        static TransactionDto from(InventoryTransaction t) {
            return new TransactionDto(t.getId(), t.getOrderId(), t.getType().name(), t.getQuantity(), t.getNote(),
                    t.getCreatedAt());
        }
    }

    public record RestockRequest(@Min(1) @Max(100000) int quantity, @Size(max = 200) String note) {
    }

    public record ThresholdRequest(@Min(0) @Max(100000) int threshold) {
    }

    /** Công khai: số lượng khả dụng cho trang sản phẩm. */
    @GetMapping("/availability")
    public List<AvailabilityDto> availability(@RequestParam List<Long> productIds) {
        if (productIds.size() > 200) {
            throw ApiException.badRequest("Tối đa 200 sản phẩm mỗi lần");
        }
        return inventories.findAllById(productIds).stream()
                .map(i -> new AvailabilityDto(i.getProductId(), i.getAvailable(), i.getAvailable() > 0))
                .toList();
    }

    @GetMapping
    public PageResponse<InventoryDto> list(AuthenticatedUser user,
                                           @RequestParam(defaultValue = "false") boolean lowStock,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "50") int size) {
        Access.requireAnyRole(user, STAFF);
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return PageResponse.of(lowStock ? inventories.findLowStock(pr) : inventories.findAllByOrderByProductIdAsc(pr),
                InventoryDto::from);
    }

    @GetMapping("/{productId}/transactions")
    public PageResponse<TransactionDto> history(AuthenticatedUser user, @PathVariable long productId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        Access.requireAnyRole(user, STAFF);
        return PageResponse.of(transactions.findByProductIdOrderByIdDesc(productId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))), TransactionDto::from);
    }

    @PostMapping("/{productId}/restock")
    public InventoryDto restock(AuthenticatedUser user, @PathVariable long productId,
                                @Valid @RequestBody RestockRequest req) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.WAREHOUSE_MANAGER);
        return InventoryDto.from(service.restock(productId, req.quantity(), req.note(), user.email()));
    }

    @PutMapping("/{productId}/threshold")
    public InventoryDto threshold(AuthenticatedUser user, @PathVariable long productId,
                                  @Valid @RequestBody ThresholdRequest req) {
        Access.requireAnyRole(user, Roles.ADMIN, Roles.WAREHOUSE_MANAGER);
        return InventoryDto.from(service.changeThreshold(productId, req.threshold()));
    }
}
