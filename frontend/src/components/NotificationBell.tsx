import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Notification, Page } from '../api/types';
import { useEventSource } from '../hooks/useEventSource';
import { dateTime } from '../utils/format';

/** Chuông thông báo realtime (SSE từ Notification Service qua API Gateway). */
export default function NotificationBell() {
  const [items, setItems] = useState<Notification[]>([]);
  const [unread, setUnread] = useState(0);
  const [open, setOpen] = useState(false);
  const [toast, setToast] = useState<Notification | null>(null);
  const toastTimer = useRef<number>();

  const load = useCallback(() => {
    api.get<Page<Notification>>('/api/notifications', { size: 15 }).then((p) => setItems(p.content)).catch(() => undefined);
    api.get<{ count: number }>('/api/notifications/unread-count').then((r) => setUnread(r.count)).catch(() => undefined);
  }, []);

  useEffect(load, [load]);

  const status = useEventSource('/api/notifications/stream', {
    notification: (n: Notification) => {
      setItems((prev) => [n, ...prev.filter((x) => x.id !== n.id)].slice(0, 15));
      if (!n.broadcast) setUnread((c) => c + 1);
      setToast(n);
      window.clearTimeout(toastTimer.current);
      toastTimer.current = window.setTimeout(() => setToast(null), 5000);
      window.dispatchEvent(new CustomEvent('shop:notification', { detail: n }));
    },
  });

  const markAll = () => {
    api.post('/api/notifications/read-all').then(() => {
      setUnread(0);
      setItems((prev) => prev.map((n) => ({ ...n, read: true })));
    }).catch(() => undefined);
  };

  const linkOf = (n: Notification) => (n.referenceId?.startsWith('order:') ? `/orders/${n.referenceId.slice(6)}` : null);

  return (
    <div className="bell">
      <button className="btn btn-ghost bell-btn" onClick={() => setOpen((o) => !o)} aria-label="Thông báo">
        🔔{unread > 0 && <span className="bell-count">{unread > 99 ? '99+' : unread}</span>}
        <span className={`dot dot-${status}`} title={status === 'open' ? 'Realtime đang kết nối' : 'Đang kết nối lại…'} />
      </button>
      {open && (
        <div className="bell-panel">
          <div className="bell-head">
            <strong>Thông báo</strong>
            <button className="btn btn-link" onClick={markAll}>Đánh dấu đã đọc</button>
          </div>
          {items.length === 0 && <div className="empty">Chưa có thông báo</div>}
          {items.map((n) => {
            const link = linkOf(n);
            const body = (
              <>
                <div className="bell-title">{n.title}</div>
                {n.message && <div className="muted small">{n.message}</div>}
                <div className="muted tiny">{dateTime(n.createdAt)}</div>
              </>
            );
            return (
              <div key={n.id} className={`bell-item ${n.read || n.broadcast ? '' : 'unread'}`}>
                {link ? <Link to={link} onClick={() => setOpen(false)}>{body}</Link> : body}
              </div>
            );
          })}
        </div>
      )}
      {toast && (
        <div className="toast" onClick={() => setToast(null)}>
          <strong>{toast.title}</strong>
          {toast.message && <div className="small">{toast.message}</div>}
        </div>
      )}
    </div>
  );
}
