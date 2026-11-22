package com.shop.product.config;

import com.shop.product.domain.CategoryRepository;
import com.shop.product.service.ProductService;
import com.shop.product.web.ProductDtos.CategoryDto;
import com.shop.product.web.ProductDtos.CategoryRequest;
import com.shop.product.web.ProductDtos.ProductRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tạo dữ liệu mẫu khi database trống. Mỗi sản phẩm tạo ra phát ProductCreated (kèm tồn kho ban đầu)
 * → Inventory / Cart / Order Service tự xây dựng dữ liệu của mình qua Kafka.
 */
@Component
@ConditionalOnProperty(prefix = "shop.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final CategoryRepository categories;
    private final ProductService service;

    public DataSeeder(CategoryRepository categories, ProductService service) {
        this.categories = categories;
        this.service = service;
    }

    private record Seed(String sku, String name, String category, long price, int stock, String description) {
    }

    @Override
    public void run(ApplicationArguments args) {
        if (categories.count() > 0) {
            return;
        }
        List<CategoryDto> cats = List.of(
                service.createCategory(new CategoryRequest("Điện thoại", "dien-thoai", "Smartphone các hãng")),
                service.createCategory(new CategoryRequest("Laptop", "laptop", "Laptop văn phòng, gaming")),
                service.createCategory(new CategoryRequest("Phụ kiện", "phu-kien", "Tai nghe, chuột, bàn phím...")),
                service.createCategory(new CategoryRequest("Thời trang", "thoi-trang", "Quần áo, giày dép")),
                service.createCategory(new CategoryRequest("Nhà cửa", "nha-cua", "Đồ gia dụng")));

        List<Seed> seeds = List.of(
                new Seed("PHN-001", "Điện thoại Nova X1 128GB", "dien-thoai", 7_990_000, 60, "Màn hình 6.5\" AMOLED, pin 5000mAh"),
                new Seed("PHN-002", "Điện thoại Nova X1 Pro 256GB", "dien-thoai", 11_490_000, 40, "Camera 108MP, sạc nhanh 67W"),
                new Seed("PHN-003", "Điện thoại Lite S 64GB", "dien-thoai", 3_290_000, 120, "Nhỏ gọn, pin bền"),
                new Seed("PHN-004", "Điện thoại Fold Z", "dien-thoai", 32_990_000, 15, "Màn hình gập 7.6\""),
                new Seed("LAP-001", "Laptop AirBook 14 M-series", "laptop", 24_990_000, 30, "14 inch, 16GB RAM, SSD 512GB"),
                new Seed("LAP-002", "Laptop Gaming Raptor 15", "laptop", 28_490_000, 25, "RTX 4060, 144Hz"),
                new Seed("LAP-003", "Laptop Văn phòng Edu 13", "laptop", 12_990_000, 50, "Nhẹ 1.2kg, pin 12 giờ"),
                new Seed("ACC-001", "Tai nghe không dây Buds Pro", "phu-kien", 1_990_000, 150, "Chống ồn chủ động"),
                new Seed("ACC-002", "Bàn phím cơ K87 RGB", "phu-kien", 1_490_000, 80, "Switch tactile, hot-swap"),
                new Seed("ACC-003", "Chuột không dây Silent M2", "phu-kien", 390_000, 200, "Click êm, 2.4GHz + Bluetooth"),
                new Seed("ACC-004", "Sạc nhanh GaN 65W", "phu-kien", 690_000, 180, "3 cổng USB-C/A"),
                new Seed("ACC-005", "Pin dự phòng 20000mAh", "phu-kien", 590_000, 12, "Sạc nhanh PD 22.5W"),
                new Seed("FAS-001", "Áo thun cotton basic", "thoi-trang", 199_000, 300, "100% cotton, nhiều màu"),
                new Seed("FAS-002", "Giày sneaker trắng", "thoi-trang", 890_000, 90, "Đế cao su, êm chân"),
                new Seed("FAS-003", "Balo laptop chống nước", "thoi-trang", 450_000, 110, "Ngăn laptop 15.6\""),
                new Seed("FAS-004", "Áo khoác gió", "thoi-trang", 359_000, 8, "Chống nước nhẹ"),
                new Seed("HOM-001", "Nồi chiên không dầu 5L", "nha-cua", 1_590_000, 70, "Công suất 1500W"),
                new Seed("HOM-002", "Máy lọc không khí Pure 300", "nha-cua", 3_490_000, 35, "Màng HEPA H13"),
                new Seed("HOM-003", "Bình giữ nhiệt 750ml", "nha-cua", 249_000, 250, "Giữ nhiệt 12 giờ"),
                new Seed("HOM-004", "Đèn bàn LED chống cận", "nha-cua", 429_000, 9, "3 chế độ ánh sáng"),
                new Seed("FLS-001", "[FLASH SALE] Tai nghe Buds Mini", "phu-kien", 99_000, 20, "Sản phẩm dùng cho kịch bản Flash Sale"));

        for (Seed s : seeds) {
            Long categoryId = cats.stream().filter(c -> c.slug().equals(s.category())).findFirst()
                    .map(CategoryDto::id).orElse(null);
            String image = "https://picsum.photos/seed/" + s.sku() + "/600/400";
            service.create(new ProductRequest(s.sku(), s.name(), s.description(), BigDecimal.valueOf(s.price()),
                    categoryId, image, List.of(image, "https://picsum.photos/seed/" + s.sku() + "-2/600/400"),
                    s.stock()));
        }
        log.info("Đã tạo {} danh mục và {} sản phẩm mẫu", cats.size(), seeds.size());
    }
}
