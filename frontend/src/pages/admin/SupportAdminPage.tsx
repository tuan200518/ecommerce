import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { Page, SupportSummary, Ticket, TicketDetail } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ErrorBox, Loading, Pagination } from '../../components/Common';
import { SupportChat } from '../../components/SupportChat';
import { useEventSource } from '../../hooks/useEventSource';
import {
  dateTime, money, number, ORDER_STATUS_LABEL, TICKET_CATEGORY_LABEL, TICKET_PRIORITY_LABEL, TICKET_STATUS_STAFF_LABEL,
} from '../../utils/format';

type View = 'active' | 'unassigned' | 'mine' | 'overdue' | 'resolved' | 'all';
const VIEWS: { key: View; label: string; query: Record<string, string | boolean> }[] = [
  { key: 'active', label: 'Đang mở', query: { status: 'ACTIVE' } },
  { key: 'unassigned', label: 'Chưa nhận', query: { status: 'ACTIVE', assignee: 'NONE' } },
  { key: 'mine', label: 'Của tôi', query: { status: 'ACTIVE', assignee: 'ME' } },
  { key: 'overdue', label: '⏰ Quá hạn', query: { overdue: true } },
  { key: 'resolved', label: 'Đã giải quyết', query: { status: 'RESOLVED' } },
  { key: 'all', label: 'Tất cả', query: {} },
];

