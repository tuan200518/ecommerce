import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { FraudSummary, Page, RiskCase } from '../../api/types';
import { ErrorBox, Loading, Modal, Pagination } from '../../components/Common';
import { dateTime, money, number, RISK_LEVEL_LABEL, RISK_STATUS_LABEL } from '../../utils/format';

/** Xác minh rủi ro (Fraud Service): hàng đợi đơn bị gắn cờ, lý do, quyết định chấp nhận / từ chối. */
export default function FraudAdminPage() {
  const [status, setStatus] = useState('PENDING_REVIEW');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<RiskCase> | null>(null);
  const [summary, setSummary] = useState<FraudSummary | null>(null);
  const [detail, setDetail] = useState<RiskCase | null>(null);
  const [note, setNote] = useState('');
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<RiskCase>>('/api/fraud/cases', { status, page, size: 20 }).then(setData).catch((e) => setError(e.message));
    api.get<FraudSummary>('/api/fraud/summary').then(setSummary).catch(() => undefined);
  }, [status, page]);

  useEffect(load, [load]);
  useEffect(() => {
    const t = window.setInterval(load, 5000);
    return () => window.clearInterval(t);
  }, [load]);

  const decide = (approved: boolean) => {
    if (!detail) return;
    setError(null);
    api.post<RiskCase>(`/api/fraud/cases/${detail.orderId}/review`, { approved, note })
      .then((c) => { setDetail(c); setNote(''); load(); })
      .catch((e) => setError(e.message));
  };

  const pending = summary?.byStatus.PENDING_REVIEW ?? 0;

  return (
    <div>
      <h2>Rủi ro & gian lận</h2>
      <ErrorBox error={error} />
      {summary && (
        <div className="kpis">
          <div className={`kpi card ${pending > 0 ? 'bad' : 'good'}`}><div className="muted small">Chờ xác minh</div><div className="kpi-value">{number(pending)}</div></div>
          <div className="kpi card"><div className="muted small">Rủi ro cao</div><div className="kpi-value">{number(summary.byLevel.HIGH ?? 0)}</div></div>
          <div className="kpi card"><div className="muted small">Rủi ro trung bình</div><div className="kpi-value">{number(summary.byLevel.MEDIUM ?? 0)}</div></div>
          <div className="kpi card"><div className="muted small">Đã từ chối</div><div className="kpi-value">{number(summary.byStatus.REJECTED ?? 0)}</div></div>
        </div>
      )}

      <div className="card">
        <div className="tabs">
          {['PENDING_REVIEW', 'APPROVED', 'REJECTED', 'CLOSED', 'AUTO_APPROVED', ''].map((s) => (
            <button key={s || 'all'} className={`tab ${status === s ? 'active' : ''}`} onClick={() => { setStatus(s); setPage(0); }}>
              {s ? RISK_STATUS_LABEL[s] : 'Tất cả'}
            </button>
          ))}
        </div>
        {!data ? <Loading /> : data.content.length === 0 ? <div className="empty">Không có hồ sơ</div> : (
          <table className="table">
            <thead><tr><th>Đơn</th><th>Khách</th><th>Giá trị</th><th>Điểm</th><th>Mức</th><th>Lý do chính</th><th>Thời gian</th><th /></tr></thead>
            <tbody>
              {data.content.map((c) => (
                <tr key={c.orderId}>
                  <td>#{c.orderId}</td>
                  <td>User #{c.userId}</td>
                  <td>{money(c.totalAmount)}</td>
                  <td><strong>{c.score}</strong></td>
                  <td><span className={`badge badge-${c.level.toLowerCase()}`}>{RISK_LEVEL_LABEL[c.level] ?? c.level}</span></td>
                  <td className="small">{c.signals.map((s) => s.rule).join(', ') || '—'}</td>
                  <td className="small">{dateTime(c.createdAt)}</td>
                  <td><button className="btn btn-ghost" onClick={() => { setDetail(c); setNote(''); }}>Xem</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      </div>

      {summary && (
        <div className="card">
          <h3>Bộ luật chấm điểm (rule-based)</h3>
          <p className="muted small">
            Đơn cần xác minh khi tổng điểm ≥ {summary.reviewScore} <strong>và</strong> có ít nhất {summary.minSignalsForReview} tín hiệu —
            một dấu hiệu đơn lẻ không đủ để kết luận gian lận.
          </p>
          <table className="table compact">
            <thead><tr><th>Luật</th><th>Điều kiện</th><th>Điểm</th></tr></thead>
            <tbody>{summary.rules.map((r) => <tr key={r.code}><td><code>{r.code}</code></td><td className="small">{r.description}</td><td>+{r.points}</td></tr>)}</tbody>
          </table>
        </div>
      )}

      {detail && (
        <Modal title={`Hồ sơ rủi ro đơn #${detail.orderId}`} onClose={() => setDetail(null)}>
          <div className="row space-between">
            <span><span className={`badge badge-${detail.level.toLowerCase()}`}>{RISK_LEVEL_LABEL[detail.level]}</span> {detail.score}/100 điểm</span>
            <span className={`badge badge-${detail.status.toLowerCase()}`}>{RISK_STATUS_LABEL[detail.status] ?? detail.status}</span>
          </div>
          <p className="small">User #{detail.userId} · {money(detail.totalAmount)} · {dateTime(detail.createdAt)}</p>
          <h4>Tín hiệu rủi ro</h4>
          {detail.signals.length === 0 ? <p className="muted small">Không có tín hiệu nào</p> : (
            <ul className="signals">{detail.signals.map((s) => <li key={s.rule}><strong>+{s.points}</strong> {s.detail}</li>)}</ul>
          )}
          {detail.reviewer && <p className="small">Xử lý bởi {detail.reviewer} lúc {dateTime(detail.decidedAt)}{detail.reviewNote ? `: ${detail.reviewNote}` : ''}</p>}
          {!detail.reviewer && detail.reviewNote && <p className="small muted">{detail.reviewNote}</p>}
          {detail.status === 'PENDING_REVIEW' && (
            <div className="form">
              <label>Ghi chú xác minh (bắt buộc khi từ chối)
                <textarea rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
              </label>
              <div className="row">
                <button className="btn btn-primary" onClick={() => decide(true)}>✓ Chấp nhận – tiếp tục thanh toán</button>
                <button className="btn btn-danger" onClick={() => decide(false)} disabled={!note.trim()}>✕ Từ chối – hủy đơn</button>
              </div>
            </div>
          )}
        </Modal>
      )}
    </div>
  );
}
