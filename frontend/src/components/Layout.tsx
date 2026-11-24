import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import NotificationBell from './NotificationBell';
import { ROLE_LABEL } from '../utils/format';

const GRAFANA_URL = (import.meta.env.VITE_GRAFANA_URL as string | undefined) ?? 'http://localhost:3001';

export function ShopLayout() {
  const { user, logout, isStaff } = useAuth();
  const navigate = useNavigate();

  return (
    <div className="app">
      <header className="topbar">
        <div className="container topbar-inner">
          <NavLink to="/" className="brand">⚡ ShopStream</NavLink>
          <nav className="nav">
            <NavLink to="/" end>Sản phẩm</NavLink>
            <NavLink to="/deals">🔥 Khuyến mãi</NavLink>
            {user && <NavLink to="/cart">Giỏ hàng</NavLink>}
            {user && <NavLink to="/orders">Đơn hàng</NavLink>}
            {user && !isStaff && <NavLink to="/loyalty">Thành viên</NavLink>}
            {isStaff && <NavLink to="/admin">Quản trị</NavLink>}
          </nav>
          <div className="nav-right">
            {user ? (
              <>
                <NotificationBell />
                <NavLink to="/profile" className="user-chip">
                  {user.fullName}
                  <span className="muted tiny">{user.roles.map((r) => ROLE_LABEL[r] ?? r).join(', ')}</span>
                </NavLink>
                <button className="btn btn-ghost" onClick={() => { logout(); navigate('/'); }}>Đăng xuất</button>
              </>
            ) : (
              <>
                <NavLink to="/login" className="btn btn-ghost">Đăng nhập</NavLink>
                <NavLink to="/register" className="btn btn-primary">Đăng ký</NavLink>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="container main">
        <Outlet />
      </main>
      <footer className="footer">
        Real-time E-commerce Analytics Platform · Spring Boot · Apache Kafka · Kafka Streams · React
      </footer>
    </div>
  );
}

export function AdminLayout() {
  const { hasRole } = useAuth();
  const links: { to: string; label: string; roles: string[] }[] = [
    { to: '/admin', label: '📊 Dashboard', roles: ['ADMIN', 'ANALYST', 'PRODUCT_MANAGER', 'WAREHOUSE_MANAGER'] },
    { to: '/admin/orders', label: '🧾 Đơn hàng', roles: ['ADMIN', 'WAREHOUSE_MANAGER', 'ANALYST'] },
    { to: '/admin/shipments', label: '🚚 Vận chuyển', roles: ['ADMIN', 'WAREHOUSE_MANAGER', 'ANALYST'] },
    { to: '/admin/fraud', label: '🛡️ Rủi ro & gian lận', roles: ['ADMIN', 'ANALYST'] },
    { to: '/admin/products', label: '📦 Sản phẩm', roles: ['ADMIN', 'PRODUCT_MANAGER'] },
    { to: '/admin/reviews', label: '⭐ Đánh giá & hỏi đáp', roles: ['ADMIN', 'PRODUCT_MANAGER'] },
    { to: '/admin/promotions', label: '🎁 Khuyến mãi', roles: ['ADMIN', 'PRODUCT_MANAGER', 'ANALYST'] },
    { to: '/admin/inventory', label: '🏬 Kho hàng', roles: ['ADMIN', 'WAREHOUSE_MANAGER', 'PRODUCT_MANAGER', 'ANALYST'] },
    { to: '/admin/payments', label: '💳 Thanh toán', roles: ['ADMIN', 'ANALYST'] },
    { to: '/admin/reports', label: '📈 Báo cáo', roles: ['ADMIN', 'ANALYST'] },
    { to: '/admin/users', label: '👥 Tài khoản', roles: ['ADMIN'] },
    { to: '/admin/audit', label: '🔐 Nhật ký kiểm toán', roles: ['ADMIN'] },
    { to: '/admin/simulator', label: '🤖 Khách hàng ảo', roles: ['ADMIN'] },
  ];
  return (
    <div className="admin">
      <aside className="sidebar">
        {links.filter((l) => hasRole(...l.roles)).map((l) => (
          <NavLink key={l.to} to={l.to} end={l.to === '/admin'}>{l.label}</NavLink>
        ))}
        {hasRole('ADMIN', 'ANALYST') && (
          // Grafana chạy riêng (cổng 3001), đăng nhập bằng tài khoản Grafana
          <a href={GRAFANA_URL} target="_blank" rel="noreferrer">📡 Giám sát (Grafana) ↗</a>
        )}
      </aside>
      <section className="admin-content">
        <Outlet />
      </section>
    </div>
  );
}