/** Hàng đợi CSKH (Support Service): lọc, nhận xử lý, chat realtime, đổi trạng thái / ưu tiên, SLA. */
export default function SupportAdminPage() {
  const { hasRole } = useAuth();
  const canReply = hasRole('ADMIN', 'SUPPORT_AGENT');
  const [view, setView] = useState<View>('active');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Ticket> | null>(null);
  const [summary, setSummary] = useState<SupportSummary | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [detail, setDetail] = useState<TicketDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    const query = VIEWS.find((v) => v.key === view)!.query;
    api.get<Page<Ticket>>('/api/support/admin/tickets', { ...query, q: q || undefined, page, size: 30 })
      .then(setData).catch((e) => setError(e.message));
    api.get<SupportSummary>('/api/support/admin/summary').then(setSummary).catch(() => undefined);
  }, [view, q, page]);
  useEffect(load, [load]);

  // Yêu cầu mới / tin nhắn mới của khách → làm mới hàng đợi (gộp nhiều sự kiện liên tiếp)
  const [tick, setTick] = useState(0);
  useEventSource('/api/support/admin/stream', { ticket: () => setTick((n) => n + 1) });
  useEffect(() => {
    if (tick === 0) return;
    const t = window.setTimeout(load, 400);
    return () => window.clearTimeout(t);
  }, [tick, load]);

  const open = useCallback((id: number) => {
    setSelected(id);
    api.get<TicketDetail>(`/api/support/tickets/${id}`).then(setDetail).catch((e) => setError(e.message));
  }, []);

  const update = (body: Record<string, string>) => {
    if (!detail) return;
    setError(null);
    api.put<Ticket>(`/api/support/admin/tickets/${detail.ticket.id}`, body)
      .then((t) => { setDetail({ ...detail, ticket: t }); load(); })
      .catch((e) => setError(e.message));
  };

  const t = detail?.ticket;
  return (
    <div>
      <h2>🎧 Hỗ trợ khách hàng</h2>
      {summary && (
        <div className="kpis">
          <div className="kpi card"><div className="muted small">Đang mở</div><div className="kpi-value">{number(summary.active)}</div>
            <div className="muted tiny">{number(summary.unassigned)} chưa có người nhận</div></div>
          <div className={`kpi card ${summary.overdue > 0 ? 'bad' : 'good'}`}><div className="muted small">Quá hạn phản hồi (SLA)</div>
            <div className="kpi-value">{number(summary.overdue)}</div></div>
          <div className="kpi card"><div className="muted small">Giải quyết hôm nay</div><div className="kpi-value">{number(summary.resolvedToday)}</div></div>
          <div className="kpi card"><div className="muted small">Phản hồi đầu tiên TB (7 ngày)</div>
            <div className="kpi-value">{summary.avgFirstResponseMinutes == null ? '—' : `${summary.avgFirstResponseMinutes} phút`}</div></div>
          <div className="kpi card"><div className="muted small">Mức hài lòng (30 ngày)</div>
            <div className="kpi-value">{summary.avgRating == null ? '—' : `${summary.avgRating} ★`}</div>
            <div className="muted tiny">{number(summary.ratingCount)} đánh giá</div></div>
        </div>
      )}
      <ErrorBox error={error} />
      <div className="support-layout">
        <div className="card">
          <div className="tabs">
            {VIEWS.map((v) => (
              <button key={v.key} className={`tab ${view === v.key ? 'active' : ''}`} onClick={() => { setView(v.key); setPage(0); }}>{v.label}</button>
            ))}
          </div>
          <input placeholder="Tìm mã HT-…, số đơn, email, tiêu đề" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} style={{ width: '100%' }} />
          {!data ? <Loading /> : data.content.length === 0 ? <div className="empty">Không có yêu cầu nào</div> : (
            <div className="ticket-list">
              {data.content.map((x) => (
                <button key={x.id} className={`ticket-item ${selected === x.id ? 'active' : ''}`} onClick={() => open(x.id)}>
                  <div className="row space-between">
                    <strong>{x.code}</strong>
                    <span className={`badge badge-priority-${x.priority.toLowerCase()}`}>{TICKET_PRIORITY_LABEL[x.priority]}</span>
                  </div>
                  <div className="small">{x.subject}{x.unread > 0 && <span className="badge badge-unread">{x.unread}</span>}</div>
                  <div className="muted tiny">
                    {TICKET_STATUS_STAFF_LABEL[x.status]} · {x.customerEmail} · {dateTime(x.lastMessageAt)}
                    {x.overdue && <span className="text-bad"> · quá hạn</span>}
                    {x.assigneeEmail ? ` · ${x.assigneeEmail}` : ' · chưa nhận'}
                  </div>
                </button>
              ))}
            </div>
          )}
          {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
        </div>

        <div className="card">
          {!t || !detail ? <div className="empty">Chọn một yêu cầu để xem và trả lời</div> : (
            <>
              <div className="row space-between">
                <h3 style={{ margin: 0 }}>{t.code}: {t.subject}</h3>
                <span className={`badge badge-ticket-${t.status.toLowerCase()}`}>{TICKET_STATUS_STAFF_LABEL[t.status]}</span>
              </div>
              <div className="muted small">
                {t.customerEmail} · {TICKET_CATEGORY_LABEL[t.category]} · tạo {dateTime(t.createdAt)}
                {t.firstResponseAt
                  ? <> · phản hồi đầu {dateTime(t.firstResponseAt)}</>
                  : <> · <span className={t.overdue ? 'text-bad' : ''}>hạn phản hồi {dateTime(t.firstResponseDueAt)}</span></>}
              </div>
              {detail.order && (
                <div className="small">Đơn liên quan: <Link to="/admin/orders">#{detail.order.orderId}</Link> · {ORDER_STATUS_LABEL[detail.order.status] ?? detail.order.status}{detail.order.totalAmount ? ` · ${money(detail.order.totalAmount)}` : ''}</div>
              )}
              {t.rating && <div className="small">Khách đánh giá: {'★'.repeat(t.rating)} {t.ratingComment}</div>}
              {canReply && (
                <div className="row" style={{ margin: '8px 0', flexWrap: 'wrap' }}>
                  {!t.assigneeEmail && <button className="btn" onClick={() => update({ assignee: 'ME' })}>Nhận xử lý</button>}
                  <select value="" onChange={(e) => e.target.value && update({ status: e.target.value })} disabled={t.status === 'CLOSED'}>
                    <option value="">Đổi trạng thái…</option>
                    {['IN_PROGRESS', 'WAITING_CUSTOMER', 'RESOLVED', 'CLOSED'].filter((s) => s !== t.status)
                      .map((s) => <option key={s} value={s}>{TICKET_STATUS_STAFF_LABEL[s]}</option>)}
                  </select>
                  <select value={t.priority} onChange={(e) => update({ priority: e.target.value })}>
                    {Object.entries(TICKET_PRIORITY_LABEL).map(([k, v]) => <option key={k} value={k}>Ưu tiên: {v}</option>)}
                  </select>
                </div>
              )}
              <SupportChat key={t.id} ticketId={t.id} initial={detail.messages} closed={t.status === 'CLOSED' || !canReply}
                           viewer="staff" onTicket={(nt) => setDetail((d) => (d ? { ...d, ticket: nt } : d))} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}
