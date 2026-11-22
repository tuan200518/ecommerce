import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Order } from '../../api/types';
import { ErrorBox, Loading, StatusBadge } from '../../components/Common';
import { dateTime, money, ORDER_STATUS_LABEL, PAYMENT_METHOD_LABEL } from '../../utils/format';

const FLOW = ['PENDING', 'AWAITING_PAYMENT', 'CONFIRMED', 'SHIPPING', 'DELIVERED'];

export default function OrderDetailPage() {
  const { id } = useParams();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Order>(`/api/orders/${id}`).then(setOrder).catch((e) => setError(e.message));
  }, [id]);

  useEffect(load, [load]);

  // Cập nhật realtime: khi có thông báo liên quan, tải lại; trong lúc Saga đang chạy, poll nhẹ để chắc chắn
  useEffect(() => {
    window.addEventListener('shop:notification', load);
    const inFlight = order && (order.status === 'PENDING' || order.status === 'AWAITING_PAYMENT');
    const timer = inFlight ? window.setInterval(load, 2000) : undefined;
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
  const reached = order.status === 'CANCELLED' ? -1 : FLOW.indexOf(order.status);
  const canCancel = ['PENDING', 'AWAITING_PAYMENT', 'CONFIRMED'].includes(order.status);

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
      {(order.status === 'PENDING' || order.status === 'AWAITING_PAYMENT') && (
        <p className="muted small">⏳ Đơn đang được xử lý bất đồng bộ qua Kafka: giữ hàng → thanh toán → xác nhận.</p>
      )}

      <div className="two-col">
        <div>
          <h3>Sản phẩm</h3>
          {order.items.map((i) => (
            <div key={i.productId} className="line"><span>{i.productName} × {i.quantity}</span><span>{money(i.lineTotal)}</span></div>
          ))}
          <div className="line total"><span>Tổng</span><span>{money(order.totalAmount)}</span></div>
          <p className="small"><strong>Giao đến:</strong> {order.shippingAddress}</p>
          <p className="small"><strong>Thanh toán:</strong> {PAYMENT_METHOD_LABEL[order.paymentMethod] ?? order.paymentMethod}</p>
          {canCancel && <button className="btn btn-danger" onClick={cancel}>Hủy đơn</button>}
        </div>
        <div>
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
