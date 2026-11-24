package com.shop.promotion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

/**
 * Một sản phẩm trong flash sale. {@code sold} chỉ được tăng/giảm bằng câu UPDATE có điều kiện
 * (xem {@code PromotionLedger}) để không bao giờ bán vượt {@code quota} khi nhiều đơn tranh nhau.
 */
@Entity
@Table(name = "flash_sale_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_flash_item_product", columnNames = {"flash_sale_id", "productId"}),
        indexes = @Index(name = "idx_flash_item_product", columnList = "productId"))
public class FlashSaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "flash_sale_id")
    private FlashSale flashSale;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String productName;

    /** Giá niêm yết lúc tạo chiến dịch (để hiển thị % giảm). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal listPrice;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal salePrice;

    private int quota;

    private int sold;

    /** Số sản phẩm tối đa mỗi tài khoản được mua với giá flash sale (chống gom hàng). */
    private int perUserLimit;

    protected FlashSaleItem() {
    }

    FlashSaleItem(FlashSale flashSale, long productId, String productName, BigDecimal listPrice, BigDecimal salePrice,
                  int quota, int perUserLimit) {
        this.flashSale = flashSale;
        this.productId = productId;
        this.productName = productName;
        this.listPrice = listPrice;
        this.salePrice = salePrice;
        this.quota = quota;
        this.perUserLimit = perUserLimit;
    }

    public int remaining() {
        return Math.max(0, quota - sold);
    }

    public Long getId() {
        return id;
    }

    public FlashSale getFlashSale() {
        return flashSale;
    }

    public Long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public BigDecimal getListPrice() {
        return listPrice;
    }

    public BigDecimal getSalePrice() {
        return salePrice;
    }

    public int getQuota() {
        return quota;
    }

    public int getSold() {
        return sold;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }
}
