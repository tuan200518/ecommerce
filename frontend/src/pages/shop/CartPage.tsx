import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { Cart } from '../../api/types';
import { Empty, ErrorBox, Loading } from '../../components/Common';
import { money } from '../../utils/format';

export default function CartPage() {
  const [cart, setCart] = useState<Cart | null>(null);
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    api.get<Cart>('/api/cart').then(setCart).catch((e) => setError(e.message));
  }, []);

  const run = (p: Promise<Cart>) => {
    setError(null);
    p.then(setCart).catch((e) => setError(e.message));
  };

  if (!cart) return error ? <ErrorBox error={error} /> : <Loading />;

  return (
    <div className="card">
      <h2>Giỏ hàng</h2>
      <ErrorBox error={error} />
      {cart.items.length === 0 ? (
        <Empty>Giỏ hàng trống. <Link to="/">Tiếp tục mua sắm</Link></Empty>
      ) : (
        <>
          <table className="table">
            <thead>
              <tr><th>Sản phẩm</th><th>Đơn giá</th><th>Số lượng</th><th>Thành tiền</th><th /></tr>
            </thead>
            <tbody>
              {cart.items.map((i) => (
                <tr key={i.productId} className={i.available ? '' : 'disabled'}>
                  <td>
                    <Link to={`/products/${i.productId}`} className="cart-product">
                      {i.imageUrl && <img src={i.imageUrl} alt="" />}
                      <span>{i.name}{!i.available && <span className="badge badge-cancelled">Ngừng bán</span>}</span>
                    </Link>
                  </td>
                  <td>{money(i.unitPrice)}</td>
                  <td>
                    <input type="number" min={1} max={20} value={i.quantity} style={{ width: 70 }}
                           onChange={(e) => run(api.put<Cart>(`/api/cart/items/${i.productId}`, { quantity: Number(e.target.value) }))} />
                  </td>
                  <td>{money(i.lineTotal)}</td>
                  <td><button className="btn btn-ghost" onClick={() => run(api.del<Cart>(`/api/cart/items/${i.productId}`))}>Xóa</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="cart-summary">
            <button className="btn btn-ghost" onClick={() => run(api.del<Cart>('/api/cart'))}>Xóa toàn bộ</button>
            <div>
              <div className="muted">Tổng cộng ({cart.totalQuantity} sản phẩm)</div>
              <div className="price big">{money(cart.totalAmount)}</div>
              <button className="btn btn-primary" disabled={!cart.items.some((i) => i.available)} onClick={() => navigate('/checkout')}>
                Tiến hành đặt hàng
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
