import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import type { EChartsOption } from 'echarts';
import { api } from '../../api/client';
import type { RecommendationAdminStats, RecommendationUserProfile } from '../../api/types';
import { ErrorBox, Loading } from '../../components/Common';
import EChart from '../../components/EChart';
import { dateTime, money, number } from '../../utils/format';

const KIND_LABEL: Record<string, string> = { VIEW: 'đã xem', CART: 'đã thêm vào giỏ', BUY: 'đã mua' };
const STRATEGY_LABEL: Record<string, string> = {
  PERSONALIZED: 'Cá nhân hóa', TRENDING: 'Thịnh hành (chưa đủ dữ liệu về khách)', HYBRID: 'Lai', CO_PURCHASE: 'Mua cùng',
};

function Kpi({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="kpi card">
      <div className="muted small">{label}</div>
      <div className="kpi-value">{value}</div>
      {hint && <div className="muted tiny">{hint}</div>}
    </div>
  );
}

/** Hiệu quả của bộ gợi ý: CTR và chuyển đổi theo vị trí, tình trạng mô hình, giải thích gợi ý cho một khách. */
export default function RecommendationsAdminPage() {
  const [days, setDays] = useState(7);
  const [stats, setStats] = useState<RecommendationAdminStats | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [userId, setUserId] = useState('');
  const [profile, setProfile] = useState<RecommendationUserProfile | null>(null);
  const [profileError, setProfileError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<RecommendationAdminStats>('/api/recommendations/admin/stats', { days })
      .then((s) => { setStats(s); setError(null); })
      .catch((e) => setError(e.message));
  }, [days]);
  useEffect(() => {
    load();
    const t = window.setInterval(load, 15_000);
    return () => window.clearInterval(t);
  }, [load]);

  const explain = (e: FormEvent) => {
    e.preventDefault();
    setProfile(null);
    setProfileError(null);
    if (!userId.trim()) return;
    api.get<RecommendationUserProfile>(`/api/recommendations/admin/users/${userId.trim()}`, { limit: 8 })
      .then(setProfile).catch((err) => setProfileError(err.message));
  };

  const totals = useMemo(() => {
    const p = stats?.placements ?? [];
    const impressions = p.reduce((a, x) => a + x.impressions, 0);
    const clicks = p.reduce((a, x) => a + x.clicks, 0);
    const conversions = p.reduce((a, x) => a + x.conversions, 0);
    const revenue = p.reduce((a, x) => a + x.revenue, 0);
    return { impressions, clicks, conversions, revenue, ctr: impressions ? (100 * clicks) / impressions : 0 };
  }, [stats]);

  const option = useMemo<EChartsOption>(() => ({
    tooltip: { trigger: 'axis' },
    legend: { data: ['Lượt hiển thị', 'Lượt bấm', 'Đơn từ gợi ý'] },
    grid: { left: 50, right: 50, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: (stats?.daily ?? []).map((d) => d.day.slice(5)) },
    yAxis: [{ type: 'value', name: 'Hiển thị' }, { type: 'value', name: 'Bấm / đơn' }],
    series: [
      { name: 'Lượt hiển thị', type: 'bar', data: (stats?.daily ?? []).map((d) => d.impressions), itemStyle: { opacity: 0.35 } },
      { name: 'Lượt bấm', type: 'line', yAxisIndex: 1, smooth: true, data: (stats?.daily ?? []).map((d) => d.clicks) },
      { name: 'Đơn từ gợi ý', type: 'line', yAxisIndex: 1, smooth: true, data: (stats?.daily ?? []).map((d) => d.conversions) },
    ],
  }), [stats]);

  const maxCtr = Math.max(1, ...(stats?.placements ?? []).map((p) => p.ctr));

  return (
    <div>
      <div className="row">
        <h2>🧠 Gợi ý sản phẩm (AI Recommendation)</h2>
        <select value={days} onChange={(e) => setDays(Number(e.target.value))} style={{ marginLeft: 'auto', width: 'auto' }}>
          <option value={1}>Hôm nay</option>
          <option value={7}>7 ngày</option>
          <option value={30}>30 ngày</option>
        </select>
      </div>
      <p className="muted small">
        Bộ gợi ý lai học trực tuyến từ luồng sự kiện: lọc cộng tác item-item (cùng mua / cùng xem), nội dung (danh mục, giá, tên),
        hồ sơ sở thích có suy giảm theo thời gian và độ thịnh hành. Đơn hàng được tính cho gợi ý khi khách mua sản phẩm đã bấm trong 24 giờ.
      </p>
      <ErrorBox error={error} />
      {!stats ? <Loading /> : (
        <>
          <div className="kpis">
            <Kpi label="Lượt hiển thị" value={number(totals.impressions)} hint={`${stats.days} ngày gần nhất`} />
            <Kpi label="Lượt bấm" value={number(totals.clicks)} hint={`CTR ${totals.ctr.toFixed(2)}%`} />
            <Kpi label="Sản phẩm bán nhờ gợi ý" value={number(totals.conversions)}
                 hint={totals.clicks ? `${((100 * totals.conversions) / totals.clicks).toFixed(1)}% lượt bấm` : undefined} />
            <Kpi label="Doanh thu từ gợi ý" value={money(totals.revenue)} />
            <Kpi label="Khách có hồ sơ sở thích" value={number(stats.model.profiles)} hint={`${number(stats.model.interactions)} hành vi`} />
            <Kpi label="Cặp sản phẩm đã học" value={number(stats.model.boughtPairs + stats.model.viewedPairs)}
                 hint={`${number(stats.model.boughtPairs)} mua cùng · ${number(stats.model.viewedPairs)} cùng xem`} />
          </div>

          <div className="card">
            <h3>Hiệu quả theo vị trí</h3>
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>Vị trí</th><th>Lượt gọi</th><th>Hiển thị</th><th>Bấm</th><th>CTR</th><th>Đơn</th><th>Tỷ lệ chuyển đổi</th><th>Doanh thu</th></tr></thead>
                <tbody>
                  {stats.placements.map((p) => (
                    <tr key={p.placement}>
                      <td><strong>{p.label}</strong><div className="muted tiny">{p.placement}</div></td>
                      <td>{number(p.requests)}</td>
                      <td>{number(p.impressions)}</td>
                      <td>{number(p.clicks)}</td>
                      <td>
                        <div className="row" style={{ gap: 6 }}>
                          <div className="rec-bar"><span style={{ width: `${(100 * p.ctr) / maxCtr}%` }} /></div>
                          <span className="small">{p.ctr.toFixed(2)}%</span>
                        </div>
                      </td>
                      <td>{number(p.conversions)}</td>
                      <td>{p.conversionRate.toFixed(1)}%</td>
                      <td>{money(p.revenue)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {stats.daily.length > 0 && <EChart option={option} height={260} />}
          </div>

          <div className="grid-2">
            <div className="card">
              <h3>Thường được mua cùng nhất</h3>
              {stats.topPairs.length === 0 ? <div className="empty">Chưa có đơn hàng nhiều sản phẩm</div> : (
                <table className="table">
                  <thead><tr><th>Sản phẩm</th><th>Mua cùng</th><th>Số đơn</th></tr></thead>
                  <tbody>
                    {stats.topPairs.map((p) => (
                      <tr key={`${p.productId}-${p.relatedId}`}>
                        <td className="small"><Link to={`/products/${p.productId}`}>{p.productName}</Link></td>
                        <td className="small"><Link to={`/products/${p.relatedId}`}>{p.relatedName}</Link></td>
                        <td>{number(p.orders)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <p className="muted tiny">
                Mô hình: {number(stats.model.activeProducts)}/{number(stats.model.products)} sản phẩm đang bán · học lần cuối {dateTime(stats.model.lastLearnedAt)}
              </p>
            </div>

            <div className="card">
              <h3>Vì sao khách nhận được gợi ý này?</h3>
              <form className="row" onSubmit={explain}>
                <input placeholder="Mã khách hàng (userId)" value={userId} onChange={(e) => setUserId(e.target.value)} inputMode="numeric" />
                <button className="btn btn-primary">Xem hồ sơ</button>
              </form>
              <ErrorBox error={profileError} />
              {profile && (
                <div className="small" style={{ marginTop: 10 }}>
                  <div><strong>Chiến lược:</strong> {STRATEGY_LABEL[profile.forYou.strategy] ?? profile.forYou.strategy}</div>
                  {profile.seeds.length > 0 && (
                    <div><strong>Sản phẩm đã tương tác:</strong>{' '}
                      {profile.seeds.map((s) => `${s.name} (${KIND_LABEL[s.kind] ?? s.kind}, ${s.weight})`).join(' · ')}</div>
                  )}
                  {profile.categories.length > 0 && (
                    <div><strong>Danh mục quan tâm:</strong> {profile.categories.map((c) => `${c.categoryName} (${c.weight})`).join(' · ')}</div>
                  )}
                  {profile.searches.length > 0 && <div><strong>Đã tìm:</strong> {profile.searches.map((q) => `“${q}”`).join(', ')}</div>}
                  <ol style={{ paddingLeft: 18 }}>
                    {profile.forYou.items.map((i) => (
                      <li key={i.productId}>
                        <Link to={`/products/${i.productId}`}>{i.name}</Link> <span className="muted">– {i.reason} (điểm {i.score})</span>
                      </li>
                    ))}
                  </ol>
                </div>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
