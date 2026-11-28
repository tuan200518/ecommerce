import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { AlertEvent, Page } from '../../api/types';
import { ErrorBox, Loading, Pagination } from '../../components/Common';
import { dateTime } from '../../utils/format';

const CHANNEL_LABEL: Record<string, string> = {
  'in-app': '🔔 Thông báo trong ứng dụng', slack: 'Slack', discord: 'Discord', telegram: 'Telegram', email: '✉️ Email',
};

/** Cảnh báo hệ thống (Prometheus → Alertmanager → notification-service): kênh đang bật, gửi thử, lịch sử. */
export default function AlertsPage() {
  const [channels, setChannels] = useState<string[]>([]);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AlertEvent> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<AlertEvent>>('/api/notifications/admin/alerts', { page, size: 30 }).then(setData).catch((e) => setError(e.message));
  }, [page]);
  useEffect(load, [load]);
  useEffect(() => {
    api.get<{ channels: string[] }>('/api/notifications/admin/alerts/channels').then((r) => setChannels(r.channels)).catch(() => undefined);
    const t = window.setInterval(load, 15_000);
    return () => window.clearInterval(t);
  }, [load]);

  const test = () => {
    setTesting(true);
    setResult(null);
    api.post<AlertEvent>('/api/notifications/admin/alerts/test')
      .then((e) => { setResult(e.deliveries ?? ''); load(); })
      .catch((e) => setError(e.message))
      .finally(() => setTesting(false));
  };

  return (
    <div>
      <h2>🚨 Cảnh báo hệ thống</h2>
      <p className="muted small">
        Prometheus đánh giá luật cảnh báo (<code>infra/prometheus/alerts.yml</code>) → Alertmanager gom nhóm, chống lặp →
        notification-service gửi tới các kênh dưới đây. Cấu hình kênh bằng biến môi trường <code>ALERT_*</code> / <code>SMTP_*</code>.
      </p>
      <ErrorBox error={error} />
      <div className="card">
        <h3>Kênh đang bật</h3>
        <div className="cat-list">{channels.map((c) => <span key={c} className="chip">{CHANNEL_LABEL[c] ?? c}</span>)}</div>
        <div className="row" style={{ marginTop: 8 }}>
          <button className="btn btn-primary" onClick={test} disabled={testing}>{testing ? 'Đang gửi…' : 'Gửi cảnh báo thử'}</button>
          {result && <span className="small">{result}</span>}
        </div>
        <p className="muted tiny">Email mặc định được giữ trong Mailpit: <a href="http://localhost:8025" target="_blank" rel="noreferrer">http://localhost:8025</a>.
          Alertmanager: <a href="http://localhost:9093" target="_blank" rel="noreferrer">http://localhost:9093</a> · Prometheus: <a href="http://localhost:9090/alerts" target="_blank" rel="noreferrer">/alerts</a></p>
      </div>
      <div className="card">
        <h3>Lịch sử</h3>
        {!data ? <Loading /> : data.content.length === 0 ? <div className="empty">Chưa có cảnh báo nào 🎉</div> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Nhận lúc</th><th>Cảnh báo</th><th>Nội dung</th><th>Kết quả gửi</th></tr></thead>
              <tbody>
                {data.content.map((a) => (
                  <tr key={a.id} className={a.status === 'resolved' ? 'disabled' : a.severity === 'critical' ? 'row-failure' : ''}>
                    <td className="small">{dateTime(a.receivedAt)}</td>
                    <td className="small"><strong>{a.alertName}</strong><div className="muted tiny">{a.status === 'resolved' ? 'đã khôi phục' : a.severity}{a.target ? ` · ${a.target}` : ''}</div></td>
                    <td className="small">{a.title}<div className="muted tiny" style={{ whiteSpace: 'pre-line' }}>{a.message}</div></td>
                    <td className="small">{(a.deliveries ?? '').split('; ').map((d) => (
                      <div key={d} className={d.includes('lỗi') ? 'text-bad' : 'text-good'}>{d}</div>
                    ))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      </div>
    </div>
  );
}
