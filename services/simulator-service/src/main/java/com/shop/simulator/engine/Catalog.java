package com.shop.simulator.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Danh mục sản phẩm mà khách ảo nhìn thấy, với độ "hot" khác nhau:
 * trọng số theo phân phối kiểu Zipf → vài sản phẩm bán rất chạy, phần còn lại bán lai rai
 * (giống thực tế, giúp biểu đồ "Top sản phẩm" có ý nghĩa).
 */
public final class Catalog {

    public record Item(long id, String sku, String name, double price, Long categoryId) {
    }

    private final List<Item> items;
    private final double[] cumulative;

    private Catalog(List<Item> items) {
        this.items = List.copyOf(items);
        this.cumulative = new double[items.size()];
        double sum = 0;
        for (int i = 0; i < items.size(); i++) {
            sum += 1.0 / Math.pow(i + 1, 0.9);
            cumulative[i] = sum;
        }
    }

    /** Xáo trộn thứ tự một lần → mỗi lần khởi động có một bộ "sản phẩm hot" khác nhau. */
    public static Catalog of(List<Item> items) {
        List<Item> shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled);
        return new Catalog(shuffled);
    }

    public static Catalog empty() {
        return new Catalog(List.of());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int size() {
        return items.size();
    }

    public List<Item> items() {
        return items;
    }

    /** Chọn sản phẩm theo trọng số độ phổ biến. */
    public Item pickPopular() {
        double r = ThreadLocalRandom.current().nextDouble() * cumulative[cumulative.length - 1];
        for (int i = 0; i < cumulative.length; i++) {
            if (r <= cumulative[i]) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }

    /** Chọn ngẫu nhiên đều (dùng khi "lướt xem"). */
    public Item pickAny() {
        return items.get(ThreadLocalRandom.current().nextInt(items.size()));
    }
}
