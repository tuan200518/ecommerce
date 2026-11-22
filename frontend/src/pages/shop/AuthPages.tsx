import { FormEvent, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuth, STAFF_ROLES } from '../../auth/AuthContext';
import { ApiError } from '../../api/client';
import { ErrorBox } from '../../components/Common';

const DEMO_ACCOUNTS = [
  ['customer@shop.local', 'Customer@123', 'Khách hàng'],
  ['admin@shop.local', 'Admin@123', 'Quản trị'],
  ['pm@shop.local', 'Manager@123', 'QL sản phẩm'],
  ['warehouse@shop.local', 'Manager@123', 'QL kho'],
  ['analyst@shop.local', 'Analyst@123', 'Phân tích'],
];

export function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const user = await login(email, password);
      const from = (location.state as { from?: string } | null)?.from;
      navigate(from ?? (user.roles.some((r) => STAFF_ROLES.includes(r)) ? '/admin' : '/'));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Không thể đăng nhập');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth-card card">
      <h2>Đăng nhập</h2>
      <form onSubmit={submit} className="form">
        <label>Email<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></label>
        <label>Mật khẩu<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required /></label>
        <ErrorBox error={error} />
        <button className="btn btn-primary" disabled={busy}>{busy ? 'Đang đăng nhập…' : 'Đăng nhập'}</button>
      </form>
      <p className="muted small">Chưa có tài khoản? <Link to="/register">Đăng ký</Link></p>
      <div className="demo-accounts">
        <div className="muted small">Tài khoản demo (bấm để điền):</div>
        {DEMO_ACCOUNTS.map(([em, pw, label]) => (
          <button key={em} type="button" className="chip" onClick={() => { setEmail(em); setPassword(pw); }}>
            {label}: {em}
          </button>
        ))}
      </div>
    </div>
  );
}

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ fullName: '', email: '', password: '' });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await register(form.email, form.password, form.fullName);
      navigate('/');
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.errors ? Object.values(err.errors).join('. ') : err.message);
      } else {
        setError('Không thể đăng ký');
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth-card card">
      <h2>Tạo tài khoản</h2>
      <form onSubmit={submit} className="form">
        <label>Họ tên<input value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} required /></label>
        <label>Email<input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} required /></label>
        <label>Mật khẩu (≥ 6 ký tự)<input type="password" minLength={6} value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required /></label>
        <ErrorBox error={error} />
        <button className="btn btn-primary" disabled={busy}>{busy ? 'Đang tạo…' : 'Đăng ký'}</button>
      </form>
      <p className="muted small">Đã có tài khoản? <Link to="/login">Đăng nhập</Link></p>
    </div>
  );
}
