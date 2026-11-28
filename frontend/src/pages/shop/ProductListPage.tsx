import { FormEvent, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Availability, Category, Page, Product } from '../../api/types';
import { Empty, ErrorBox, Loading, Pagination } from '../../components/Common';
import { money } from '../../utils/format';
import { FlashPrice } from '../../components/Promotions';
import { useFlashOffers } from '../../hooks/useFlashSales';
import { RecommendationStrip } from '../../components/Recommendations';

export default function ProductListPage() {
  const [params, setParams] = useSearchParams();
  const [categories, setCategories] = useState<Category[]>([]);
  const [data, setData] = useState<Page<Product> | null>(null);
  const [stock, setStock] = useState<Record<number, Availability>>({});
  const offers = useFlashOffers();
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState(params.get('q') ?? '');

  const query = {
    q: params.get('q') ?? undefined,
    categoryId: params.get('categoryId') ?? undefined,
    minPrice: params.get('minPrice') ?? undefined,
    maxPrice: params.get('maxPrice') ?? undefined,
    sort: params.get('sort') ?? undefined,
    page: Number(params.get('page') ?? 0),
    size: 12,
  };

  useEffect(() => {
    api.get<Category[]>('/api/categories').then(setCategories).catch(() => undefined);
  }, []);

  useEffect(() => {
    setData(null);
    setError(null);
    api.get<Page<Product>>('/api/products', query)
      .then((page) => {
        setData(page);
        if (page.content.length > 0) {
          api.get<Availability[]>('/api/inventory/availability', { productIds: page.content.map((p) => p.id).join(',') })
            .then((list) => setStock(Object.fromEntries(list.map((a) => [a.productId, a]))))
            .catch(() => undefined);
        }
      })
      .catch((e) => setError(e.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  const update = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    Object.entries(patch).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    if (!('page' in patch)) next.delete('page');
    setParams(next);
  };

  const submitSearch = (e: FormEvent) => {
    e.preventDefault();
    update({ q: q.trim() || undefined });
  };

  return (
    <div className="shop">
      <aside className="filters card">
        <form onSubmit={submitSearch} className="form">
          <label>Tìm kiếm<input value={q} placeholder="Tên, SKU, mô tả…" onChange={(e) => setQ(e.target.value)} /></label>
          <button className="btn btn-primary">Tìm</button>
        </form>
        <h4>Danh mục</h4>
        <div className="cat-list">
          <button className={`chip ${!query.categoryId ? 'active' : ''}`} onClick={() => update({ categoryId: undefined })}>Tất cả</button>
          {categories.map((c) => (
            <button key={c.id} className={`chip ${query.categoryId === String(c.id) ? 'active' : ''}`}
                    onClick={() => update({ categoryId: String(c.id) })}>{c.name}</button>
          ))}
        </div>
        <h4>Khoảng giá</h4>
        <div className="row">
          <input type="number" placeholder="Từ" defaultValue={query.minPrice} onBlur={(e) => update({ minPrice: e.target.value || undefined })} />
          <input type="number" placeholder="Đến" defaultValue={query.maxPrice} onBlur={(e) => update({ maxPrice: e.target.value || undefined })} />
        </div>
        <h4>Sắp xếp</h4>
        <select value={query.sort ?? ''} onChange={(e) => update({ sort: e.target.value || undefined })}>
          <option value="">Mới nhất</option>
          <option value="price_asc">Giá tăng dần</option>
          <option value="price_desc">Giá giảm dần</option>
          <option value="name">Tên A-Z</option>
        </select>
      </aside>

      <section>
        {!query.q && !query.categoryId && !query.minPrice && !query.maxPrice && query.page === 0 && (
          <RecommendationStrip path="for-you" query={{ limit: 8 }}
                               title={(l) => (l.strategy === 'PERSONALIZED' ? '✨ Dành cho bạn' : '🔥 Đang thịnh hành')}
                               subtitle="Gợi ý dựa trên hành vi mua sắm" />
        )}
        <ErrorBox error={error} />
        {!data && !error && <Loading />}
        {data && data.content.length === 0 && <Empty>Không tìm thấy sản phẩm phù hợp.</Empty>}
        {data && (
          <>
            <div className="muted small">{data.totalElements} sản phẩm</div>
            <div className="grid">
              {data.content.map((p) => {
                const s = stock[p.id];
                return (
                  <Link key={p.id} to={`/products/${p.id}`} className="product card">
                    <div className="product-img">
                      {p.imageUrl ? <img src={p.imageUrl} alt={p.name} loading="lazy" /> : <span>📦</span>}
                    </div>
                    <div className="product-body">
                      <div className="muted tiny">{p.categoryName}</div>
                      <div className="product-name">{p.name}</div>
                      <FlashPrice price={p.price} offer={offers[p.id]} />
                      {s && (s.inStock
                        ? <span className={`stock ${s.available <= 10 ? 'low' : ''}`}>{s.available <= 10 ? `Chỉ còn ${s.available}` : 'Còn hàng'}</span>
                        : <span className="stock out">Hết hàng</span>)}
                    </div>
                  </Link>
                );
              })}
            </div>
            <Pagination page={data.page} totalPages={data.totalPages} onChange={(p) => update({ page: String(p) })} />
          </>
        )}
      </section>
    </div>
  );
}
