import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import { ErrorBox, Loading } from '../../components/Common';
import { dateTime, money, number } from '../../utils/format';

interface Settings {
  sessionsPerMinute: number;
  maxConcurrentSessions: number;
  browseOnlyRate: number;
  cartAbandonRate: number;
  paymentFailureRate: number;
  cancelRate: number;
  newCustomerRate: number;
  wave: boolean;
  autoRestock: boolean;
  autoFulfill: boolean;
}

interface Entry {
  at: string;
  kind: string;
  actor: string | null;
  message: string;
}

interface Status {
  running: boolean;
  startedAt: string | null;
  settings: Settings;
  currentRate: number;
  activeSessions: number;
  customers: number;
  catalogSize: number;
  stats: Record<string, number>;
  orderValue: number;
  recent: Entry[];
}

const KIND: Record<string, { icon: string; label: string }> = {
  ORDER: { icon: '🛒', label: 'Đặt hàng' },
  CANCEL: { icon: '↩️', label: 'Hủy đơn' },
  BROWSE_ONLY: { icon: '👀', label: 'Chỉ xem' },
  ABANDON_CART: { icon: '🧺', label: 'Bỏ giỏ' },
  REGISTER: { icon: '🆕', label: 'Đăng ký' },
  RESTOCK: { icon: '📦', label: 'Nhập kho' },
  SHIP: { icon: '🚚', label: 'Giao hàng' },
  DELIVER: { icon: '✅', label: 'Đã giao' },
  BURST: { icon: '⚡', label: 'Mua dồn dập' },
  ERROR: { icon: '⚠️', label: 'Lỗi' },
};

const RATES: { key: keyof Settings; label: string; hint: string }[] = [
  { key: 'browseOnlyRate', label: 'Chỉ xem rồi rời đi', hint: 'khách không thêm gì vào giỏ' },
  { key: 'cartAbandonRate', label: 'Bỏ giỏ hàng', hint: 'thêm vào giỏ nhưng không đặt' },
  { key: 'paymentFailureRate', label: 'Thẻ bị từ chối', hint: 'chọn MOCK_FAIL → kích hoạt luồng bù Saga' },
  { key: 'cancelRate', label: 'Hủy sau khi đặt', hint: 'khách đổi ý vài giây sau' },
  { key: 'newCustomerRate', label: 'Khách mới đăng ký', hint: 'tạo tài khoản mới' },
];

