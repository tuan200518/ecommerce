import { FormEvent, useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../../api/client';
import type { AdminVoucher, FlashSale, Page, Product, PromotionStats } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ErrorBox, Loading, Modal, Pagination } from '../../components/Common';
import { SoldBar } from '../../components/Promotions';
import { dateTime, money, number, VOUCHER_TYPE_LABEL, voucherValueText } from '../../utils/format';

type Tab = 'overview' | 'flash' | 'vouchers';

const STATUS_LABEL: Record<string, string> = {
  UPCOMING: 'Sắp diễn ra', ACTIVE: 'Đang diễn ra', ENDED: 'Đã kết thúc', CANCELLED: 'Đã hủy',
};
const SOURCE_LABEL: Record<string, string> = { ADMIN: 'Quản trị', LOYALTY: 'Đổi điểm', SYSTEM: 'Hệ thống' };

/** datetime-local (giờ máy) ↔ ISO */
const toLocalInput = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
const toIso = (local: string) => new Date(local).toISOString();

function errorText(e: unknown): string {
  return e instanceof ApiError && e.errors ? Object.values(e.errors).join('. ') : (e as Error).message;
}

/** Quản trị khuyến mãi (Promotion Service): thống kê, chiến dịch flash sale, mã giảm giá. */
export default function PromotionsAdminPage() {
  const { hasRole } = useAuth();
  const canWrite = hasRole('ADMIN', 'PRODUCT_MANAGER');
  const [tab, setTab] = useState<Tab>('overview');

  return (
    <div>
      <h2>🎁 Khuyến mãi</h2>
      <div className="tabs">
        <button className={`tab ${tab === 'overview' ? 'active' : ''}`} onClick={() => setTab('overview')}>Tổng quan</button>
        <button className={`tab ${tab === 'flash' ? 'active' : ''}`} onClick={() => setTab('flash')}>Flash sale</button>
        <button className={`tab ${tab === 'vouchers' ? 'active' : ''}`} onClick={() => setTab('vouchers')}>Mã giảm giá</button>
      </div>
      {tab === 'overview' && <Overview />}
      {tab === 'flash' && <FlashSales canWrite={canWrite} />}
      {tab === 'vouchers' && <Vouchers canWrite={canWrite} />}
    </div>
  );
}

