package com.shop.simulator.engine;

/** Một khách hàng ảo với "tính cách" mua sắm riêng. */
public record VirtualCustomer(String email, String password, String name, Persona persona) {

    public enum Persona {
        /** Tiết kiệm: 1 món, số lượng 1. */
        THRIFTY(1, 1, 1),
        /** Bình thường: 1-2 món, mỗi món 1-2 cái. */
        REGULAR(1, 2, 2),
        /** Chịu chi: 2-4 món, mỗi món 1-3 cái. */
        BIG_SPENDER(2, 4, 3);

        private final int minLines;
        private final int maxLines;
        private final int maxQty;

        Persona(int minLines, int maxLines, int maxQty) {
            this.minLines = minLines;
            this.maxLines = maxLines;
            this.maxQty = maxQty;
        }

        public int lines() {
            return minLines + RandomData.rnd().nextInt(maxLines - minLines + 1);
        }

        public int quantity() {
            return 1 + RandomData.rnd().nextInt(maxQty);
        }

        public static Persona random() {
            int r = RandomData.rnd().nextInt(10);
            return r < 3 ? THRIFTY : r < 8 ? REGULAR : BIG_SPENDER;
        }
    }
}