export default function SimulatorPage() {
  const [status, setStatus] = useState<Status | null>(null);
  const [draft, setDraft] = useState<Settings | null>(null);
  const [burst, setBurst] = useState({ orders: 60, seconds: 30 });
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const load = useCallback(() => {
    api.get<Status>('/api/simulator')
      .then((s) => {
        setStatus(s);
        setDraft((d) => d ?? s.settings);
      })
      .catch((e) => setError(e.message));
  }, []);

  useEffect(() => {
    load();
    const t = window.setInterval(load, 2000);
    return () => window.clearInterval(t);
  }, [load]);

  const act = (p: Promise<Status>) => {
    setError(null);
    p.then(setStatus).catch((e) => setError(e.message));
  };

  const save = () => {
    if (!draft) return;
    act(api.put<Status>('/api/simulator/settings', draft));
    setSaved(true);
    window.setTimeout(() => setSaved(false), 2000);
  };

  if (!status || !draft) return error ? <ErrorBox error={error} /> : <Loading />;
  const s = status.stats;
  const sessions = s.SESSION ?? 0;
  const conversion = sessions === 0 ? 0 : Math.round((100 * (s.ORDER ?? 0)) / sessions);

  return (
    <div>
      <div className="row space-between">
        <h2>🤖 Khách hàng ảo</h2>
        <div className="row">
          <span className={`live ${status.running ? 'open' : ''}`}>
            <span className={`dot ${status.running ? 'dot-open' : 'dot-closed'}`} />
            {status.running ? `Đang chạy · ${status.currentRate} lượt/phút` : 'Đã dừng'}
          </span>
          {status.running
            ? <button className="btn btn-danger" onClick={() => act(api.post<Status>('/api/simulator/stop'))}>⏹ Dừng</button>
            : <button className="btn btn-primary" onClick={() => act(api.post<Status>('/api/simulator/start'))}>▶ Bắt đầu</button>}
        </div>
      </div>
      <p className="muted small">
        Khách ảo truy cập hệ thống qua API Gateway giống người thật: xem, tìm kiếm, thêm giỏ, đặt hàng, đôi khi hủy hoặc bị từ chối thẻ.
        Nhân viên ảo tự nhập kho khi sắp hết và giao các đơn đã xác nhận. Kết quả xem tại <Link to="/admin">Dashboard</Link>.
      </p>
      <ErrorBox error={error} />

      <div className="kpis">
        <Stat label="Phiên mua sắm" value={number(sessions)} hint={`${status.activeSessions} đang diễn ra`} />
        <Stat label="Đơn đã đặt" value={number(s.ORDER)} hint={`tỷ lệ chuyển đổi ${conversion}%`} />
        <Stat label="Giá trị đơn đặt" value={money(status.orderValue)} />
        <Stat label="Chỉ xem / bỏ giỏ" value={`${number(s.BROWSE_ONLY)} / ${number(s.ABANDON_CART)}`} />
        <Stat label="Hủy đơn" value={number(s.CANCEL)} />
        <Stat label="Khách ảo" value={number(status.customers)} hint={`${number(s.REGISTER)} đăng ký mới`} />
        <Stat label="Nhập kho / giao / đã giao" value={`${number(s.RESTOCK)} / ${number(s.SHIP)} / ${number(s.DELIVER)}`} />
        <Stat label="Lỗi" value={number(s.ERROR)} bad={(s.ERROR ?? 0) > 0} />
      </div>

      <div className="grid-2">
        <div className="card">
          <h3>Cấu hình</h3>
          <div className="form">
            <label>
              Số phiên mua sắm / phút: <strong>{draft.sessionsPerMinute}</strong>
              <input type="range" min={1} max={300} value={draft.sessionsPerMinute}
                     onChange={(e) => setDraft({ ...draft, sessionsPerMinute: Number(e.target.value) })} />
            </label>
            {RATES.map((r) => (
              <label key={r.key}>
                {r.label}: <strong>{Math.round(Number(draft[r.key]) * 100)}%</strong> <span className="muted tiny">({r.hint})</span>
                <input type="range" min={0} max={100} value={Math.round(Number(draft[r.key]) * 100)}
                       onChange={(e) => setDraft({ ...draft, [r.key]: Number(e.target.value) / 100 })} />
              </label>
            ))}
            <label>Số phiên đồng thời tối đa
              <input type="number" min={1} max={200} value={draft.maxConcurrentSessions}
                     onChange={(e) => setDraft({ ...draft, maxConcurrentSessions: Number(e.target.value) })} />
            </label>
            <label className="radio"><input type="checkbox" checked={draft.wave} onChange={(e) => setDraft({ ...draft, wave: e.target.checked })} />
              Lưu lượng dao động (giờ cao điểm / thấp điểm, chu kỳ 10 phút)</label>
            <label className="radio"><input type="checkbox" checked={draft.autoRestock} onChange={(e) => setDraft({ ...draft, autoRestock: e.target.checked })} />
              Nhân viên kho ảo tự nhập hàng khi sắp hết</label>
            <label className="radio"><input type="checkbox" checked={draft.autoFulfill} onChange={(e) => setDraft({ ...draft, autoFulfill: e.target.checked })} />
              Nhân viên ảo tự giao đơn (CONFIRMED → SHIPPING → DELIVERED)</label>
            <div className="row">
              <button className="btn btn-primary" onClick={save}>Áp dụng</button>
              <button className="btn btn-ghost" onClick={() => setDraft(status.settings)}>Hoàn tác</button>
              {saved && <span className="muted small">Đã áp dụng ✓</span>}
            </div>
          </div>

          <h3 style={{ marginTop: 20 }}>⚡ Mua dồn dập (kiểm tra cảnh báo đột biến)</h3>
          <div className="row">
            <label>Số đơn<input type="number" min={1} max={500} value={burst.orders} onChange={(e) => setBurst({ ...burst, orders: Number(e.target.value) })} /></label>
            <label>Trong (giây)<input type="number" min={1} max={300} value={burst.seconds} onChange={(e) => setBurst({ ...burst, seconds: Number(e.target.value) })} /></label>
            <button className="btn" onClick={() => act(api.post<Status>('/api/simulator/burst', burst))}>Kích hoạt</button>
          </div>
          <p className="muted tiny">Vượt ngưỡng 30 đơn/phút (mặc định) → Dashboard hiện cảnh báo "Đơn hàng tăng đột biến".</p>
          <button className="btn btn-ghost" onClick={() => act(api.post<Status>('/api/simulator/reset-stats'))}>Đặt lại thống kê</button>
        </div>

        <div className="card">
          <h3>Hoạt động gần đây</h3>
          <div className="alerts-feed" style={{ maxHeight: 640 }}>
            {status.recent.length === 0 && <div className="empty">Chưa có hoạt động</div>}
            {status.recent.map((e, i) => (
              <div key={`${e.at}-${i}`} className={`feed-item ${e.kind === 'ERROR' ? 'sev-critical' : ''}`}
                   style={{ borderLeftColor: e.kind === 'ORDER' ? 'var(--good)' : undefined }}>
                <div className="small">
                  {KIND[e.kind]?.icon} <strong>{e.actor ?? 'hệ thống'}</strong> {e.message}
                </div>
                <div className="muted tiny">{KIND[e.kind]?.label ?? e.kind} · {dateTime(e.at)}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function Stat({ label, value, hint, bad }: { label: string; value: string; hint?: string; bad?: boolean }) {
  return (
    <div className={`kpi card ${bad ? 'bad' : ''}`}>
      <div className="muted small">{label}</div>
      <div className="kpi-value">{value}</div>
      {hint && <div className="muted tiny">{hint}</div>}
    </div>
  );
}
