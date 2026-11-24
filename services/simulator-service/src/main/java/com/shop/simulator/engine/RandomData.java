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

    private static final List<String> GOOD = List.of("Sản phẩm đúng mô tả, giao nhanh.", "Chất lượng tốt so với giá.",
            "Đóng gói cẩn thận, sẽ ủng hộ tiếp.", "Dùng ổn định sau một tuần.", "Rất hài lòng!");
    private static final List<String> MEH = List.of("Tạm ổn, giao hơi chậm.", "Chất lượng trung bình.",
            "Đúng giá tiền, không có gì nổi bật.");
    private static final List<String> BAD = List.of("Không giống ảnh minh họa.", "Hàng lỗi nhẹ, đang chờ đổi.",
            "Giao trễ, đóng gói sơ sài.");
    private static final List<String> QUESTIONS = List.of("Sản phẩm bảo hành bao lâu ạ?", "Có xuất hóa đơn VAT không?",
            "Có màu khác không shop?", "Bao lâu thì giao tới Hà Nội?", "Hàng chính hãng không ạ?");

    /** Số sao lệch về phía tích cực như thực tế: phần lớn 4-5 sao. */
    static int rating() {
        int r = rnd().nextInt(100);
        return r < 55 ? 5 : r < 80 ? 4 : r < 90 ? 3 : r < 95 ? 2 : 1;
    }

    static String reviewComment(int rating) {
        return pick(rating >= 4 ? GOOD : rating == 3 ? MEH : BAD);
    }

    static String question() {
        return pick(QUESTIONS);
    }

    static long thinkMillis(int min, int max) {
        return min + rnd().nextInt(Math.max(1, max - min));
    }
}
