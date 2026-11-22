import { FormEvent, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { Cart, Order, Profile } from '../../api/types';
import { ErrorBox, Loading } from '../../components/Common';
import { money, PAYMENT_METHOD_LABEL } from '../../utils/format';

export default function CheckoutPage() {
  const navigate = useNavigate();
  const [cart, setCart] = useState<Cart | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [address, setAddress] = useState('');
  const [method, setMethod] = useState('MOCK_CARD');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.get<Cart>('/api/cart').then(setCart).catch((e) => setError(e.message));
    api.get<Profile>('/api/users/me').then((p) => {
      setProfile(p);
      const def = p.addresses.find((a) => a.isDefault) ?? p.addresses[0];
      if (def) setAddress(def.fullText);
    }).catch(() => undefined);
  }, []);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (!cart) return;
    setBusy(true);
    setError(null);
    try {
      const order = await api.post<Order>('/api/orders', {
        items: cart.items.filter((i) => i.available).map((i) => ({ productId: i.productId, quantity: i.quantity })),
        shippingAddress: address,
        paymentMethod: method,
      });
      navigate(`/orders/${order.id}`);
    } catch (err) {
      setError(err instanceof ApiError && err.errors ? Object.values(err.errors).join('. ') : (err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  if (!cart) return error ? <ErrorBox error={error} /> : <Loading />;
  const items = cart.items.filter((i) => i.available);

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
      </div>
      <div className="card">
        <h2>Đơn hàng</h2>
        {items.map((i) => (
          <div key={i.productId} className="line">
            <span>{i.name} × {i.quantity}</span>
            <span>{money(i.lineTotal)}</span>
          </div>
        ))}
        <div className="line total"><span>Tổng thanh toán</span><span>{money(cart.totalAmount)}</span></div>
        <ErrorBox error={error} />
        <button className="btn btn-primary block" disabled={busy || items.length === 0}>
          {busy ? 'Đang đặt hàng…' : 'Đặt hàng'}
        </button>
      </div>
    </form>
  );
}
