import { FormEvent, useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { AuditEntry, AuditSummary, Page } from '../../api/types';
import { ErrorBox, Loading, Pagination } from '../../components/Common';
import { AUDIT_ACTION_LABEL, dateTime, number } from '../../utils/format';

interface Filters { q: string; action: string; outcome: string; actor: string; resourceType: string; resourceId: string }
const EMPTY: Filters = { q: '', action: '', outcome: '', actor: '', resourceType: '', resourceId: '' };

/** Nhật ký kiểm toán (Audit Service) – chỉ ADMIN: ai đã làm gì, khi nào, từ IP nào; cảnh báo dò mật khẩu. */
export default function AuditPage() {
  const [summary, setSummary] = useState<AuditSummary | null>(null);
  const [actions, setActions] = useState<string[]>([]);
  const [draft, setDraft] = useState<Filters>(EMPTY);
  const [filters, setFilters] = useState<Filters>(EMPTY);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AuditEntry> | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<AuditEntry>>('/api/audit', { ...filters, page, size: 30 }).then(setData).catch((e) => setError(e.message));
    api.get<AuditSummary>('/api/audit/summary', { hours: 24 }).then(setSummary).catch(() => undefined);
  }, [filters, page]);

  useEffect(load, [load]);
  useEffect(() => {
    api.get<string[]>('/api/audit/actions').then(setActions).catch(() => undefined);
    const t = window.setInterval(load, 10_000);
    return () => window.clearInterval(t);
  }, [load]);

  const search = (e: FormEvent) => {
    e.preventDefault();
    setPage(0);
    setFilters(draft);
  };
  const quick = (patch: Partial<Filters>) => {
    const next = { ...EMPTY, ...patch };
    setDraft(next);
    setFilters(next);
    setPage(0);
  };

  return (
    <div>
      <h2>🔐 Nhật ký kiểm toán</h2>
      <p className="muted small">
        Mọi thao tác quản trị (sản phẩm, kho, đơn, vận đơn, rủi ro, đánh giá, khuyến mãi, tài khoản) và sự kiện bảo mật
        (đăng nhập sai, khóa tài khoản) được ghi qua Kafka topic <code>audit-events</code> trong cùng transaction với thao tác.
      </p>
      {summary && (
        <div className="kpis">
          <div className="kpi card"><div className="muted small">Sự kiện 24 giờ</div><div className="kpi-value">{number(summary.total)}</div></div>
          <div className={`kpi card ${summary.failures > 0 ? 'bad' : ''}`} onClick={() => quick({ outcome: 'FAILURE' })} style={{ cursor: 'pointer' }}>
            <div className="muted small">Thất bại</div><div className="kpi-value">{number(summary.failures)}</div>
          </div>
          <div className="kpi card" onClick={() => quick({ action: 'LOGIN_FAILED' })} style={{ cursor: 'pointer' }}>
            <div className="muted small">Đăng nhập sai</div><div className="kpi-value">{number(summary.failedLogins)}</div>
          </div>
          <div className={`kpi card ${summary.lockedAccounts > 0 ? 'bad' : ''}`} onClick={() => quick({ action: 'ACCOUNT_LOCKED' })} style={{ cursor: 'pointer' }}>
            <div className="muted small">Tài khoản bị tạm khóa</div><div className="kpi-value">{number(summary.lockedAccounts)}</div>
          </div>
        </div>
      )}
      {summary && (
        <div className="grid-2">
          <div className="card">
            <h3>Thao tác nhiều nhất (24 giờ)</h3>
            {summary.byAction.length === 0 ? <div className="empty">Chưa có dữ liệu</div> : (
              <table className="table compact">
                <tbody>
                  {summary.byAction.map((a) => (
                    <tr key={a.key} onClick={() => quick({ action: a.key })} style={{ cursor: 'pointer' }}>
                      <td>{AUDIT_ACTION_LABEL[a.key] ?? a.key}</td><td className="muted tiny mono">{a.key}</td><td>{number(a.count)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          <div className="card">
            <h3>IP có nhiều lần thất bại</h3>
            {summary.suspiciousIps.length === 0 ? <div className="empty">Không có IP đáng ngờ 🎉</div> : (
              <table className="table compact">
                <thead><tr><th>IP</th><th>Lần thất bại</th><th>Số tài khoản bị thử</th></tr></thead>
                <tbody>
                  {summary.suspiciousIps.map((ip) => (
                    <tr key={ip.ip} className={ip.accounts >= 5 ? 'text-bad' : ''} onClick={() => quick({ q: ip.ip })} style={{ cursor: 'pointer' }}>
                      <td className="mono">{ip.ip}</td><td>{number(ip.failures)}</td><td>{number(ip.accounts)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}

      <div className="card">
        <form className="row" onSubmit={search} style={{ flexWrap: 'wrap' }}>
          <input placeholder="Tìm nội dung / IP / correlationId" value={draft.q} onChange={(e) => setDraft({ ...draft, q: e.target.value })} style={{ minWidth: 220 }} />
          <input placeholder="Email người thao tác" value={draft.actor} onChange={(e) => setDraft({ ...draft, actor: e.target.value })} />
          <select value={draft.action} onChange={(e) => setDraft({ ...draft, action: e.target.value })}>
            <option value="">Mọi thao tác</option>
            {actions.map((a) => <option key={a} value={a}>{AUDIT_ACTION_LABEL[a] ?? a}</option>)}
          </select>
          <select value={draft.outcome} onChange={(e) => setDraft({ ...draft, outcome: e.target.value })}>
            <option value="">Mọi kết quả</option>
            <option value="SUCCESS">Thành công</option>
            <option value="FAILURE">Thất bại</option>
          </select>
          <input placeholder="Loại đối tượng (ORDER, USER…)" value={draft.resourceType} onChange={(e) => setDraft({ ...draft, resourceType: e.target.value })} style={{ width: 170 }} />
          <input placeholder="Mã đối tượng" value={draft.resourceId} onChange={(e) => setDraft({ ...draft, resourceId: e.target.value })} style={{ width: 110 }} />
          <button className="btn btn-primary">Lọc</button>
          <button type="button" className="btn btn-ghost" onClick={() => quick({})}>Xóa lọc</button>
        </form>
        <ErrorBox error={error} />
        {!data ? <Loading /> : data.content.length === 0 ? <div className="empty">Không có bản ghi phù hợp</div> : (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>Thời gian</th><th>Người thao tác</th><th>Thao tác</th><th>Đối tượng</th><th>Chi tiết</th><th>IP</th></tr></thead>
              <tbody>
                {data.content.map((e) => (
                  <tr key={e.id} className={e.outcome === 'FAILURE' ? 'row-failure' : ''}>
                    <td className="small">{dateTime(e.occurredAt)}<div className="muted tiny">{e.source}</div></td>
                    <td className="small">{e.actorEmail ?? <span className="muted">—</span>}<div className="muted tiny">{e.actorRoles}</div></td>
                    <td className="small">
                      {e.outcome === 'FAILURE' ? '❌ ' : ''}{AUDIT_ACTION_LABEL[e.action] ?? e.action}
                      <div className="muted tiny mono">{e.action}</div>
                    </td>
                    <td className="small mono">{e.resourceType}{e.resourceId ? ` #${e.resourceId}` : ''}</td>
                    <td className="small">{e.detail}<div className="muted tiny mono" title="correlationId – tra cứu log mọi service">{e.correlationId}</div></td>
                    <td className="small mono">{e.clientIp ?? '—'}</td>
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
