import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Page, Payment, PaymentSummary } from '../../api/types';
import { ErrorBox, Loading, Pagination, StatusBadge } from '../../components/Common';
import { dateTime, money, number, PAYMENT_METHOD_LABEL, PAYMENT_STATUS_LABEL } from '../../utils/format';

export default function PaymentsAdminPage() {
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Payment> | null>(null);
  const [summary, setSummary] = useState<PaymentSummary[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<Payment>>('/api/payments', { status, page, size: 20 }).then(setData).catch((e) => setError(e.message));
    api.get<PaymentSummary[]>('/api/payments/summary').then(setSummary).catch(() => undefined);
  }, [status, page]);

  useEffect(load, [load]);

  return (
    <div>
      <h2>Theo dõi thanh toán</h2>
      <ErrorBox error={error} />
      <div className="kpis">
        {summary.map((s) => (
          <div key={s.status} className="kpi card">
            <div className="muted small">{PAYMENT_STATUS_LABEL[s.status] ?? s.status}</div>
            <div className="kpi-value">{number(s.count)}</div>
            <div className="muted tiny">{money(s.amount)}</div>
          </div>
        ))}
      </div>
      <div className="card">
        <div className="tabs">
          <button className={`tab ${status === '' ? 'active' : ''}`} onClick={() => { setStatus(''); setPage(0); }}>Tất cả</button>
          {Object.entries(PAYMENT_STATUS_LABEL).map(([k, v]) => (
            <button key={k} className={`tab ${status === k ? 'active' : ''}`} onClick={() => { setStatus(k); setPage(0); }}>{v}</button>
          ))}
        </div>
        {!data ? <Loading /> : (
          <table className="table">
            <thead><tr><th>ID</th><th>Đơn</th><th>Khách</th><th>Số tiền</th><th>Phương thức</th><th>Trạng thái</th><th>Lý do</th><th>Thời gian</th></tr></thead>
            <tbody>
              {data.content.map((p) => (
                <tr key={p.id}>
                  <td>{p.id}</td><td>#{p.orderId}</td><td>#{p.userId}</td><td>{money(p.amount)}</td>
                  <td className="small">{PAYMENT_METHOD_LABEL[p.method] ?? p.method}</td>
                  <td><StatusBadge status={p.status} kind="payment" /></td>
                  <td className="small">{p.failureReason}</td><td className="small">{dateTime(p.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      </div>
    </div>
  );
}
