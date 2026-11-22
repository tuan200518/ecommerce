import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Page, User } from '../../api/types';
import { ErrorBox, Loading, Modal, Pagination } from '../../components/Common';
import { dateTime, ROLE_LABEL } from '../../utils/format';

export default function UsersAdminPage() {
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<User> | null>(null);
  const [editing, setEditing] = useState<{ user: User; roles: string[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<User>>('/api/auth/users', { q, page, size: 20 }).then(setData).catch((e) => setError(e.message));
  }, [q, page]);

  useEffect(load, [load]);

  const saveRoles = () => {
    if (!editing) return;
    api.put(`/api/auth/users/${editing.user.id}/roles`, { roles: editing.roles })
      .then(() => { setEditing(null); load(); })
      .catch((e) => setError(e.message));
  };

  const toggleEnabled = (u: User) =>
    api.put(`/api/auth/users/${u.id}/status`, { enabled: !u.enabled }).then(load).catch((e) => setError(e.message));

  return (
    <div>
      <h2>Tài khoản & phân quyền</h2>
      <ErrorBox error={error} />
      <div className="card">
        <input placeholder="Tìm email / họ tên" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
        {!data ? <Loading /> : (
          <table className="table">
            <thead><tr><th>ID</th><th>Email</th><th>Họ tên</th><th>Vai trò</th><th>Đăng nhập gần nhất</th><th>Trạng thái</th><th /></tr></thead>
            <tbody>
              {data.content.map((u) => (
                <tr key={u.id} className={u.enabled ? '' : 'disabled'}>
                  <td>{u.id}</td><td>{u.email}</td><td>{u.fullName}</td>
                  <td>{u.roles.map((r) => <span key={r} className="chip small">{ROLE_LABEL[r] ?? r}</span>)}</td>
                  <td className="small">{dateTime(u.lastLoginAt)}</td>
                  <td>{u.enabled ? 'Hoạt động' : 'Đã khóa'}</td>
                  <td className="row">
                    <button className="btn btn-ghost" onClick={() => setEditing({ user: u, roles: [...u.roles] })}>Phân quyền</button>
                    <button className="btn btn-ghost" onClick={() => toggleEnabled(u)}>{u.enabled ? 'Khóa' : 'Mở khóa'}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      </div>
      {editing && (
        <Modal title={`Phân quyền: ${editing.user.email}`} onClose={() => setEditing(null)}>
          {Object.entries(ROLE_LABEL).map(([role, label]) => (
            <label key={role} className="radio">
              <input type="checkbox" checked={editing.roles.includes(role)}
                     onChange={(e) => setEditing({ ...editing, roles: e.target.checked ? [...editing.roles, role] : editing.roles.filter((r) => r !== role) })} />
              {label} <span className="muted tiny">({role})</span>
            </label>
          ))}
          <p className="muted small">Quyền mới có hiệu lực khi người dùng đăng nhập lại (JWT mới).</p>
          <button className="btn btn-primary" disabled={editing.roles.length === 0} onClick={saveRoles}>Lưu</button>
        </Modal>
      )}
    </div>
  );
}
