import { FormEvent, useCallback, useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { Cart, MyVoucher, Order, Profile, PublicVoucher, Quote } from '../../api/types';
import { ErrorBox, Loading } from '../../components/Common';
import { money, PAYMENT_METHOD_LABEL, voucherValueText } from '../../utils/format';

const FREE_SHIPPING_FROM = 500_000;
const SHIPPING_FEE = 30_000;

export default function CheckoutPage() {
  const navigate = useNavigate();
  const [cart, setCart] = useState<Cart | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [address, setAddress] = useState('');
  const [method, setMethod] = useState('MOCK_CARD');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [codeInput, setCodeInput] = useState('');
  const [appliedCode, setAppliedCode] = useState<string | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [quoting, setQuoting] = useState(false);
  const [vouchers, setVouchers] = useState<(PublicVoucher | MyVoucher)[]>([]);

  useEffect(() => {
    api.get<Cart>('/api/cart').then(setCart).catch((e) => setError(e.message));
    api.get<Profile>('/api/users/me').then((p) => {
      setProfile(p);
      const def = p.addresses.find((a) => a.isDefault) ?? p.addresses[0];
      if (def) setAddress(def.fullText);
    }).catch(() => undefined);
    // Mã của tôi (đổi từ điểm) + mã công khai – lỗi (Promotion Service chưa chạy) thì bỏ qua
    Promise.all([
      api.get<MyVoucher[]>('/api/promotions/vouchers/mine').catch(() => [] as MyVoucher[]),
      api.get<PublicVoucher[]>('/api/promotions/vouchers/public').catch(() => [] as PublicVoucher[]),
    ]).then(([mine, pub]) => setVouchers([...mine.filter((v) => v.status === 'AVAILABLE'), ...pub]));
  }, []);

  const items = cart?.items.filter((i) => i.available) ?? [];
  const subtotal = items.reduce((sum, i) => sum + i.lineTotal, 0);
  // Ước tính hiển thị; Order Service tính phí chính thức (trên giá niêm yết) và Promotion Service tính ưu đãi
  const shippingFee = subtotal >= FREE_SHIPPING_FROM ? 0 : SHIPPING_FEE;
  const itemsKey = items.map((i) => `${i.productId}x${i.quantity}`).join(',');

  const requestQuote = useCallback((code: string | null) => {
    if (items.length === 0) return;
    setQuoting(true);
    api.post<Quote>('/api/promotions/quote', {
      items: items.map((i) => ({ productId: i.productId, quantity: i.quantity })),
      voucherCode: code,
      shippingFee,
    })
      .then(setQuote)
      .catch(() => setQuote(null)) // không báo giá được → hiển thị giá niêm yết, đơn vẫn được định giá ở backend
      .finally(() => setQuoting(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [itemsKey, shippingFee]);

  useEffect(() => { requestQuote(appliedCode); }, [requestQuote, appliedCode]);

  const applyCode = (code: string) => {
    const c = code.trim().toUpperCase();
    setCodeInput(c);
    setAppliedCode(c || null);
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!cart) return;
    setBusy(true);
    setError(null);
    try {
      const order = await api.post<Order>('/api/orders', {
        items: items.map((i) => ({ productId: i.productId, quantity: i.quantity })),
        shippingAddress: address,
        paymentMethod: method,
        voucherCode: quote?.voucherValid ? appliedCode : null,
      });
      navigate(`/orders/${order.id}`);
    } catch (err) {
      setError(err instanceof ApiError && err.errors ? Object.values(err.errors).join('. ') : (err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!cart) return error ? <ErrorBox error={error} /> : <Loading />;

  const discount = quote?.totalDiscount ?? 0;
  const total = Math.max(0, subtotal - discount + shippingFee);
  const blocking = quote ? quote.warnings : [];

  return (
    <form className="checkout" onSubmit={submit}>
      <div className="card">
        <h2>Thông tin giao hàng</h2>
        {profile && profile.addresses.length > 0 && (
          <div className="cat-list">
            {profile.addresses.map((a) => (
              <button type="button" key={a.id} className={`chip ${address === a.fullText ? 'active' : ''}`} onClick={() => setAddress(a.fullText)}>
                {a.label || 'Địa chỉ'}{a.isDefault ? ' (mặc định)' : ''}
              </button>
            ))}
          </div>
        )}
        <label className="form">
          Địa chỉ nhận hàng
          <textarea rows={3} value={address} onChange={(e) => setAddress(e.target.value)} required
                    placeholder="Người nhận - SĐT - Số nhà, đường, phường, quận, tỉnh/thành" />
        </label>
        <h3>Phương thức thanh toán</h3>
        {Object.entries(PAYMENT_METHOD_LABEL).map(([value, label]) => (
          <label key={value} className="radio">
            <input type="radio" name="method" value={value} checked={method === value} onChange={() => setMethod(value)} />
            {label}
          </label>
        ))}
        <p className="muted small">
          Thanh toán là mô phỏng. Chọn "Thẻ bị từ chối" để xem luồng bù (Saga): đơn bị hủy và tồn kho được hoàn lại.
        </p>

        <h3>🎟️ Mã giảm giá</h3>
        <div className="row">
          <input value={codeInput} onChange={(e) => setCodeInput(e.target.value.toUpperCase())} placeholder="Nhập mã, vd. WELCOME10"
                 maxLength={32} style={{ flex: 1 }} />
          <button type="button" className="btn" onClick={() => applyCode(codeInput)} disabled={quoting}>Áp dụng</button>
          {appliedCode && <button type="button" className="btn btn-ghost" onClick={() => applyCode('')}>Bỏ mã</button>}
        </div>
        {quote?.voucherMessage && (
          <div className={`alert ${quote.voucherValid ? 'alert-success' : 'alert-error'}`}>{quote.voucherMessage}</div>
        )}
        {vouchers.length > 0 && (
          <div className="cat-list" style={{ marginTop: 8 }}>
            {vouchers.map((v) => (
              <button type="button" key={v.code} className={`chip ${appliedCode === v.code ? 'active' : ''}`}
                      title={v.description} onClick={() => applyCode(v.code)}>
                {v.code} · {voucherValueText(v)}
              </button>
            ))}
          </div>
        )}
        <p className="muted tiny">Mỗi đơn dùng tối đa một mã. <Link to="/deals">Xem tất cả ưu đãi</Link></p>
      </div>
      <div className="card">
        <h2>Đơn hàng</h2>
        {(quote?.lines ?? items.map((i) => ({ productId: i.productId, name: i.name, quantity: i.quantity,
          unitPrice: i.unitPrice, salePrice: i.unitPrice, lineTotal: i.lineTotal, flashSale: null }))).map((l) => (
          <div key={l.productId} className="line">
            <span>
              {l.name} × {l.quantity}
              {l.flashSale && <div className="tiny flash-label">⚡ {l.flashSale}: {money(l.salePrice)}/sp</div>}
            </span>
            <span>
              {l.salePrice < l.unitPrice && <span className="price-old">{money(l.unitPrice * l.quantity)}</span>}{' '}
              {money(l.lineTotal)}
            </span>
          </div>
        ))}
        <div className="line"><span>Tạm tính</span><span>{money(subtotal)}</span></div>
        {quote && quote.itemDiscount > 0 && (
          <div className="line text-good"><span>Giảm giá flash sale</span><span>-{money(quote.itemDiscount)}</span></div>
        )}
        <div className="line">
          <span>Phí vận chuyển {shippingFee === 0 ? '' : <span className="muted tiny">(miễn phí từ {money(FREE_SHIPPING_FROM)})</span>}</span>
          <span>{shippingFee === 0 ? 'Miễn phí' : money(shippingFee)}</span>
        </div>
        {quote && quote.voucherValid && quote.voucherDiscount > 0 && (
          <div className="line text-good">
            <span>{quote.freeShipping ? 'Miễn phí vận chuyển' : 'Voucher'} ({quote.voucherCode})</span>
            <span>-{money(quote.voucherDiscount)}</span>
          </div>
        )}
        <div className="line total"><span>Tổng thanh toán</span><span>{money(total)}</span></div>
        {blocking.length > 0 && (
          <div className="alert alert-error">
            {blocking.map((w) => <div key={w}>⚠️ {w}</div>)}
            <div className="tiny">Vui lòng điều chỉnh giỏ hàng hoặc bỏ mã giảm giá trước khi đặt hàng.</div>
          </div>
        )}
        <ErrorBox error={error} />
        <button className="btn btn-primary block" disabled={busy || items.length === 0 || blocking.length > 0 || quoting}>
          {busy ? 'Đang đặt hàng…' : 'Đặt hàng'}
        </button>
        <p className="muted tiny">Giá ưu đãi được xác nhận lại khi đặt hàng (suất flash sale / lượt dùng mã có thể vừa hết).</p>
      </div>
    </form>
  );
}
