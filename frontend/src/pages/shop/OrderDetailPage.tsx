import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Order, Shipment } from '../../api/types';
import { ErrorBox, Loading, StatusBadge } from '../../components/Common';
import { dateTime, money, ORDER_STATUS_LABEL, PAYMENT_METHOD_LABEL, SHIPMENT_STATUS_LABEL } from '../../utils/format';

const FLOW = ['PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'SHIPPING', 'DELIVERED'];
const IN_FLIGHT = ['PENDING', 'UNDER_REVIEW', 'AWAITING_PAYMENT'];
const HAS_SHIPMENT = ['CONFIRMED', 'SHIPPING', 'DELIVERED', 'CANCELLED'];

export default function OrderDetailPage() {
  const { id } = useParams();
  const [order, setOrder] = useState<Order | null>(null);
  const [shipment, setShipment] = useState<Shipment | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Order>(`/api/orders/${id}`)
      .then((o) => {
        setOrder(o);
        if (HAS_SHIPMENT.includes(o.status)) {
          // Vận đơn do Shipping Service tạo bất đồng bộ sau khi đơn được xác nhận → có thể chưa có ngay
          api.get<Shipment>(`/api/shipments/orders/${o.id}`).then(setShipment).catch(() => setShipment(null));
        }
      })
      .catch((e) => setError(e.message));
  }, [id]);

  useEffect(load, [load]);

  // Cập nhật realtime: khi có thông báo liên quan, tải lại; trong lúc Saga/giao hàng đang chạy, poll nhẹ để chắc chắn
  useEffect(() => {
    window.addEventListener('shop:notification', load);
    const active = order && (IN_FLIGHT.includes(order.status) || order.status === 'CONFIRMED' || order.status === 'SHIPPING');
    const timer = active ? window.setInterval(load, order && IN_FLIGHT.includes(order.status) ? 2000 : 5000) : undefined;
    return () => {
      window.removeEventListener('shop:notification', load);
      if (timer) window.clearInterval(timer);
    };
  }, [load, order]);

  const cancel = () => {
    if (!confirm('Bạn chắc chắn muốn hủy đơn này?')) return;
    api.post<Order>(`/api/orders/${id}/cancel`).then(() => load()).catch((e) => setError(e.message));
  };

  if (!order) return error ? <ErrorBox error={error} /> : <Loading />;
  const reachedStatus = order.status === 'UNDER_REVIEW' ? 'PENDING' : order.status;
  const reached = order.status === 'CANCELLED' ? -1 : FLOW.indexOf(reachedStatus);
  const canCancel = ['PENDING', 'UNDER_REVIEW', 'AWAITING_PAYMENT', 'CONFIRMED'].includes(order.status);

  return (
    <div className="card">
      <div className="row space-between">
        <h2>Đơn hàng #{order.id}</h2>
        <StatusBadge status={order.status} />
      </div>
      <ErrorBox error={error} />

      {order.status === 'CANCELLED' ? (
        <div className="alert alert-error">Đơn đã bị hủy: {order.cancelReason}</div>
      ) : (
        <ol className="steps">
          {FLOW.map((s, i) => (
            <li key={s} className={i <= reached ? 'done' : ''}>{ORDER_STATUS_LABEL[s]}</li>
          ))}
        </ol>
      )}
      {order.status === 'UNDER_REVIEW' && (
        <div className="alert alert-warning">
          🛡️ Đơn hàng cần thêm bước xác minh an toàn trước khi thanh toán. Hàng đã được giữ cho bạn —
          nhân viên sẽ liên hệ và xử lý trong thời gian sớm nhất.
        </div>
      )}
      {(order.status === 'PENDING' || order.status === 'AWAITING_PAYMENT') && (
        <p className="muted small">⏳ Đơn đang được xử lý bất đồng bộ qua Kafka: giữ hàng + kiểm tra rủi ro + áp dụng ưu đãi → thanh toán → xác nhận.</p>
      )}
      {order.promotionPending && order.status !== 'CANCELLED' && (
        <div className="alert alert-info">🎟️ Đang áp dụng ưu đãi{order.voucherCode ? ` (mã ${order.voucherCode})` : ''} – tổng tiền sẽ được cập nhật sau vài giây.</div>
      )}

      <div className="two-col">
        <div>
          <h3>Sản phẩm</h3>
          {order.items.map((i) => (
            <div key={i.productId} className="line">
              <span>
                <Link to={`/products/${i.productId}`}>{i.productName}</Link> × {i.quantity}
                {order.status === 'DELIVERED' && <> · <Link to={`/products/${i.productId}#reviews-title`} className="small">Đánh giá</Link></>}
                {i.salePrice != null && <div className="tiny flash-label">⚡ Giá flash sale {money(i.salePrice)}/sp</div>}
              </span>
              <span>
                {i.salePrice != null && <span className="price-old">{money(i.unitPrice * i.quantity)}</span>}{' '}
                {money(i.lineTotal)}
              </span>
            </div>
          ))}
          {order.subtotal !== undefined && (
            <>
              <div className="line"><span>Tạm tính</span><span>{money(order.subtotal)}</span></div>
              {!!order.itemDiscount && order.itemDiscount > 0 && (
                <div className="line text-good"><span>Giảm giá flash sale</span><span>-{money(order.itemDiscount)}</span></div>
              )}
              <div className="line"><span>Phí vận chuyển</span><span>{order.shippingFee ? money(order.shippingFee) : 'Miễn phí'}</span></div>
              {!!order.voucherDiscount && order.voucherDiscount > 0 && (
                <div className="line text-good"><span>Mã giảm giá {order.voucherCode}</span><span>-{money(order.voucherDiscount)}</span></div>
              )}
            </>
          )}
          <div className="line total"><span>Tổng</span><span>{money(order.totalAmount)}</span></div>
          <p className="small"><strong>Giao đến:</strong> {order.shippingAddress}</p>
          <p className="small"><strong>Thanh toán:</strong> {PAYMENT_METHOD_LABEL[order.paymentMethod] ?? order.paymentMethod}</p>
          {canCancel && <button className="btn btn-danger" onClick={cancel}>Hủy đơn</button>}
        </div>
        <div>
          {shipment && (
            <>
              <h3>Vận chuyển</h3>
              <p className="small">
                <strong>{shipment.carrierName}</strong> · Mã vận đơn <code>{shipment.trackingNumber}</code>
                <span className={`badge badge-${shipment.status.toLowerCase()}`}>{SHIPMENT_STATUS_LABEL[shipment.status] ?? shipment.status}</span>
              </p>
              <ul className="timeline">
                {(shipment.timeline ?? []).map((t, idx) => (
                  <li key={idx}>
                    <strong>{SHIPMENT_STATUS_LABEL[t.status] ?? t.status}</strong>
                    <div className="muted tiny">{dateTime(t.at)}</div>
                    {t.note && <div className="small">{t.note}</div>}
                  </li>
                ))}
              </ul>
            </>
          )}
          <h3>Lịch sử trạng thái</h3>
          <ul className="timeline">
            {(order.history ?? []).map((h, idx) => (
              <li key={idx}>
                <strong>{ORDER_STATUS_LABEL[h.toStatus] ?? h.toStatus}</strong>
                <div className="muted tiny">{dateTime(h.changedAt)}</div>
                {h.note && <div className="small">{h.note}</div>}
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
