import { FormEvent, useEffect, useState } from 'react';
import { api, ApiError } from '../../api/client';
import type { Address, Profile } from '../../api/types';
import { ErrorBox, Loading } from '../../components/Common';

const EMPTY_ADDRESS = { label: '', recipient: '', phone: '', line: '', city: '', makeDefault: false };

export default function ProfilePage() {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [form, setForm] = useState({ fullName: '', phone: '', dateOfBirth: '' });
  const [addr, setAddr] = useState(EMPTY_ADDRESS);
  const [editing, setEditing] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const load = () =>
    api.get<Profile>('/api/users/me').then((p) => {
      setProfile(p);
      setForm({ fullName: p.fullName ?? '', phone: p.phone ?? '', dateOfBirth: p.dateOfBirth ?? '' });
    }).catch((e) => setError(e.message));

  useEffect(() => {
    load();
  }, []);

  const fail = (err: unknown) =>
    setError(err instanceof ApiError && err.errors ? Object.values(err.errors).join('. ') : (err as Error).message);

  const saveProfile = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    api.put<Profile>('/api/users/me', { ...form, dateOfBirth: form.dateOfBirth || null })
      .then((p) => { setProfile(p); setMessage('Đã lưu hồ sơ'); })
      .catch(fail);
  };

  const saveAddress = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const req = editing ? api.put<Address>(`/api/users/me/addresses/${editing}`, addr) : api.post<Address>('/api/users/me/addresses', addr);
    req.then(() => { setAddr(EMPTY_ADDRESS); setEditing(null); load(); }).catch(fail);
  };

  const edit = (a: Address) => {
    setEditing(a.id);
    setAddr({ label: a.label ?? '', recipient: a.recipient, phone: a.phone, line: a.line, city: a.city, makeDefault: a.isDefault });
  };

  if (!profile) return error ? <ErrorBox error={error} /> : <Loading />;

  return (
    <div className="two-col">
      <form className="card form" onSubmit={saveProfile}>
        <h2>Hồ sơ</h2>
        <label>Email<input value={profile.email} disabled /></label>
        <label>Họ tên<input value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} required /></label>
        <label>Số điện thoại<input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} /></label>
        <label>Ngày sinh<input type="date" value={form.dateOfBirth} onChange={(e) => setForm({ ...form, dateOfBirth: e.target.value })} /></label>
        {message && <div className="alert alert-success">{message}</div>}
        <ErrorBox error={error} />
        <button className="btn btn-primary">Lưu hồ sơ</button>
      </form>

      <div className="card">
        <h2>Sổ địa chỉ</h2>
        {profile.addresses.map((a) => (
          <div key={a.id} className="address">
            <div>
              <strong>{a.label || 'Địa chỉ'}</strong> {a.isDefault && <span className="badge badge-confirmed">Mặc định</span>}
              <div className="small">{a.fullText}</div>
            </div>
            <div className="row">
              <button className="btn btn-ghost" onClick={() => edit(a)}>Sửa</button>
              <button className="btn btn-ghost" onClick={() => api.del(`/api/users/me/addresses/${a.id}`).then(load).catch(fail)}>Xóa</button>
            </div>
          </div>
        ))}
        <form className="form" onSubmit={saveAddress}>
          <h3>{editing ? 'Sửa địa chỉ' : 'Thêm địa chỉ'}</h3>
          <div className="row">
            <input placeholder="Nhãn (Nhà, Công ty…)" value={addr.label} onChange={(e) => setAddr({ ...addr, label: e.target.value })} />
            <input placeholder="Người nhận" value={addr.recipient} onChange={(e) => setAddr({ ...addr, recipient: e.target.value })} required />
          </div>
          <div className="row">
            <input placeholder="Số điện thoại" value={addr.phone} onChange={(e) => setAddr({ ...addr, phone: e.target.value })} required />
            <input placeholder="Tỉnh / Thành phố" value={addr.city} onChange={(e) => setAddr({ ...addr, city: e.target.value })} required />
          </div>
          <input placeholder="Số nhà, đường, phường, quận" value={addr.line} onChange={(e) => setAddr({ ...addr, line: e.target.value })} required />
          <label className="radio"><input type="checkbox" checked={addr.makeDefault} onChange={(e) => setAddr({ ...addr, makeDefault: e.target.checked })} /> Đặt làm mặc định</label>
          <div className="row">
            <button className="btn btn-primary">{editing ? 'Cập nhật' : 'Thêm địa chỉ'}</button>
            {editing && <button type="button" className="btn btn-ghost" onClick={() => { setEditing(null); setAddr(EMPTY_ADDRESS); }}>Hủy</button>}
          </div>
        </form>
      </div>
    </div>
  );
}
