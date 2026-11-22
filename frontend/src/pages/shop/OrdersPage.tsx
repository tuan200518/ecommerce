import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { Order, Page } from '../../api/types';
import { Empty, ErrorBox, Loading, Pagination, StatusBadge } from '../../components/Common';
import { dateTime, money } from '../../utils/format';

export default function OrdersPage() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<Order> | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<Order>>('/api/orders/my', { page, size: 10 }).then(setData).catch((e) => setError(e.message));
  }, [page]);

  useEffect(load, [load]);

  // Trạng thái đơn thay đổi (qua thông báo realtime) → tải lại danh sách
  useEffect(() => {
    window.addEventListener('shop:notification', load);
    return () => window.removeEventListener('shop:notification', load);
  }, [load]);

  if (!data) return error ? <ErrorBox error={error} /> : <Loading />;

  return (
    <div className="card">
      <h2>Đơn hàng của tôi</h2>
      {data.content.length === 0 ? (
        <Empty>Bạn chưa có đơn hàng nào. <Link to="/">Mua sắm ngay</Link></Empty>
      ) : (
        <table className="table">
          <thead><tr><th>Mã đơn</th><th>Ngày đặt</th><th>Sản phẩm</th><th>Tổng tiền</th><th>Trạng thái</th></tr></thead>
          <tbody>
            {data.content.map((o) => (
              <tr key={o.id}>
                <td><Link to={`/orders/${o.id}`}>#{o.id}</Link></td>
                <td>{dateTime(o.createdAt)}</td>
                <td className="small">{o.items.map((i) => `${i.productName} × ${i.quantity}`).join(', ')}</td>
                <td>{money(o.totalAmount)}</td>
                <td><StatusBadge status={o.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
    </div>
  );
}
