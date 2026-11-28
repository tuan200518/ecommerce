import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Ticket, TicketDetail } from '../../api/types';
import { ErrorBox, Loading } from '../../components/Common';
import { SupportChat } from '../../components/SupportChat';
import { dateTime, money, ORDER_STATUS_LABEL, TICKET_CATEGORY_LABEL, TICKET_STATUS_LABEL } from '../../utils/format';

/** Chi tiết yêu cầu hỗ trợ phía khách: chat realtime, đóng yêu cầu, đánh giá sau khi giải quyết. */
export default function TicketPage() {
  const { id } = useParams();
  const [detail, setDetail] = useState<TicketDetail | null>(null);
  const [ticket, setTicket] = useState<Ticket | null>(null);
  const [rating, setRating] = useState(5);
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<TicketDetail>(`/api/support/tickets/${id}`)
      .then((d) => { setDetail(d); setTicket(d.ticket); })
      .catch((e) => setError(e.message));
  }, [id]);
  useEffect(load, [load]);

  if (!detail || !ticket) return error ? <ErrorBox error={error} /> : <Loading />;

  const close = () => {
    if (!window.confirm('Đóng yêu cầu này? Bạn có thể tạo yêu cầu mới nếu cần hỗ trợ thêm.')) return;
    api.post<Ticket>(`/api/support/tickets/${ticket.id}/close`).then(setTicket).catch((e) => setError(e.message));
  };
  const rate = () => {
    api.post<Ticket>(`/api/support/tickets/${ticket.id}/rating`, { rating, comment: comment || null })
      .then(setTicket).catch((e) => setError(e.message));
  };
  const finished = ticket.status === 'RESOLVED' || ticket.status === 'CLOSED';

  return (
    <div>
      <Link to="/support" className="small">← Tất cả yêu cầu</Link>
      <div className="row space-between">
        <h2>{ticket.code}: {ticket.subject}</h2>
        <span className={`badge badge-ticket-${ticket.status.toLowerCase()}`}>{TICKET_STATUS_LABEL[ticket.status]}</span>
      </div>
      <div className="muted small">
        {TICKET_CATEGORY_LABEL[ticket.category]} · gửi lúc {dateTime(ticket.createdAt)}
        {ticket.assigneeEmail && <> · đang được hỗ trợ bởi CSKH</>}
        {detail.order && <> · <Link to={`/orders/${detail.order.orderId}`}>Đơn #{detail.order.orderId}</Link> ({ORDER_STATUS_LABEL[detail.order.status] ?? detail.order.status}{detail.order.totalAmount ? `, ${money(detail.order.totalAmount)}` : ''})</>}
      </div>
      {ticket.status === 'RESOLVED' && (
        <div className="alert alert-success">Yêu cầu đã được giải quyết. Nếu vấn đề vẫn còn, chỉ cần gửi tin nhắn để mở lại.</div>
      )}
      <ErrorBox error={error} />

      <div className="card">
        <SupportChat ticketId={ticket.id} initial={detail.messages} closed={ticket.status === 'CLOSED'} viewer="customer"
                     onTicket={(t) => setTicket({ ...t, unread: 0 })} />
        {ticket.status !== 'CLOSED' && <button className="btn btn-ghost" onClick={close} style={{ marginTop: 8 }}>Đóng yêu cầu</button>}
      </div>

      {finished && (
        <div className="card">
          <h3>Đánh giá hỗ trợ</h3>
          {ticket.rating ? (
            <p>Bạn đã đánh giá <strong>{'★'.repeat(ticket.rating)}{'☆'.repeat(5 - ticket.rating)}</strong>{ticket.ratingComment ? ` – “${ticket.ratingComment}”` : ''}. Cảm ơn bạn!</p>
          ) : (
            <div className="form">
              <div className="star-input">
                {[1, 2, 3, 4, 5].map((s) => (
                  <button key={s} type="button" className={s <= rating ? 'on' : ''} onClick={() => setRating(s)} aria-label={`${s} sao`}>★</button>
                ))}
              </div>
              <input value={comment} onChange={(e) => setComment(e.target.value)} maxLength={500} placeholder="Góp ý thêm (không bắt buộc)" />
              <div><button className="btn btn-primary" onClick={rate}>Gửi đánh giá</button></div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
