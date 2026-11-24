import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Page, Shipment, ShippingSummary } from '../../api/types';
import { ErrorBox, Loading, Modal, Pagination } from '../../components/Common';
import { useAuth } from '../../auth/AuthContext';
import { dateTime, money, number, SHIPMENT_STATUS_LABEL } from '../../utils/format';

const QUEUE = ['CREATED', 'PACKED', 'HANDED_OVER', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED'];

/** Quản lý vận chuyển (Shipping Service): hàng đợi vận đơn theo trạng thái, cập nhật từng bước, đổi ĐVVC. */
export default function ShipmentsAdminPage() {
  const { hasRole } = useAuth();
  const canUpdate = hasRole('ADMIN', 'WAREHOUSE_MANAGER');
  const [status, setStatus] = useState('CREATED');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Shipment> | null>(null);
  const [summary, setSummary] = useState<ShippingSummary | null>(null);
  const [detail, setDetail] = useState<Shipment | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<Shipment>>('/api/shipments', { status, page, size: 20 }).then(setData).catch((e) => setError(e.message));
    api.get<ShippingSummary>('/api/shipments/summary').then(setSummary).catch(() => undefined);
  }, [status, page]);

  useEffect(load, [load]);
  useEffect(() => {
    const t = window.setInterval(load, 5000);
    return () => window.clearInterval(t);
  }, [load]);

  const open = (id: number) => api.get<Shipment>(`/api/shipments/${id}`).then(setDetail).catch((e) => setError(e.message));

  const advance = (s: Shipment, next: string) => {
    setError(null);
    api.put<Shipment>(`/api/shipments/${s.id}/status`, { status: next })
      .then((updated) => { if (detail) setDetail(updated); load(); })
      .catch((e) => setError(e.message));
  };

  const changeCarrier = (s: Shipment, carrier: string) => {
    api.put<Shipment>(`/api/shipments/${s.id}/carrier`, { carrier })
      .then((updated) => { setDetail(updated); load(); })
      .catch((e) => setError(e.message));
  };

  return (
    <div>
      <h2>Quản lý vận chuyển</h2>
      <p className="muted small">
        Vận đơn được tạo tự động khi đơn hàng được xác nhận (sự kiện OrderConfirmed). Mỗi bước cập nhật phát
        shipping-events → Order Service chuyển đơn sang "Đang giao" / "Đã giao".
      </p>
      <ErrorBox error={error} />
      <div className="card">
        <div className="tabs">
          {QUEUE.map((s) => (
            <button key={s} className={`tab ${status === s ? 'active' : ''}`} onClick={() => { setStatus(s); setPage(0); }}>
              {SHIPMENT_STATUS_LABEL[s]} ({number(summary?.byStatus[s] ?? 0)})
            </button>
          ))}
        </div>
        {!data ? <Loading /> : data.content.length === 0 ? <div className="empty">Không có vận đơn</div> : (
          <table className="table">
            <thead><tr><th>Mã vận đơn</th><th>Đơn</th><th>ĐVVC</th><th>Giá trị đơn</th><th>Cập nhật</th><th>Trạng thái</th><th /></tr></thead>
            <tbody>
              {data.content.map((s) => (
                <tr key={s.id}>
                  <td><code>{s.trackingNumber}</code></td>
                  <td>#{s.orderId}</td>
                  <td className="small">{s.carrierName}</td>
                  <td>{money(s.orderTotal)}</td>
                  <td className="small">{dateTime(s.updatedAt)}</td>
                  <td><span className={`badge badge-${s.status.toLowerCase()}`}>{SHIPMENT_STATUS_LABEL[s.status]}</span></td>
                  <td className="row">
                    {canUpdate && s.nextStatuses.map((n) => (
                      <button key={n} className="btn btn-primary" onClick={() => advance(s, n)}>→ {SHIPMENT_STATUS_LABEL[n]}</button>
                    ))}
                    <button className="btn btn-ghost" onClick={() => open(s.id)}>Chi tiết</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      </div>

      {detail && (
        <Modal title={`Vận đơn ${detail.trackingNumber}`} onClose={() => setDetail(null)}>
          <p className="small">Đơn #{detail.orderId} · User #{detail.userId} · Phí ĐVVC {money(detail.fee)}</p>
          <p className="small"><strong>Giao đến:</strong> {detail.address}</p>
          {canUpdate && !['HANDED_OVER', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED'].includes(detail.status) && summary && (
            <label className="form">Đơn vị vận chuyển
              <select value={detail.carrier} onChange={(e) => changeCarrier(detail, e.target.value)}>
                {summary.carriers.map((c) => <option key={c.code} value={c.code}>{c.name} – {money(c.baseFee)}</option>)}
              </select>
            </label>
          )}
          <ul className="timeline">
            {(detail.timeline ?? []).map((t, i) => (
              <li key={i}><strong>{SHIPMENT_STATUS_LABEL[t.status]}</strong> <span className="muted tiny">{dateTime(t.at)}</span><div className="small">{t.note}</div></li>
            ))}
          </ul>
          {canUpdate && detail.nextStatuses.length > 0 && (
            <div className="row">
              {detail.nextStatuses.map((n) => (
                <button key={n} className="btn btn-primary" onClick={() => advance(detail, n)}>→ {SHIPMENT_STATUS_LABEL[n]}</button>
              ))}
            </div>
          )}
        </Modal>
      )}
    </div>
  );
}
