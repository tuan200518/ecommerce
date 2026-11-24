import { useEffect, useState } from 'react';
import type { FlashOffer } from '../hooks/useFlashSales';
import type { MyVoucher, PublicVoucher } from '../api/types';
import { money, voucherValueText } from '../utils/format';

/** Đếm ngược tới một thời điểm (cập nhật mỗi giây). */
export function Countdown({ to, prefix }: { to: string; prefix?: string }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);
  const ms = Math.max(0, new Date(to).getTime() - now);
  const h = Math.floor(ms / 3_600_000);
  const m = Math.floor((ms % 3_600_000) / 60_000);
  const s = Math.floor((ms % 60_000) / 1000);
  const days = Math.floor(h / 24);
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    <span className="countdown">
      {prefix}{days > 0 ? `${days} ngày ` : ''}{pad(h % 24)}:{pad(m)}:{pad(s)}
    </span>
  );
}

/** Giá có/không có flash sale. */
export function FlashPrice({ price, offer, big }: { price: number; offer?: FlashOffer; big?: boolean }) {
  if (!offer || offer.item.salePrice >= price) {
    return <div className={`price ${big ? 'big' : ''}`}>{money(price)}</div>;
  }
  const soldOut = offer.item.remaining <= 0;
  return (
    <div className="flash-price">
      <div className="row" style={{ gap: 8, alignItems: 'baseline' }}>
        <span className={`price ${big ? 'big' : ''} ${soldOut ? '' : 'sale'}`}>{money(soldOut ? price : offer.item.salePrice)}</span>
        {!soldOut && <span className="price-old">{money(price)}</span>}
        {!soldOut && <span className="badge badge-flash">-{offer.item.discountPercent}%</span>}
      </div>
      <div className="tiny">
        {soldOut
          ? <span className="muted">⚡ Flash sale đã hết suất</span>
          : <>
              <span className="flash-label">⚡ {offer.sale.name}</span>{' · '}
              <span className="muted">còn {offer.item.remaining}/{offer.item.quota} suất · tối đa {offer.item.perUserLimit}/khách · kết thúc sau </span>
              <Countdown to={offer.sale.endsAt} />
            </>}
      </div>
      {big && !soldOut && <SoldBar sold={offer.item.sold} quota={offer.item.quota} />}
    </div>
  );
}

export function SoldBar({ sold, quota }: { sold: number; quota: number }) {
  const pct = quota === 0 ? 0 : Math.min(100, Math.round((100 * sold) / quota));
  return (
    <div className="sold-bar" title={`Đã bán ${sold}/${quota}`}>
      <div className="sold-bar-fill" style={{ width: `${pct}%` }} />
      <span>{pct >= 80 ? '🔥 Sắp hết' : `Đã bán ${sold}`}</span>
    </div>
  );
}

/** Thẻ voucher (công khai hoặc của tôi). */
export function VoucherCard({ v, status, onUse, action }: {
  v: PublicVoucher | MyVoucher;
  status?: MyVoucher['status'];
  onUse?: (code: string) => void;
  action?: string;
}) {
  const disabled = status !== undefined && status !== 'AVAILABLE';
  return (
    <div className={`voucher ${disabled ? 'voucher-off' : ''}`}>
      <div className="voucher-value">{voucherValueText(v)}</div>
      <div className="voucher-body">
        <div><strong className="mono">{v.code}</strong>{status && status !== 'AVAILABLE' && <span className="badge"> {VOUCHER_STATUS[status]}</span>}</div>
        <div className="small">{v.description}</div>
        <div className="muted tiny">
          {v.minOrderAmount > 0 ? `Đơn từ ${money(v.minOrderAmount)} · ` : ''}HSD {new Date(v.endsAt).toLocaleDateString('vi-VN')}
          {'remainingUses' in v && v.remainingUses != null ? ` · còn ${v.remainingUses} lượt` : ''}
        </div>
      </div>
      {onUse && !disabled && (
        <button type="button" className="btn btn-ghost" onClick={() => onUse(v.code)}>{action ?? 'Dùng'}</button>
      )}
    </div>
  );
}

const VOUCHER_STATUS: Record<MyVoucher['status'], string> = {
  AVAILABLE: 'Có thể dùng',
  USED: 'Đã dùng',
  EXPIRED: 'Hết hạn',
  DISABLED: 'Ngừng áp dụng',
};
