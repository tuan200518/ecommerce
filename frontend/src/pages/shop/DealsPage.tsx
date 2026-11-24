import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { MyVoucher, PublicVoucher } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { Countdown, SoldBar, VoucherCard } from '../../components/Promotions';
import { useFlashSales } from '../../hooks/useFlashSales';
import { dateTime, money } from '../../utils/format';

/** Trang ưu đãi: flash sale đang/sắp diễn ra, mã giảm giá công khai và voucher cá nhân. */
export default function DealsPage() {
  const sales = useFlashSales();
  const { user } = useAuth();
  const [vouchers, setVouchers] = useState<PublicVoucher[]>([]);
  const [mine, setMine] = useState<MyVoucher[]>([]);
  const [copied, setCopied] = useState<string | null>(null);

  useEffect(() => {
    api.get<PublicVoucher[]>('/api/promotions/vouchers/public').then(setVouchers).catch(() => undefined);
    if (user) api.get<MyVoucher[]>('/api/promotions/vouchers/mine').then(setMine).catch(() => undefined);
  }, [user]);

  const copy = (code: string) => {
    navigator.clipboard?.writeText(code).then(() => {
      setCopied(code);
      window.setTimeout(() => setCopied(null), 1500);
    }).catch(() => undefined);
  };

  return (
    <div>
      <h2>🔥 Khuyến mãi</h2>
      {sales.length === 0 && <div className="card empty">Hiện chưa có chương trình flash sale nào.</div>}
      {sales.map((sale) => (
        <div key={sale.id} className="card flash-sale">
          <div className="row space-between">
            <div>
              <h3 style={{ margin: 0 }}>⚡ {sale.name}</h3>
              {sale.description && <div className="muted small">{sale.description}</div>}
            </div>
            <div className="small">
              {sale.status === 'ACTIVE'
                ? <>Kết thúc sau <Countdown to={sale.endsAt} /></>
                : <>Bắt đầu sau <Countdown to={sale.startsAt} /> <span className="muted tiny">({dateTime(sale.startsAt)})</span></>}
            </div>
          </div>
          <div className="grid">
            {sale.items.map((i) => (
              <Link key={i.id} to={`/products/${i.productId}`} className="product card">
                <div className="product-img">
                  {i.imageUrl ? <img src={i.imageUrl} alt={i.productName} loading="lazy" /> : <span>📦</span>}
                  <span className="badge badge-flash corner">-{i.discountPercent}%</span>
                </div>
                <div className="product-body">
                  <div className="product-name">{i.productName}</div>
                  <div className="row" style={{ gap: 6, alignItems: 'baseline' }}>
                    <span className="price sale">{money(i.salePrice)}</span>
                    <span className="price-old">{money(i.listPrice)}</span>
                  </div>
                  {sale.status === 'ACTIVE'
                    ? (i.remaining > 0 ? <SoldBar sold={i.sold} quota={i.quota} /> : <span className="stock out">Đã hết suất</span>)
                    : <span className="muted tiny">{i.quota} suất · tối đa {i.perUserLimit}/khách</span>}
                </div>
              </Link>
            ))}
          </div>
        </div>
      ))}

      <div className="grid-2">
        <div className="card">
          <h3>🎟️ Mã giảm giá cho mọi khách</h3>
          {vouchers.length === 0 ? <div className="empty">Chưa có mã nào</div> : (
            <div className="voucher-list">
              {vouchers.map((v) => (
                <VoucherCard key={v.code} v={v} onUse={copy} action={copied === v.code ? 'Đã chép ✓' : 'Sao chép'} />
              ))}
            </div>
          )}
          <p className="muted tiny">Nhập mã ở bước thanh toán. Mỗi đơn dùng tối đa một mã.</p>
        </div>
        <div className="card">
          <h3>Voucher của tôi</h3>
          {!user ? <div className="empty"><Link to="/login">Đăng nhập</Link> để xem voucher cá nhân</div>
            : mine.length === 0
              ? <div className="empty">Chưa có voucher cá nhân – <Link to="/loyalty">đổi điểm thưởng</Link> để nhận</div>
              : <div className="voucher-list">{mine.map((v) => <VoucherCard key={v.code} v={v} status={v.status} />)}</div>}
        </div>
      </div>
    </div>
  );
}
