import { FormEvent, useCallback, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { api, ApiError } from '../../api/client';
import type { Page, Ticket, TicketDetail, TicketOrderInfo } from '../../api/types';
import { ErrorBox, Loading, Pagination } from '../../components/Common';
import { dateTime, money, ORDER_STATUS_LABEL, TICKET_CATEGORY_LABEL, TICKET_STATUS_LABEL } from '../../utils/format';

/** Danh sách yêu cầu hỗ trợ của khách + form gửi yêu cầu mới (?orderId= để gắn sẵn đơn hàng). */
export default function SupportPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const presetOrder = params.get('orderId') ?? '';
  const [data, setData] = useState<Page<Ticket> | null>(null);
  const [page, setPage] = useState(0);
  const [orders, setOrders] = useState<TicketOrderInfo[]>([]);
  const [showForm, setShowForm] = useState(!!presetOrder);
  const [form, setForm] = useState({ subject: '', category: presetOrder ? 'ORDER' : 'OTHER', orderId: presetOrder, message: '' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    api.get<Page<Ticket>>('/api/support/tickets/mine', { page, size: 10 }).then(setData).catch((e) => setError(e.message));
  }, [page]);

  useEffect(load, [load]);
  useEffect(() => {
    api.get<TicketOrderInfo[]>('/api/support/orders/mine').then(setOrders).catch(() => undefined);
    window.addEventListener('shop:notification', load);
    return () => window.removeEventListener('shop:notification', load);
  }, [load]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    api.post<TicketDetail>('/api/support/tickets', {
      subject: form.subject, category: form.category, message: form.message,
      orderId: form.orderId ? Number(form.orderId) : null,
    })
      .then((t) => navigate(`/support/${t.ticket.id}`))
      .catch((err) => setError(err instanceof ApiError && err.errors ? Object.values(err.errors).join('. ') : err.message))
      .finally(() => setBusy(false));
  };

  return (
    <div>
      <div className="row space-between">
        <h2>💬 Hỗ trợ khách hàng</h2>
        {!showForm && <button className="btn btn-primary" onClick={() => setShowForm(true)}>+ Gửi yêu cầu mới</button>}
      </div>
      <p className="muted small">Nhân viên CSKH phản hồi trong vòng 2 giờ (yêu cầu về thanh toán: 30 phút). Bạn nhận thông báo ngay khi có trả lời.</p>

      {showForm && (
        <form className="card form" onSubmit={submit}>
          <h3>Yêu cầu mới</h3>
          <div className="row">
            <label style={{ flex: 1 }}>Chủ đề
              <select value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
                {Object.entries(TICKET_CATEGORY_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
            <label style={{ flex: 1 }}>Đơn hàng liên quan
              <select value={form.orderId} onChange={(e) => setForm({ ...form, orderId: e.target.value })}>
                <option value="">— không chọn —</option>
                {orders.map((o) => (
                  <option key={o.orderId} value={o.orderId}>#{o.orderId} · {ORDER_STATUS_LABEL[o.status] ?? o.status}{o.totalAmount ? ` · ${money(o.totalAmount)}` : ''}</option>
                ))}
                {presetOrder && !orders.some((o) => String(o.orderId) === presetOrder) && <option value={presetOrder}>#{presetOrder}</option>}
              </select>
            </label>
          </div>
          <label>Tiêu đề<input value={form.subject} onChange={(e) => setForm({ ...form, subject: e.target.value })} required maxLength={150}
                                  placeholder="vd. Đơn hàng giao chậm" /></label>
          <label>Mô tả vấn đề<textarea rows={4} value={form.message} onChange={(e) => setForm({ ...form, message: e.target.value })} required maxLength={2000} /></label>
          <ErrorBox error={error} />
          <div className="row">
            <button className="btn btn-primary" disabled={busy}>{busy ? 'Đang gửi…' : 'Gửi yêu cầu'}</button>
            <button type="button" className="btn btn-ghost" onClick={() => setShowForm(false)}>Hủy</button>
          </div>
        </form>
      )}

      <div className="card">
        <h3>Yêu cầu của tôi</h3>
        {!data ? <Loading /> : data.content.length === 0 ? <div className="empty">Bạn chưa gửi yêu cầu hỗ trợ nào</div> : (
          <table className="table">
            <thead><tr><th>Mã</th><th>Tiêu đề</th><th>Trạng thái</th><th>Cập nhật</th></tr></thead>
            <tbody>
              {data.content.map((t) => (
                <tr key={t.id}>
                  <td><Link to={`/support/${t.id}`}><strong>{t.code}</strong></Link></td>
                  <td>
                    <Link to={`/support/${t.id}`}>{t.subject}</Link>
                    {t.unread > 0 && <span className="badge badge-unread">{t.unread} tin mới</span>}
                    <div className="muted tiny">{TICKET_CATEGORY_LABEL[t.category]}{t.orderId ? ` · đơn #${t.orderId}` : ''}</div>
                  </td>
                  <td><span className={`badge badge-ticket-${t.status.toLowerCase()}`}>{TICKET_STATUS_LABEL[t.status]}</span></td>
                  <td className="small">{dateTime(t.lastMessageAt)}</td>
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
