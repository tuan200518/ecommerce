import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import { AuthProvider } from './auth/AuthContext';
import { AdminLayout, ShopLayout } from './components/Layout';
import { RequireAuth } from './components/Guards';
import ProductListPage from './pages/shop/ProductListPage';
import ProductDetailPage from './pages/shop/ProductDetailPage';
import CartPage from './pages/shop/CartPage';
import CheckoutPage from './pages/shop/CheckoutPage';
import OrdersPage from './pages/shop/OrdersPage';
import OrderDetailPage from './pages/shop/OrderDetailPage';
import ProfilePage from './pages/shop/ProfilePage';
import LoyaltyPage from './pages/shop/LoyaltyPage';
import { LoginPage, RegisterPage } from './pages/shop/AuthPages';
import DashboardPage from './pages/admin/DashboardPage';
import ProductsAdminPage from './pages/admin/ProductsAdminPage';
import OrdersAdminPage from './pages/admin/OrdersAdminPage';
import InventoryAdminPage from './pages/admin/InventoryAdminPage';
import PaymentsAdminPage from './pages/admin/PaymentsAdminPage';
import UsersAdminPage from './pages/admin/UsersAdminPage';
import ReportsPage from './pages/admin/ReportsPage';
import SimulatorPage from './pages/admin/SimulatorPage';
import FraudAdminPage from './pages/admin/FraudAdminPage';
import ShipmentsAdminPage from './pages/admin/ShipmentsAdminPage';
import ReviewsAdminPage from './pages/admin/ReviewsAdminPage';
import PromotionsAdminPage from './pages/admin/PromotionsAdminPage';
import AuditPage from './pages/admin/AuditPage';
import DealsPage from './pages/shop/DealsPage';
import { STAFF_ROLES } from './auth/AuthContext';
import './styles.css';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<ShopLayout />}>
            <Route index element={<ProductListPage />} />
            <Route path="products/:id" element={<ProductDetailPage />} />
            <Route path="deals" element={<DealsPage />} />
            <Route path="login" element={<LoginPage />} />
            <Route path="register" element={<RegisterPage />} />
            <Route path="cart" element={<RequireAuth><CartPage /></RequireAuth>} />
            <Route path="checkout" element={<RequireAuth><CheckoutPage /></RequireAuth>} />
            <Route path="orders" element={<RequireAuth><OrdersPage /></RequireAuth>} />
            <Route path="orders/:id" element={<RequireAuth><OrderDetailPage /></RequireAuth>} />
            <Route path="profile" element={<RequireAuth><ProfilePage /></RequireAuth>} />
            <Route path="loyalty" element={<RequireAuth><LoyaltyPage /></RequireAuth>} />
            <Route path="admin" element={<RequireAuth roles={STAFF_ROLES}><AdminLayout /></RequireAuth>}>
              <Route index element={<DashboardPage />} />
              <Route path="orders" element={<OrdersAdminPage />} />
              <Route path="products" element={<ProductsAdminPage />} />
              <Route path="inventory" element={<InventoryAdminPage />} />
              <Route path="payments" element={<PaymentsAdminPage />} />
              <Route path="reports" element={<ReportsPage />} />
              <Route path="users" element={<UsersAdminPage />} />
              <Route path="fraud" element={<FraudAdminPage />} />
              <Route path="shipments" element={<ShipmentsAdminPage />} />
              <Route path="reviews" element={<ReviewsAdminPage />} />
              <Route path="promotions" element={<PromotionsAdminPage />} />
              <Route path="audit" element={<AuditPage />} />
              <Route path="simulator" element={<SimulatorPage />} />
            </Route>
            <Route path="*" element={<div className="empty">Không tìm thấy trang</div>} />
          </Route>
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  </React.StrictMode>,
);
