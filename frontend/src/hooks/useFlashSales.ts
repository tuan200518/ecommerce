import { useEffect, useState } from 'react';
import { api } from '../api/client';
import type { FlashSale, FlashSaleItem } from '../api/types';

export interface FlashOffer {
  sale: FlashSale;
  item: FlashSaleItem;
}

// Dùng chung giữa các trang: tải tối đa 1 lần / 30 giây cho cả ứng dụng
let cache: { at: number; data: FlashSale[] } | null = null;
let inflight: Promise<FlashSale[]> | null = null;

function load(): Promise<FlashSale[]> {
  if (cache && Date.now() - cache.at < 30_000) return Promise.resolve(cache.data);
  if (!inflight) {
    inflight = api.get<FlashSale[]>('/api/promotions/flash-sales/active')
      .then((data) => {
        cache = { at: Date.now(), data };
        return data;
      })
      .catch(() => [] as FlashSale[]) // Promotion Service chưa chạy → hiển thị giá thường
      .finally(() => { inflight = null; });
  }
  return inflight;
}

/** Flash sale đang diễn ra + sắp diễn ra. */
export function useFlashSales(): FlashSale[] {
  const [sales, setSales] = useState<FlashSale[]>(cache?.data ?? []);
  useEffect(() => {
    let alive = true;
    const refresh = () => load().then((d) => alive && setSales(d));
    refresh();
    const t = window.setInterval(refresh, 30_000);
    return () => { alive = false; window.clearInterval(t); };
  }, []);
  return sales;
}

/** productId → ưu đãi flash sale ĐANG diễn ra. */
export function useFlashOffers(): Record<number, FlashOffer> {
  const sales = useFlashSales();
  const map: Record<number, FlashOffer> = {};
  for (const sale of sales) {
    if (sale.status !== 'ACTIVE') continue;
    for (const item of sale.items) {
      const current = map[item.productId];
      if (!current || item.salePrice < current.item.salePrice) map[item.productId] = { sale, item };
    }
  }
  return map;
}
