import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api/client';
import { Link } from 'react-router-dom';
import type { LoyaltyAccount, MyVoucher, Page, PointTransaction, RedeemResult, Reward, Tier } from '../../api/types';
import { ErrorBox, Loading, Pagination } from '../../components/Common';
import { dateTime, money, number, POINT_TYPE_LABEL, voucherValueText } from '../../utils/format';
import { VoucherCard } from '../../components/Promotions';

/** Trang thành viên (Loyalty Service): điểm, hạng, tiến độ lên hạng, mã giới thiệu và lịch sử điểm. */
export default function LoyaltyPage() {
  const [account, setAccount] = useState<LoyaltyAccount | null>(null);
  const [tiers, setTiers] = useState<Tier[]>([]);
  const [history, setHistory] = useState<Page<PointTransaction> | null>(null);
  const [page, setPage] = useState(0);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [rewards, setRewards] = useState<Reward[]>([]);
  const [myVouchers, setMyVouchers] = useState<MyVoucher[]>([]);
  const [redeeming, setRedeeming] = useState<string | null>(null);
  const [redeemMsg, setRedeemMsg] = useState<string | null>(null);
  const [redeemError, setRedeemError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<LoyaltyAccount>('/api/loyalty/me').then(setAccount).catch((e) => setError(e.message));
    api.get<Page<PointTransaction>>('/api/loyalty/me/transactions', { page, size: 10 }).then(setHistory).catch(() => undefined);
    api.get<MyVoucher[]>('/api/promotions/vouchers/mine').then(setMyVouchers).catch(() => undefined);
  }, [page]);

  const redeem = (rewardId: string) => {
    setRedeeming(rewardId);
    setRedeemMsg(null);
    setRedeemError(null);
    api.post<RedeemResult>(`/api/loyalty/rewards/${rewardId}/redeem`)
      .then((r) => {
        setRedeemMsg(r.message);
        load();
        // Voucher do Promotion Service phát bất đồng bộ (loyalty-events → promotion-service)
        window.setTimeout(load, 3000);
      })
      .catch((e) => setRedeemError(e.message))
      .finally(() => setRedeeming(null));
  };

  useEffect(load, [load]);
  useEffect(() => {
    api.get<Tier[]>('/api/loyalty/tiers').then(setTiers).catch(() => undefined);
    api.get<Reward[]>('/api/loyalty/rewards').then(setRewards).catch(() => undefined);
    window.addEventListener('shop:notification', load);
    return () => window.removeEventListener('shop:notification', load);
  }, [load]);

  if (!account) return error ? <ErrorBox error={error} /> : <Loading />;

  const link = `${window.location.origin}/register?ref=${account.referralCode}`;
  const copy = () => {
    navigator.clipboard?.writeText(link).then(() => {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    }).catch(() => undefined);
  };
  const next = account.nextTier;
  const progress = next
    ? Math.min(100, Math.round((Number(account.spend12Months) * 100) / Number(next.minSpend)))
    : 100;

  return (
    <div>
      <h2>Thành viên</h2>
      <div className="card tier-card">
        <div>
          <div className="muted small">Điểm hiện có</div>
          <div className="points">{number(account.balance)}</div>
          <div className="muted tiny">Đã tích lũy tổng cộng {number(account.lifetimeEarned)} điểm</div>
        </div>
        <div>
          <div className="muted small">Hạng thành viên</div>
          <div className="tier-name">{account.tier.name}</div>
          <div className="muted tiny">Hệ số điểm ×{account.tier.multiplier}</div>
        </div>
        <div>
          <div className="muted small">Chi tiêu 12 tháng</div>
          <div className="tier-name">{money(account.spend12Months)}</div>
          {next ? (
            <>
              <div className="bar primary" style={{ margin: '6px 0' }}><span style={{ width: `${progress}%` }} /></div>
              <div className="muted tiny">Chi thêm {money(account.amountToNextTier)} để lên hạng {next.name}</div>
            </>
          ) : <div className="muted tiny">Bạn đang ở hạng cao nhất 🎉</div>}
        </div>
      </div>

      <div className="grid-2">
        <div className="card">
          <h3>Giới thiệu bạn bè</h3>
          <p className="small">Bạn nhận <strong>500 điểm</strong> khi người được giới thiệu hoàn thành đơn hàng đầu tiên.</p>
          <div className="row">
            <span className="referral-code">{account.referralCode}</span>
            <button className="btn" onClick={copy}>{copied ? 'Đã sao chép ✓' : 'Sao chép link mời'}</button>
          </div>
          <p className="muted tiny">{link}</p>
          <p className="small">Đã giới thiệu thành công: <strong>{account.referralCount}</strong> người</p>
        </div>
        <div className="card">
          <h3>Cách tích điểm</h3>
          <ul className="small">
            <li>1 điểm cho mỗi 10.000 ₫ của đơn hàng hợp lệ, nhân hệ số hạng.</li>
            <li>Nhiệm vụ đơn hàng đầu tiên: +200 điểm {account.firstOrderCompleted && <span className="text-good">✓ đã hoàn thành</span>}</li>
            <li>Viết đánh giá sản phẩm đã nhận: +50 điểm (tối đa 5 lần/ngày).</li>
            <li>Đơn bị hủy/hoàn tiền: điểm của đơn đó bị trừ lại.</li>
          </ul>
          <table className="table compact">
            <thead><tr><th>Hạng</th><th>Chi tiêu 12 tháng</th><th>Hệ số</th></tr></thead>
            <tbody>
              {tiers.map((t) => (
                <tr key={t.code} className={t.code === account.tier.code ? 'text-good' : ''}>
                  <td>{t.name}</td><td>từ {money(t.minSpend)}</td><td>×{t.multiplier}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="grid-2">
        <div className="card">
          <h3>🎁 Đổi điểm lấy voucher</h3>
          {redeemMsg && <div className="alert alert-success">{redeemMsg}</div>}
          <ErrorBox error={redeemError} />
          {rewards.length === 0 ? <div className="empty">Chưa có phần thưởng</div> : rewards.map((r) => {
            const affordable = account.balance >= r.points;
            return (
              <div key={r.id} className="line reward">
                <span>
                  <strong>{r.name}</strong>
                  <div className="muted tiny">{voucherValueText({ type: r.voucherType, value: r.value, maxDiscount: r.maxDiscount })} · hạn dùng {r.validDays} ngày</div>
                </span>
                <button className={`btn ${affordable ? 'btn-primary' : ''}`} disabled={!affordable || redeeming !== null}
                        onClick={() => redeem(r.id)} title={affordable ? '' : `Cần ${number(r.points)} điểm`}>
                  {redeeming === r.id ? 'Đang đổi…' : `${number(r.points)} điểm`}
                </button>
              </div>
            );
          })}
          <p className="muted tiny">Điểm đã đổi không được hoàn lại; voucher dùng một lần khi thanh toán.</p>
        </div>
        <div className="card">
          <h3>🎟️ Voucher của tôi</h3>
          {myVouchers.length === 0 ? <div className="empty">Bạn chưa có voucher cá nhân. <Link to="/deals">Xem mã công khai</Link></div> : (
            <div className="voucher-list">
              {myVouchers.map((v) => <VoucherCard key={v.code} v={v} status={v.status} />)}
            </div>
          )}
        </div>
      </div>

      <div className="card">
        <h3>Lịch sử điểm</h3>
        {!history ? <Loading /> : history.content.length === 0 ? <div className="empty">Chưa có giao dịch điểm</div> : (
          <table className="table">
            <thead><tr><th>Thời gian</th><th>Loại</th><th>Nội dung</th><th>Điểm</th></tr></thead>
            <tbody>
              {history.content.map((t) => (
                <tr key={t.id}>
                  <td className="small">{dateTime(t.createdAt)}</td>
                  <td className="small">{POINT_TYPE_LABEL[t.type] ?? t.type}</td>
                  <td className="small">{t.description}{t.reversed && <span className="muted"> (đã bị trừ lại)</span>}</td>
                  <td className={t.points >= 0 ? 'points-plus' : 'points-minus'}>{t.points >= 0 ? '+' : ''}{number(t.points)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {history && <Pagination page={history.page} totalPages={history.totalPages} onChange={setPage} />}
      </div>
    </div>
  );
}
