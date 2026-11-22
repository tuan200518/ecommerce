package com.shop.simulator.engine;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Dữ liệu ngẫu nhiên kiểu Việt Nam cho khách hàng ảo. */
final class RandomData {

    private static final List<String> LAST = List.of("Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ",
            "Võ", "Đặng", "Bùi", "Đỗ", "Hồ", "Ngô", "Dương", "Lý");
    private static final List<String> MIDDLE = List.of("Văn", "Thị", "Minh", "Ngọc", "Thanh", "Hữu", "Quốc", "Thu",
            "Gia", "Đức", "Hoài", "Bảo");
    private static final List<String> FIRST = List.of("An", "Bình", "Châu", "Dũng", "Giang", "Hà", "Hải", "Hùng",
            "Khang", "Lan", "Linh", "Long", "Mai", "Nam", "Nhung", "Phúc", "Quân", "Sơn", "Tâm", "Thảo", "Trang",
            "Tú", "Uyên", "Vy", "Yến");
    private static final List<String> STREETS = List.of("Lê Lợi", "Nguyễn Huệ", "Trần Hưng Đạo", "Hai Bà Trưng",
            "Lý Thường Kiệt", "Điện Biên Phủ", "Cách Mạng Tháng 8", "Nguyễn Trãi", "Phan Đình Phùng", "Võ Văn Tần");
    private static final List<String> CITIES = List.of("TP. Hồ Chí Minh", "Hà Nội", "Đà Nẵng", "Hải Phòng",
            "Cần Thơ", "Nha Trang", "Huế", "Biên Hòa", "Vũng Tàu", "Quy Nhơn");
    private static final List<String> SEARCH = List.of("tai nghe", "laptop", "điện thoại", "áo", "giày", "sạc",
            "chuột", "bàn phím", "nồi", "bình", "balo", "đèn");

    private RandomData() {
    }

    static ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }

    static boolean chance(double probability) {
        return rnd().nextDouble() < probability;
    }

    static <T> T pick(List<T> list) {
        return list.get(rnd().nextInt(list.size()));
    }

    static String fullName() {
        return pick(LAST) + " " + pick(MIDDLE) + " " + pick(FIRST);
    }

    static String phone() {
        return "09" + (10_000_000 + rnd().nextInt(89_999_999));
    }

    static String address(String name) {
        return name + " - " + phone() + " - " + (1 + rnd().nextInt(300)) + " " + pick(STREETS) + ", "
                + "Quận " + (1 + rnd().nextInt(12)) + ", " + pick(CITIES);
    }

    static String searchTerm() {
        return pick(SEARCH);
    }

    static long thinkMillis(int min, int max) {
        return min + rnd().nextInt(Math.max(1, max - min));
    }
}