function Overview() {
  const [stats, setStats] = useState<PromotionStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const load = () => api.get<PromotionStats>('/api/promotions/admin/stats', { days: 30 }).then(setStats).catch((e) => setError(e.message));
    load();
    const t = window.setInterval(load, 10_000);
    return () => window.clearInterval(t);
  }, []);
  if (!stats) return error ? <ErrorBox error={error} /> : <Loading />;
  return (
    <>
      <div className="kpis">
        <div className="kpi card"><div className="muted small">Đơn có ưu đãi (30 ngày)</div><div className="kpi-value">{number(stats.ordersWithPromotion)}</div></div>
        <div className="kpi card"><div className="muted small">Giảm giá flash sale</div><div className="kpi-value">{money(stats.flashSaleDiscount)}</div></div>
        <div className="kpi card"><div className="muted small">Giảm giá voucher</div><div className="kpi-value">{money(stats.voucherDiscount)}</div></div>
        <div className="kpi card"><div className="muted small">Flash sale đang chạy</div><div className="kpi-value">{number(stats.activeFlashSales)}</div></div>
        <div className="kpi card"><div className="muted small">Mã công khai còn hiệu lực</div><div className="kpi-value">{number(stats.activeVouchers)}</div></div>
      </div>
      <div className="grid-2">
        <div className="card">
          <h3>Mã được dùng nhiều nhất</h3>
          {stats.topVouchers.length === 0 ? <div className="empty">Chưa có đơn dùng mã</div> : (
            <table className="table compact">
              <thead><tr><th>Mã</th><th>Số đơn</th><th>Tổng giảm</th></tr></thead>
              <tbody>
                {stats.topVouchers.map((v) => (
                  <tr key={v.label}><td className="mono">{v.label}</td><td>{number(v.count)}</td><td>{money(v.amount)}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="card">
          <h3>Tiến độ bán flash sale</h3>
          {stats.flashSaleItems.length === 0 ? <div className="empty">Không có flash sale đang chạy</div> : stats.flashSaleItems.map((i) => (
            <div key={i.id} style={{ marginBottom: 10 }}>
              <div className="row space-between small"><span>{i.productName}</span><span>{money(i.salePrice)} <span className="price-old">{money(i.listPrice)}</span></span></div>
              <SoldBar sold={i.sold} quota={i.quota} />
              <div className="muted tiny">Đã bán {i.sold}/{i.quota} · còn {i.remaining} · tối đa {i.perUserLimit}/khách</div>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}

// ------------------------------------------------------------------ Flash sale

interface ItemDraft { productId: string; salePrice: string; quota: string; perUserLimit: string }

function FlashSales({ canWrite }: { canWrite: boolean }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<FlashSale> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => {
    api.get<Page<FlashSale>>('/api/promotions/admin/flash-sales', { page, size: 10 }).then(setData).catch((e) => setError(e.message));
  }, [page]);
  useEffect(load, [load]);

  const cancel = (f: FlashSale) => {
    if (!window.confirm(`Hủy chiến dịch "${f.name}"? Đơn đã đặt vẫn giữ giá ưu đãi.`)) return;
    api.post<FlashSale>(`/api/promotions/admin/flash-sales/${f.id}/cancel`).then(load).catch((e) => setError(e.message));
  };

  return (
    <div className="card">
      <div className="row space-between">
        <h3>Chiến dịch flash sale</h3>
        {canWrite && <button className="btn btn-primary" onClick={() => setCreating(true)}>+ Tạo flash sale</button>}
      </div>
      <ErrorBox error={error} />
      {!data ? <Loading /> : data.content.length === 0 ? <div className="empty">Chưa có chiến dịch</div> : (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Chiến dịch</th><th>Thời gian</th><th>Trạng thái</th><th>Sản phẩm</th><th /></tr></thead>
            <tbody>
              {data.content.map((f) => (
                <tr key={f.id} className={f.status === 'CANCELLED' || f.status === 'ENDED' ? 'disabled' : ''}>
                  <td><strong>{f.name}</strong><div className="muted tiny">tạo bởi {f.createdBy}</div></td>
                  <td className="small">{dateTime(f.startsAt)}<br />→ {dateTime(f.endsAt)}</td>
                  <td><span className={`badge badge-flash-${f.status.toLowerCase()}`}>{STATUS_LABEL[f.status]}</span></td>
                  <td className="small">
                    {f.items.map((i) => (
                      <div key={i.id}>{i.productName}: <strong>{money(i.salePrice)}</strong> <span className="muted">({i.sold}/{i.quota}, ≤{i.perUserLimit}/khách)</span></div>
                    ))}
                  </td>
                  <td>{canWrite && (f.status === 'ACTIVE' || f.status === 'UPCOMING') && <button className="btn btn-danger" onClick={() => cancel(f)}>Hủy</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      {creating && <FlashSaleForm onClose={() => setCreating(false)} onCreated={() => { setCreating(false); load(); }} />}
    </div>
  );
}

function FlashSaleForm({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const now = new Date();
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [startsAt, setStartsAt] = useState(toLocalInput(now));
  const [endsAt, setEndsAt] = useState(toLocalInput(new Date(now.getTime() + 2 * 3600_000)));
  const [items, setItems] = useState<ItemDraft[]>([{ productId: '', salePrice: '', quota: '50', perUserLimit: '2' }]);
  const [products, setProducts] = useState<Product[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.get<Page<Product>>('/api/products', { size: 100 }).then((p) => setProducts(p.content.filter((x) => x.active))).catch(() => undefined);
  }, []);

  const update = (idx: number, patch: Partial<ItemDraft>) => setItems(items.map((it, i) => (i === idx ? { ...it, ...patch } : it)));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    api.post('/api/promotions/admin/flash-sales', {
      name, description: description || null, startsAt: toIso(startsAt), endsAt: toIso(endsAt),
      items: items.map((i) => ({ productId: Number(i.productId), salePrice: Number(i.salePrice), quota: Number(i.quota), perUserLimit: Number(i.perUserLimit) })),
    }).then(onCreated).catch((err) => setError(errorText(err))).finally(() => setBusy(false));
  };

  return (
    <Modal title="Tạo flash sale" onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <label>Tên chiến dịch<input value={name} onChange={(e) => setName(e.target.value)} required maxLength={150} placeholder="Flash Sale 12.12" /></label>
        <label>Mô tả<input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={500} /></label>
        <div className="row">
          <label style={{ flex: 1 }}>Bắt đầu<input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required /></label>
          <label style={{ flex: 1 }}>Kết thúc<input type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} required /></label>
        </div>
        <div className="small"><strong>Sản phẩm</strong> <span className="muted tiny">(giá flash sale phải thấp hơn giá bán; một sản phẩm không được nằm trong 2 chiến dịch trùng giờ)</span></div>
        {items.map((it, idx) => {
          const p = products.find((x) => String(x.id) === it.productId);
          return (
            <div key={idx} className="row" style={{ alignItems: 'flex-end' }}>
              <label style={{ flex: 3 }}>Sản phẩm
                <select value={it.productId} onChange={(e) => update(idx, { productId: e.target.value })} required>
                  <option value="">— chọn —</option>
                  {products.map((x) => <option key={x.id} value={x.id}>{x.name} ({money(x.price)})</option>)}
                </select>
              </label>
              <label style={{ flex: 2 }}>Giá sale
                <input type="number" min={1000} step={1000} value={it.salePrice} required
                       placeholder={p ? String(Math.floor((p.price * 0.8) / 1000) * 1000) : ''}
                       onChange={(e) => update(idx, { salePrice: e.target.value })} />
              </label>
              <label style={{ flex: 1 }}>Suất<input type="number" min={1} value={it.quota} onChange={(e) => update(idx, { quota: e.target.value })} required /></label>
              <label style={{ flex: 1 }}>≤/khách<input type="number" min={1} max={100} value={it.perUserLimit} onChange={(e) => update(idx, { perUserLimit: e.target.value })} required /></label>
              {items.length > 1 && <button type="button" className="btn btn-ghost" onClick={() => setItems(items.filter((_, i) => i !== idx))}>✕</button>}
            </div>
          );
        })}
        <button type="button" className="btn btn-ghost" onClick={() => setItems([...items, { productId: '', salePrice: '', quota: '50', perUserLimit: '2' }])}>+ Thêm sản phẩm</button>
        <ErrorBox error={error} />
        <div className="row">
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Đang tạo…' : 'Tạo chiến dịch'}</button>
          <button type="button" className="btn btn-ghost" onClick={onClose}>Hủy</button>
        </div>
      </form>
    </Modal>
  );
}

// ------------------------------------------------------------------ Voucher

function Vouchers({ canWrite }: { canWrite: boolean }) {
  const [q, setQ] = useState('');
  const [source, setSource] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AdminVoucher> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const load = useCallback(() => {
    api.get<Page<AdminVoucher>>('/api/promotions/admin/vouchers', { q: q || undefined, source: source || undefined, page, size: 20 })
      .then(setData).catch((e) => setError(e.message));
  }, [q, source, page]);
  useEffect(load, [load]);

  const toggle = (v: AdminVoucher) => {
    api.put<AdminVoucher>(`/api/promotions/admin/vouchers/${v.id}/status`, { active: !v.active }).then(load).catch((e) => setError(e.message));
  };

  return (
    <div className="card">
      <div className="row space-between">
        <div className="row">
          <input placeholder="Tìm mã / mô tả" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
          <select value={source} onChange={(e) => { setSource(e.target.value); setPage(0); }}>
            <option value="">Mọi nguồn</option>
            {Object.entries(SOURCE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </div>
        {canWrite && <button className="btn btn-primary" onClick={() => setCreating(true)}>+ Tạo mã giảm giá</button>}
      </div>
      <ErrorBox error={error} />
      {!data ? <Loading /> : data.content.length === 0 ? <div className="empty">Không có mã nào</div> : (
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>Mã</th><th>Ưu đãi</th><th>Điều kiện</th><th>Đã dùng</th><th>Hiệu lực</th><th>Nguồn</th><th /></tr></thead>
            <tbody>
              {data.content.map((v) => (
                <tr key={v.id} className={v.active ? '' : 'disabled'}>
                  <td><strong className="mono">{v.code}</strong><div className="muted tiny">{v.description}</div></td>
                  <td className="small">{voucherValueText(v)}<div className="muted tiny">{VOUCHER_TYPE_LABEL[v.type]}</div></td>
                  <td className="small">{v.minOrderAmount > 0 ? `Đơn từ ${money(v.minOrderAmount)}` : 'Mọi đơn'}<div className="muted tiny">≤ {v.perUserLimit} lần/khách</div></td>
                  <td className="small">{number(v.used)}{v.usageLimit != null ? ` / ${number(v.usageLimit)}` : ''}</td>
                  <td className="small">{dateTime(v.startsAt)}<br />→ {dateTime(v.endsAt)}</td>
                  <td className="small">{SOURCE_LABEL[v.source] ?? v.source}{v.ownerUserId != null && <div className="muted tiny">user #{v.ownerUserId}</div>}</td>
                  <td>{canWrite && <button className={`btn ${v.active ? 'btn-ghost' : ''}`} onClick={() => toggle(v)}>{v.active ? 'Tắt' : 'Bật'}</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      {creating && <VoucherForm onClose={() => setCreating(false)} onCreated={() => { setCreating(false); load(); }} />}
    </div>
  );
}

function VoucherForm({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const now = new Date();
  const [f, setF] = useState({
    code: '', description: '', type: 'PERCENT', value: '10', maxDiscount: '50000', minOrderAmount: '0',
    startsAt: toLocalInput(now), endsAt: toLocalInput(new Date(now.getTime() + 30 * 86_400_000)),
    usageLimit: '', perUserLimit: '1',
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const set = (patch: Partial<typeof f>) => setF({ ...f, ...patch });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    api.post('/api/promotions/admin/vouchers', {
      code: f.code, description: f.description, type: f.type,
      value: f.type === 'FREE_SHIPPING' ? 0 : Number(f.value),
      maxDiscount: f.type === 'PERCENT' && f.maxDiscount ? Number(f.maxDiscount) : null,
      minOrderAmount: Number(f.minOrderAmount || 0),
      startsAt: toIso(f.startsAt), endsAt: toIso(f.endsAt),
      usageLimit: f.usageLimit ? Number(f.usageLimit) : null,
      perUserLimit: Number(f.perUserLimit || 1),
    }).then(onCreated).catch((err) => setError(errorText(err))).finally(() => setBusy(false));
  };

  return (
    <Modal title="Tạo mã giảm giá" onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <div className="row">
          <label style={{ flex: 1 }}>Mã<input value={f.code} onChange={(e) => set({ code: e.target.value.toUpperCase() })} required minLength={3} maxLength={32} pattern="[A-Za-z0-9_-]+" placeholder="SALE12" /></label>
          <label style={{ flex: 1 }}>Loại
            <select value={f.type} onChange={(e) => set({ type: e.target.value })}>
              {Object.entries(VOUCHER_TYPE_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
        </div>
        <label>Mô tả hiển thị cho khách<input value={f.description} onChange={(e) => set({ description: e.target.value })} required maxLength={255} /></label>
        {f.type !== 'FREE_SHIPPING' && (
          <div className="row">
            <label style={{ flex: 1 }}>{f.type === 'PERCENT' ? 'Phần trăm giảm' : 'Số tiền giảm (₫)'}
              <input type="number" min={f.type === 'PERCENT' ? 1 : 1000} max={f.type === 'PERCENT' ? 100 : undefined} value={f.value} onChange={(e) => set({ value: e.target.value })} required />
            </label>
            {f.type === 'PERCENT' && <label style={{ flex: 1 }}>Giảm tối đa (₫)<input type="number" min={0} value={f.maxDiscount} onChange={(e) => set({ maxDiscount: e.target.value })} /></label>}
          </div>
        )}
        <div className="row">
          <label style={{ flex: 1 }}>Đơn tối thiểu (₫)<input type="number" min={0} value={f.minOrderAmount} onChange={(e) => set({ minOrderAmount: e.target.value })} /></label>
          <label style={{ flex: 1 }}>Tổng lượt (trống = không giới hạn)<input type="number" min={1} value={f.usageLimit} onChange={(e) => set({ usageLimit: e.target.value })} /></label>
          <label style={{ flex: 1 }}>Lượt/khách<input type="number" min={1} max={100} value={f.perUserLimit} onChange={(e) => set({ perUserLimit: e.target.value })} required /></label>
        </div>
        <div className="row">
          <label style={{ flex: 1 }}>Bắt đầu<input type="datetime-local" value={f.startsAt} onChange={(e) => set({ startsAt: e.target.value })} required /></label>
          <label style={{ flex: 1 }}>Kết thúc<input type="datetime-local" value={f.endsAt} onChange={(e) => set({ endsAt: e.target.value })} required /></label>
        </div>
        <ErrorBox error={error} />
        <div className="row">
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Đang tạo…' : 'Tạo mã'}</button>
          <button type="button" className="btn btn-ghost" onClick={onClose}>Hủy</button>
        </div>
      </form>
    </Modal>
  );
}
