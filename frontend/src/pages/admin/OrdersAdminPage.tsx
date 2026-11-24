import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Order, Page } from '../../api/types';
import { ErrorBox, Loading, Modal, Pagination, StatusBadge } from '../../components/Common';
import { useAuth } from '../../auth/AuthContext';
import { Link } from 'react-router-dom';
import { dateTime, money, ORDER_STATUS_LABEL, PAYMENT_METHOD_LABEL, RISK_LEVEL_LABEL } from '../../utils/format';

// Giao hàng (SHIPPING / DELIVERED) do Shipping Service điều khiển qua trang Vận chuyển; ở đây chỉ còn thao tác hủy.
const NEXT: Record<string, string[]> = {
  CONFIRMED: ['CANCELLED'],
  PENDING: ['CANCELLED'],
  UNDER_REVIEW: ['CANCELLED'],
  AWAITING_PAYMENT: ['CANCELLED'],
};

export default function OrdersAdminPage() {
  const { hasRole } = useAuth();
  const canUpdate = hasRole('ADMIN', 'WAREHOUSE_MANAGER');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Order> | null>(null);
  const [detail, setDetail] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<Order>>('/api/orders', { status, page, size: 20 }).then(setData).catch((e) => setError(e.message));
  }, [status, page]);

  useEffect(load, [load]);
  useEffect(() => {
    const t = window.setInterval(load, 5000);
    return () => window.clearInterval(t);
  }, [load]);

  const open = (id: number) => api.get<Order>(`/api/orders/${id}`).then(setDetail).catch((e) => setError(e.message));

  const update = (id: number, next: string) => {
    api.put<Order>(`/api/orders/${id}/status`, { status: next })
      .then(() => { load(); open(id); })
      .catch((e) => setError(e.message));
  };

  return (
    <div>
      <h2>Quản lý đơn hàng</h2>
      <ErrorBox error={error} />
      <div className="card">
        <div className="tabs">
          <button className={`tab ${status === '' ? 'active' : ''}`} onClick={() => { setStatus(''); setPage(0); }}>Tất cả</button>
          {Object.entries(ORDER_STATUS_LABEL).map(([k, v]) => (
            <button key={k} className={`tab ${status === k ? 'active' : ''}`} onClick={() => { setStatus(k); setPage(0); }}>{v}</button>
          ))}
        </div>
        {!data ? <Loading /> : (
          <table className="table">
            <thead><tr><th>Mã</th><th>Khách</th><th>Thời gian</th><th>Tổng</th><th>Thanh toán</th><th>Trạng thái</th><th /></tr></thead>
            <tbody>
              {data.content.map((o) => (
                <tr key={o.id}>
                  <td>#{o.id}</td><td>User #{o.userId}</td><td>{dateTime(o.createdAt)}</td><td>{money(o.totalAmount)}</td>
                  <td className="small">{PAYMENT_METHOD_LABEL[o.paymentMethod] ?? o.paymentMethod}</td>
                  <td><StatusBadge status={o.status} /></td>
                  <td><button className="btn btn-ghost" onClick={() => open(o.id)}>Chi tiết</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      </div>

      {detail && (
        <Modal title={`Đơn hàng #${detail.id}`} onClose={() => setDetail(null)}>
          <div className="row space-between"><StatusBadge status={detail.status} /><span>{money(detail.totalAmount)}</span></div>
          {detail.riskLevel && (
            <p className="small">
              Rủi ro: <span className={`badge badge-${detail.riskLevel.toLowerCase()}`}>{RISK_LEVEL_LABEL[detail.riskLevel] ?? detail.riskLevel}</span>{' '}
              {detail.riskScore} điểm {detail.status === 'UNDER_REVIEW' && <Link to="/admin/fraud">→ xác minh</Link>}
            </p>
          )}
          {['CONFIRMED', 'SHIPPING', 'DELIVERED'].includes(detail.status) && (
            <p className="small"><Link to="/admin/shipments">→ Quản lý vận đơn</Link></p>
          )}
          {detail.cancelReason && <div className="alert alert-error">{detail.cancelReason}</div>}
          {detail.items.map((i) => <div key={i.productId} className="line"><span>{i.productName} × {i.quantity}</span><span>{money(i.lineTotal)}</span></div>)}
          <p className="small">Giao đến: {detail.shippingAddress}</p>
          <h4>Lịch sử</h4>
          <ul className="timeline">
            {(detail.history ?? []).map((h, i) => (
              <li key={i}><strong>{ORDER_STATUS_LABEL[h.toStatus] ?? h.toStatus}</strong> <span className="muted tiny">{dateTime(h.changedAt)}</span><div className="small">{h.note}</div></li>
            ))}
          </ul>
          {canUpdate && (NEXT[detail.status] ?? []).length > 0 && (
            <div className="row">
              {(NEXT[detail.status] ?? []).map((n) => (
                <button key={n} className={`btn ${n === 'CANCELLED' ? 'btn-danger' : 'btn-primary'}`} onClick={() => update(detail.id, n)}>
                  → {ORDER_STATUS_LABEL[n]}
                </button>
              ))}
            </div>
          )}
        </Modal>
      )}
    </div>
  );
}
