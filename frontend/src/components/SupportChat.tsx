import { FormEvent, useEffect, useRef, useState } from 'react';
import { api, ApiError } from '../api/client';
import type { Ticket, TicketMessage } from '../api/types';
import { useEventSource } from '../hooks/useEventSource';
import { dateTime } from '../utils/format';

/**
 * Khung chat của một yêu cầu hỗ trợ: tin nhắn realtime qua SSE (/api/support/tickets/{id}/stream),
 * gửi tin qua REST. Dùng chung cho khách và nhân viên CSKH.
 */
export function SupportChat({ ticketId, initial, closed, viewer, onTicket }: {
  ticketId: number;
  initial: TicketMessage[];
  closed: boolean;
  viewer: 'customer' | 'staff';
  onTicket?: (t: Ticket) => void;
}) {
  const [messages, setMessages] = useState<TicketMessage[]>(initial);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => setMessages(initial), [initial]);

  const add = (m: TicketMessage) => setMessages((list) => (list.some((x) => x.id === m.id) ? list : [...list, m]));

  const live = useEventSource(`/api/support/tickets/${ticketId}/stream`, {
    message: (m: TicketMessage) => add(m),
    ticket: (t: Ticket) => onTicket?.(t),
  });

  useEffect(() => {
    bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [messages.length]);

  const send = (e: FormEvent) => {
    e.preventDefault();
    const content = text.trim();
    if (!content) return;
    setBusy(true);
    setError(null);
    api.post<TicketMessage>(`/api/support/tickets/${ticketId}/messages`, { content })
      .then((m) => { add(m); setText(''); })
      .catch((err) => setError(err instanceof ApiError && err.errors ? Object.values(err.errors).join('. ') : err.message))
      .finally(() => setBusy(false));
  };

  const mine = (m: TicketMessage) => (viewer === 'customer' ? m.kind === 'CUSTOMER' : m.kind === 'STAFF');

  return (
    <div className="chat">
      <div className="chat-status muted tiny">
        <span className={`dot ${live === 'open' ? 'dot-open' : 'dot-closed'}`} /> {live === 'open' ? 'Trực tuyến – tin nhắn mới hiện ngay' : 'Đang kết nối…'}
      </div>
      <div className="chat-messages">
        {messages.map((m) => (
          m.kind === 'SYSTEM'
            ? <div key={m.id} className="chat-system">{m.content} · {dateTime(m.createdAt)}</div>
            : (
              <div key={m.id} className={`chat-bubble ${mine(m) ? 'me' : 'them'} ${m.kind === 'STAFF' ? 'staff' : ''}`}>
                <div className="tiny muted">{m.authorName} · {dateTime(m.createdAt)}</div>
                <div className="chat-text">{m.content}</div>
              </div>
            )
        ))}
        <div ref={bottom} />
      </div>
      {closed ? (
        <div className="muted small">Yêu cầu đã đóng.{viewer === 'customer' ? ' Nếu còn vấn đề, vui lòng tạo yêu cầu mới.' : ''}</div>
      ) : (
        <form className="chat-input" onSubmit={send}>
          <textarea rows={2} value={text} maxLength={2000} placeholder="Nhập tin nhắn… (Enter để gửi, Shift+Enter xuống dòng)"
                    onChange={(e) => setText(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && !e.shiftKey) {
                        e.preventDefault();
                        (e.currentTarget.form as HTMLFormElement | null)?.requestSubmit();
                      }
                    }} />
          <button className="btn btn-primary" disabled={busy || !text.trim()}>Gửi</button>
        </form>
      )}
      {error && <div className="alert alert-error">{error}</div>}
    </div>
  );
}
