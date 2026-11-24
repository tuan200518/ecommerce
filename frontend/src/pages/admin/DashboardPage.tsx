import { useEffect, useMemo, useState } from 'react';
import type { EChartsOption } from 'echarts';
import { api } from '../../api/client';
import type { Alert, Point, Snapshot } from '../../api/types';
import EChart from '../../components/EChart';
import { ErrorBox, Loading } from '../../components/Common';
import { useEventSource } from '../../hooks/useEventSource';
import { dateTime, money, number, ORDER_STATUS_LABEL, timeOnly } from '../../utils/format';

type RevenueWindow = '1m' | '5m' | '1h';

const STATUS_COLORS: Record<string, string> = {
  PENDING: '#f59e0b',
  AWAITING_PAYMENT: '#3b82f6',
  CONFIRMED: '#10b981',
  SHIPPING: '#8b5cf6',
  DELIVERED: '#059669',
  CANCELLED: '#ef4444',
};

export default function DashboardPage() {
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [range, setRange] = useState<RevenueWindow>('1m');
  const [flash, setFlash] = useState<Alert | null>(null);

  useEffect(() => {
    api.get<Snapshot>('/api/analytics/snapshot').then(setSnapshot).catch((e) => setError(e.message));
  }, []);

  const status = useEventSource('/api/analytics/stream', {
    snapshot: (s: Snapshot) => setSnapshot(s),
    alert: (a: Alert) => {
      setFlash(a);
      setTimeout(() => setFlash(null), 6000);
    },
  });

  const revenueSeries: Point[] = !snapshot ? [] : range === '1m' ? snapshot.revenuePerMinute
    : range === '5m' ? snapshot.revenuePer5Minutes : snapshot.revenuePerHour;

  const revenueOption = useMemo<EChartsOption>(() => ({
    grid: { left: 70, right: 20, top: 30, bottom: 30 },
    tooltip: { trigger: 'axis', valueFormatter: (v) => money(Number(v)) },
    xAxis: { type: 'category', data: revenueSeries.map((p) => timeOnly(p.t)) },
    yAxis: { type: 'value', axisLabel: { formatter: (v: number) => (v >= 1e6 ? `${(v / 1e6).toFixed(1)}tr` : number(v)) } },
    series: [{ name: 'Doanh thu', type: 'bar', data: revenueSeries.map((p) => p.value), itemStyle: { color: '#6366f1', borderRadius: [4, 4, 0, 0] } }],
  }), [revenueSeries]);

  const ordersOption = useMemo<EChartsOption>(() => {
    const orders = snapshot?.ordersPerMinute ?? [];
    const failures = snapshot?.paymentFailuresPerMinute ?? [];
    const times = Array.from(new Set([...orders, ...failures].map((p) => p.t))).sort((a, b) => a - b);
    const byT = (list: Point[]) => new Map(list.map((p) => [p.t, p.value]));
    const o = byT(orders);
    const f = byT(failures);
    return {
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      tooltip: { trigger: 'axis' },
      legend: { top: 0 },
      xAxis: { type: 'category', data: times.map(timeOnly) },
      yAxis: { type: 'value', minInterval: 1 },
      series: [
        { name: 'Đơn mới / phút', type: 'line', smooth: true, areaStyle: { opacity: 0.15 }, data: times.map((t) => o.get(t) ?? 0), color: '#0ea5e9' },
        { name: 'Thanh toán lỗi / phút', type: 'line', smooth: true, data: times.map((t) => f.get(t) ?? 0), color: '#ef4444' },
      ],
    };
  }, [snapshot]);

  const statusOption = useMemo<EChartsOption>(() => ({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie', radius: ['45%', '72%'], center: ['50%', '45%'],
      label: { formatter: '{c}' },
      data: Object.entries(snapshot?.ordersByStatus ?? {}).filter(([, v]) => v > 0).map(([k, v]) => ({
        name: ORDER_STATUS_LABEL[k] ?? k, value: v, itemStyle: { color: STATUS_COLORS[k] },
      })),
    }],
  }), [snapshot]);

  const productsOption = useMemo<EChartsOption>(() => {
    const top = [...(snapshot?.topProducts ?? [])].reverse();
    return {
      grid: { left: 160, right: 30, top: 10, bottom: 20 },
      tooltip: { trigger: 'axis', valueFormatter: (v) => money(Number(v)) },
      xAxis: { type: 'value', axisLabel: { formatter: (v: number) => (v >= 1e6 ? `${(v / 1e6).toFixed(0)}tr` : number(v)) } },
      yAxis: { type: 'category', data: top.map((p) => (p.name ?? `#${p.productId}`).slice(0, 24)) },
      series: [{ type: 'bar', data: top.map((p) => p.revenue), itemStyle: { color: '#10b981', borderRadius: [0, 4, 4, 0] },
        label: { show: true, position: 'right', formatter: (p: any) => `${top[p.dataIndex]?.quantity ?? ''} sp` } }],
    };
  }, [snapshot]);

  const funnelOption = useMemo<EChartsOption>(() => {
    const c = snapshot?.counters ?? {};
    return {
      tooltip: { trigger: 'item' },
      series: [{
        type: 'funnel', left: '10%', width: '80%', sort: 'descending', label: { formatter: '{b}: {c}' },
        data: [
          { name: 'Xem sản phẩm', value: c.product_views ?? 0 },
          { name: 'Thêm vào giỏ', value: c.cart_adds ?? 0 },
          { name: 'Tạo đơn', value: c.orders_created ?? 0 },
          { name: 'Thanh toán OK', value: c.payments_completed ?? 0 },
          { name: 'Xác nhận', value: c.orders_confirmed ?? 0 },
        ],
      }],
    };
  }, [snapshot]);

  if (!snapshot) return error ? <ErrorBox error={error} /> : <Loading />;
  const k = snapshot.kpis;

  return (
    <div className="dashboard">
      <div className="row space-between">
        <h2>Dashboard thời gian thực</h2>
        <span className={`live ${status}`}>
          <span className={`dot dot-${status}`} /> {status === 'open' ? 'LIVE' : 'Đang kết nối…'} · cập nhật {timeOnly(snapshot.generatedAt)}
        </span>
      </div>
      {flash && <div className={`alert ${flash.severity === 'CRITICAL' ? 'alert-error' : 'alert-warning'}`}>🚨 <strong>{flash.title}</strong> — {flash.message}</div>}

      <div className="kpis">
        <Kpi label="Doanh thu (thuần)" value={money(k.revenue)} hint={`${number(k.paymentsCompleted)} giao dịch`} />
        <Kpi label="Tổng đơn hàng" value={number(k.totalOrders)} hint={`${number(k.confirmedOrders)} xác nhận · ${number(k.cancelledOrders)} hủy`} />
        <Kpi label="Khách đang hoạt động (5′)" value={number(k.activeUsers)} />
        <Kpi label="Tỷ lệ thanh toán thành công" value={`${k.paymentSuccessRate}%`} hint={`${number(k.paymentsFailed)} thất bại`} tone={k.paymentSuccessRate < 80 && k.paymentsFailed > 0 ? 'bad' : 'good'} />
        <Kpi label="Giá trị đơn trung bình" value={money(k.averageOrderValue)} />
        <Kpi label="Sản phẩm sắp hết" value={number(k.lowStockProducts)} tone={k.lowStockProducts > 0 ? 'bad' : 'good'} />
        <Kpi label="Tỷ lệ xem → đặt hàng" value={`${k.viewToOrderRate ?? 0}%`} hint={`${number(k.productViews)} lượt xem sản phẩm`} />
        <Kpi label="Đơn bị gắn cờ rủi ro" value={number(k.flaggedOrders)} hint="chờ / đã xác minh thủ công" tone={k.flaggedOrders > 0 ? 'bad' : undefined} />
        <Kpi label="Giao thành công" value={number(k.shipmentsDelivered)} hint={`${number(k.reviews)} đánh giá sản phẩm`} />
      </div>

      <div className="grid-2">
        <div className="card">
          <div className="row space-between">
            <h3>Doanh thu theo cửa sổ thời gian (Kafka Streams)</h3>
            <div className="tabs">
              {(['1m', '5m', '1h'] as RevenueWindow[]).map((w) => (
                <button key={w} className={`tab ${range === w ? 'active' : ''}`} onClick={() => setRange(w)}>
                  {w === '1m' ? '1 phút' : w === '5m' ? '5 phút' : '1 giờ'}
                </button>
              ))}
            </div>
          </div>
          <EChart option={revenueOption} />
        </div>
        <div className="card">
          <h3>Đơn hàng & thanh toán lỗi theo phút</h3>
          <EChart option={ordersOption} />
        </div>
        <div className="card">
          <h3>Đơn theo trạng thái</h3>
          <EChart option={statusOption} />
        </div>
        <div className="card">
          <h3>Top sản phẩm bán chạy (doanh thu)</h3>
          {snapshot.topProducts.length === 0 ? <div className="empty">Chưa có đơn được xác nhận</div> : <EChart option={productsOption} />}
        </div>
        <div className="card">
          <h3>Phễu hành vi mua hàng</h3>
          <EChart option={funnelOption} />
          <div className="muted small">Tỷ lệ xem → đơn: {k.viewToOrderRate ?? 0}% · giỏ → đơn: {k.cartToOrderRate}%</div>
        </div>
        <div className="card">
          <h3>Cảnh báo vận hành</h3>
          <div className="alerts-feed">
            {snapshot.alerts.length === 0 && <div className="empty">Chưa có cảnh báo</div>}
            {snapshot.alerts.map((a) => (
              <div key={a.id} className={`feed-item sev-${a.severity.toLowerCase()}`}>
                <strong>{a.title}</strong>
                <div className="small">{a.message}</div>
                <div className="muted tiny">{dateTime(a.at)}</div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="grid-2">
        <div className="card">
          <h3>Sản phẩm sắp hết hàng</h3>
          {snapshot.lowStock.length === 0 ? <div className="empty">Tồn kho ổn định</div> : (
            <table className="table">
              <thead><tr><th>Sản phẩm</th><th>Khả dụng</th><th>Đang giữ</th><th>Ngưỡng</th></tr></thead>
              <tbody>
                {snapshot.lowStock.map((s) => (
                  <tr key={s.productId}>
                    <td>{s.name}</td>
                    <td className={s.available === 0 ? 'text-bad' : 'text-warn'}>{s.available}</td>
                    <td>{s.reserved}</td>
                    <td>{s.threshold}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="card">
          <h3>Hiệu suất Kafka</h3>
          {!snapshot.kafka ? <div className="empty">Đang thu thập…</div> : snapshot.kafka.error ? (
            <div className="alert alert-error">{snapshot.kafka.error}</div>
          ) : (
            <>
              <div className="muted small">Broker: {snapshot.kafka.brokers} · lấy mẫu {timeOnly(snapshot.kafka.sampledAt)}</div>
              <table className="table compact">
                <thead><tr><th>Topic</th><th>Partition</th><th>Messages</th><th>msg/s</th></tr></thead>
                <tbody>
                  {snapshot.kafka.topics.map((t) => (
                    <tr key={t.topic}><td>{t.topic}</td><td>{t.partitions}</td><td>{number(t.messages)}</td><td>{t.messagesPerSecond}</td></tr>
                  ))}
                </tbody>
              </table>
              <table className="table compact">
                <thead><tr><th>Consumer group</th><th>Trạng thái</th><th>Thành viên</th><th>Lag</th></tr></thead>
                <tbody>
                  {snapshot.kafka.consumerGroups.map((g) => (
                    <tr key={g.groupId}>
                      <td>{g.groupId}</td><td className="small">{g.state}</td><td>{g.members}</td>
                      <td className={g.lag > 100 ? 'text-bad' : g.lag > 0 ? 'text-warn' : ''}>{number(g.lag)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function Kpi({ label, value, hint, tone }: { label: string; value: string; hint?: string; tone?: 'good' | 'bad' }) {
  return (
    <div className={`kpi card ${tone ?? ''}`}>
      <div className="muted small">{label}</div>
      <div className="kpi-value">{value}</div>
      {hint && <div className="muted tiny">{hint}</div>}
    </div>
  );
}
