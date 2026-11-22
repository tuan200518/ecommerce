import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { InventoryItem, Page } from '../../api/types';
import { ErrorBox, Loading, Modal, Pagination } from '../../components/Common';
import { useAuth } from '../../auth/AuthContext';
import { dateTime } from '../../utils/format';

interface Tx {
  id: number;
  orderId?: number | null;
  type: string;
  quantity: number;
  note?: string;
  createdAt: string;
}

export default function InventoryAdminPage() {
  const { hasRole } = useAuth();
  const canEdit = hasRole('ADMIN', 'WAREHOUSE_MANAGER');
  const [lowStock, setLowStock] = useState(false);
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<InventoryItem> | null>(null);
  const [history, setHistory] = useState<{ item: InventoryItem; tx: Tx[] } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<InventoryItem>>('/api/inventory', { lowStock, page, size: 50 }).then(setData).catch((e) => setError(e.message));
  }, [lowStock, page]);

  useEffect(load, [load]);
  useEffect(() => {
    const t = window.setInterval(load, 5000);
    return () => window.clearInterval(t);
  }, [load]);

  const restock = (item: InventoryItem) => {
    const qty = prompt(`Nhập thêm bao nhiêu "${item.productName}"?`, '50');
    if (!qty) return;
    api.post(`/api/inventory/${item.productId}/restock`, { quantity: Number(qty), note: 'Nhập kho từ trang quản trị' })
      .then(load).catch((e) => setError(e.message));
  };

  const threshold = (item: InventoryItem) => {
    const v = prompt(`Ngưỡng cảnh báo tồn kho cho "${item.productName}"`, String(item.lowStockThreshold));
    if (v === null) return;
    api.put(`/api/inventory/${item.productId}/threshold`, { threshold: Number(v) }).then(load).catch((e) => setError(e.message));
  };

  const showHistory = (item: InventoryItem) =>
    api.get<Page<Tx>>(`/api/inventory/${item.productId}/transactions`, { size: 30 })
      .then((p) => setHistory({ item, tx: p.content })).catch((e) => setError(e.message));

  return (
    <div>
      <div className="row space-between">
        <h2>Quản lý kho</h2>
        <label className="radio"><input type="checkbox" checked={lowStock} onChange={(e) => { setLowStock(e.target.checked); setPage(0); }} /> Chỉ hiện sắp hết hàng</label>
      </div>
      <ErrorBox error={error} />
      <div className="card">
        {!data ? <Loading /> : (
          <table className="table">
            <thead><tr><th>ID</th><th>Sản phẩm</th><th>Khả dụng</th><th>Đang giữ</th><th>Ngưỡng</th><th>Cập nhật</th><th /></tr></thead>
            <tbody>
              {data.content.map((i) => (
                <tr key={i.productId}>
                  <td>{i.productId}</td>
                  <td>{i.productName} {i.lowStock && <span className="badge badge-pending">Sắp hết</span>}</td>
                  <td className={i.available === 0 ? 'text-bad' : i.lowStock ? 'text-warn' : ''}>{i.available}</td>
                  <td>{i.reserved}</td><td>{i.lowStockThreshold}</td><td className="small">{dateTime(i.updatedAt)}</td>
                  <td className="row">
                    {canEdit && <button className="btn btn-ghost" onClick={() => restock(i)}>Nhập kho</button>}
                    {canEdit && <button className="btn btn-ghost" onClick={() => threshold(i)}>Ngưỡng</button>}
                    <button className="btn btn-ghost" onClick={() => showHistory(i)}>Lịch sử</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      </div>
      {history && (
        <Modal title={`Biến động kho: ${history.item.productName}`} onClose={() => setHistory(null)}>
          <table className="table compact">
            <thead><tr><th>Thời gian</th><th>Loại</th><th>SL</th><th>Đơn</th><th>Ghi chú</th></tr></thead>
            <tbody>
              {history.tx.map((t) => (
                <tr key={t.id}><td className="small">{dateTime(t.createdAt)}</td><td>{t.type}</td><td>{t.quantity}</td><td>{t.orderId ? `#${t.orderId}` : ''}</td><td className="small">{t.note}</td></tr>
              ))}
            </tbody>
          </table>
        </Modal>
      )}
    </div>
  );
}
