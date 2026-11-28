import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { RecommendationList, RecommendedProduct } from '../api/types';
import { useFlashOffers } from '../hooks/useFlashSales';
import { FlashPrice } from './Promotions';

type Props = {
  /** Đường dẫn sau /api/recommendations/, vd. "for-you", "products/5/similar" */
  path: string;
  query?: Record<string, string | number | undefined>;
  /** Tiêu đề; có thể phụ thuộc chiến lược (khách mới nhận "thịnh hành" thay vì "dành cho bạn") */
  title: string | ((list: RecommendationList) => string);
  subtitle?: string;
};

/** Ghi nhận lượt bấm (đo CTR + quy đơn hàng về gợi ý); lỗi không ảnh hưởng điều hướng. */
function trackClick(list: RecommendationList, item: RecommendedProduct) {
  api.post('/api/recommendations/clicks', { placement: list.placement, productId: item.productId }).catch(() => undefined);
}

/** Một dải sản phẩm được gợi ý, mỗi thẻ kèm lý do gợi ý. Ẩn khi không có gợi ý hoặc service lỗi. */
export function RecommendationStrip({ path, query, title, subtitle }: Props) {
  const [list, setList] = useState<RecommendationList | null>(null);
  const offers = useFlashOffers();
  const key = `${path}?${JSON.stringify(query ?? {})}`;

  useEffect(() => {
    let cancelled = false;
    setList(null);
    api.get<RecommendationList>(`/api/recommendations/${path}`, query)
      .then((l) => { if (!cancelled) setList(l); })
      .catch(() => { if (!cancelled) setList(null); });
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  if (!list || list.items.length === 0) return null;
  return (
    <section className="rec card">
      <div className="rec-head">
        <h3>{typeof title === 'function' ? title(list) : title}</h3>
        {subtitle && <span className="muted small">{subtitle}</span>}
      </div>
      <div className="rec-strip">
        {list.items.map((item) => (
          <Link key={item.productId} to={`/products/${item.productId}`} className="rec-item"
                onClick={() => trackClick(list, item)}>
            <div className="product-img">
              {item.imageUrl ? <img src={item.imageUrl} alt={item.name} loading="lazy" /> : <span>📦</span>}
            </div>
            <div className="product-body">
              <div className="product-name">{item.name}</div>
              {item.price != null && <FlashPrice price={item.price} offer={offers[item.productId]} />}
              <div className="rec-reason" title={`Điểm gợi ý: ${item.score}`}>💡 {item.reason}</div>
            </div>
          </Link>
        ))}
      </div>
    </section>
  );
}
