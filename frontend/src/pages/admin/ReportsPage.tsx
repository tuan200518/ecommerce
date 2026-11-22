import { useEffect, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { api } from '../../api/client';
import EChart from '../../components/EChart';
import { ErrorBox } from '../../components/Common';
import { dateTime, money, number } from '../../utils/format';

interface SalesRow {
  windowStart: string;
  windowEnd: string;
  revenue: number;
  payments: number;
}

interface ProductRow {
  productId: number;
  productName: string;
  quantitySold: number;
  revenue: number;
}

interface AlertRow {
  id: string;
  type: string;
  severity: string;
  title: string;
  message: string;
  createdAt: string;
}

/** Báo cáo đọc từ bảng tổng hợp (sales_aggregates, product_statistics) do luồng Kafka Streams tạo ra. */
export default function ReportsPage() {
  const [range, setRange] = useState('1h');
  const [hours, setHours] = useState(24);
  const [sales, setSales] = useState<SalesRow[]>([]);
  const [products, setProducts] = useState<ProductRow[]>([]);
  const [alerts, setAlerts] = useState<AlertRow[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.get<SalesRow[]>('/api/analytics/reports/sales', { window: range, hours }).then(setSales).catch((e) => setError(e.message));
  }, [range, hours]);

  useEffect(() => {
    api.get<ProductRow[]>('/api/analytics/reports/products', { limit: 20 }).then(setProducts).catch(() => undefined);
    api.get<AlertRow[]>('/api/analytics/reports/alerts', { limit: 50 }).then(setAlerts).catch(() => undefined);
  }, []);

  const option = useMemo<EChartsOption>(() => ({
    grid: { left: 80, right: 20, top: 30, bottom: 40 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: sales.map((r) => dateTime(r.windowStart)) },
    yAxis: [{ type: 'value', name: 'Doanh thu' }, { type: 'value', name: 'Giao dịch', minInterval: 1 }],
    series: [
      { name: 'Doanh thu', type: 'bar', data: sales.map((r) => Number(r.revenue)), color: '#6366f1' },
      { name: 'Giao dịch', type: 'line', yAxisIndex: 1, data: sales.map((r) => Number(r.payments)), color: '#f59e0b' },
    ],
  }), [sales]);

  const total = sales.reduce((s, r) => s + Number(r.revenue), 0);

  return (
    <div>
      <h2>Báo cáo kinh doanh</h2>
      <ErrorBox error={error} />
      <div className="card">
        <div className="row">
          <label>Cửa sổ
            <select value={range} onChange={(e) => setRange(e.target.value)}>
              <option value="1m">1 phút</option>
              <option value="5m">5 phút</option>
              <option value="1h">1 giờ</option>
            </select>
          </label>
          <label>Khoảng thời gian
            <select value={hours} onChange={(e) => setHours(Number(e.target.value))}>
              <option value={1}>1 giờ qua</option>
              <option value={6}>6 giờ qua</option>
              <option value={24}>24 giờ qua</option>
              <option value={168}>7 ngày qua</option>
            </select>
          </label>
          <div className="kpi-inline">Tổng: <strong>{money(total)}</strong></div>
        </div>
        <EChart option={option} height={320} />
      </div>
      <div className="grid-2">
        <div className="card">
          <h3>Doanh số theo sản phẩm</h3>
          <table className="table compact">
            <thead><tr><th>Sản phẩm</th><th>Đã bán</th><th>Doanh thu</th></tr></thead>
            <tbody>
              {products.map((p) => (
                <tr key={p.productId}><td>{p.productName}</td><td>{number(p.quantitySold)}</td><td>{money(p.revenue)}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card">
          <h3>Lịch sử cảnh báo</h3>
          <table className="table compact">
            <thead><tr><th>Thời gian</th><th>Loại</th><th>Nội dung</th></tr></thead>
            <tbody>
              {alerts.map((a) => (
                <tr key={a.id}><td className="small">{dateTime(a.createdAt)}</td><td>{a.type}</td><td className="small"><strong>{a.title}</strong> — {a.message}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
