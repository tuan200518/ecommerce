import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../../api/client';
import type { Page, Question, Review } from '../../api/types';
import { ErrorBox, Loading, Pagination } from '../../components/Common';
import { dateTime, stars } from '../../utils/format';

/** Kiểm duyệt đánh giá + trả lời câu hỏi chưa có phản hồi (Review Service). */
export default function ReviewsAdminPage() {
  const [tab, setTab] = useState<'reviews' | 'questions'>('questions');
  const [visible, setVisible] = useState<string>('');
  const [page, setPage] = useState(0);
  const [reviews, setReviews] = useState<Page<Review> | null>(null);
  const [questions, setQuestions] = useState<Page<Question> | null>(null);
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    if (tab === 'reviews') {
      api.get<Page<Review>>('/api/reviews/admin/reviews', { visible: visible || undefined, page, size: 20 })
        .then(setReviews).catch((e) => setError(e.message));
    } else {
      api.get<Page<Question>>('/api/reviews/admin/questions', { answered: false, page, size: 20 })
        .then(setQuestions).catch((e) => setError(e.message));
    }
  }, [tab, visible, page]);

  useEffect(load, [load]);

  const toggle = (r: Review) => {
    const note = r.visible ? prompt('Lý do ẩn đánh giá (vd. spam, ngôn từ không phù hợp):') : null;
    if (r.visible && note === null) return;
    api.put(`/api/reviews/admin/reviews/${r.id}/visibility`, { visible: !r.visible, note })
      .then(load).catch((e) => setError(e.message));
  };

  const answer = (q: Question) => {
    api.post(`/api/reviews/questions/${q.id}/answers`, { content: answers[q.id] ?? '' })
      .then(() => { setAnswers({ ...answers, [q.id]: '' }); load(); })
      .catch((e) => setError(e.message));
  };

  return (
    <div>
      <h2>Đánh giá & hỏi đáp</h2>
      <ErrorBox error={error} />
      <div className="tabs">
        <button className={`tab ${tab === 'questions' ? 'active' : ''}`} onClick={() => { setTab('questions'); setPage(0); }}>Câu hỏi chờ trả lời</button>
        <button className={`tab ${tab === 'reviews' ? 'active' : ''}`} onClick={() => { setTab('reviews'); setPage(0); }}>Kiểm duyệt đánh giá</button>
      </div>

      {tab === 'questions' && (
        <div className="card">
          {!questions ? <Loading /> : questions.content.length === 0 ? <div className="empty">Không còn câu hỏi chờ trả lời 🎉</div> :
            questions.content.map((q) => (
              <div key={q.id} className="qa-item">
                <div><Link to={`/products/${q.productId}`}>Sản phẩm #{q.productId}</Link> · <span className="muted tiny">{q.authorName}, {dateTime(q.createdAt)}</span></div>
                <div><strong>Hỏi:</strong> {q.content}</div>
                {q.answers.map((a) => <div key={a.id} className="qa-answer"><strong>{a.authorName}:</strong> {a.content}</div>)}
                <div className="row" style={{ marginTop: 6 }}>
                  <input style={{ flex: 1 }} value={answers[q.id] ?? ''} placeholder="Nhập câu trả lời…" aria-label="Câu trả lời"
                         onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} />
                  <button className="btn btn-primary" disabled={!answers[q.id]?.trim()} onClick={() => answer(q)}>Trả lời</button>
                </div>
              </div>
            ))}
          {questions && <Pagination page={questions.page} totalPages={questions.totalPages} onChange={setPage} />}
        </div>
      )}

      {tab === 'reviews' && (
        <div className="card">
          <div className="tabs">
            {[['', 'Tất cả'], ['true', 'Đang hiển thị'], ['false', 'Đã ẩn']].map(([v, label]) => (
              <button key={v} className={`tab ${visible === v ? 'active' : ''}`} onClick={() => { setVisible(v); setPage(0); }}>{label}</button>
            ))}
          </div>
          {!reviews ? <Loading /> : reviews.content.length === 0 ? <div className="empty">Chưa có đánh giá</div> : (
            <table className="table">
              <thead><tr><th>Sản phẩm</th><th>Khách</th><th>Sao</th><th>Nội dung</th><th>Thời gian</th><th /></tr></thead>
              <tbody>
                {reviews.content.map((r) => (
                  <tr key={r.id} style={{ opacity: r.visible ? 1 : 0.6 }}>
                    <td><Link to={`/products/${r.productId}`}>{r.productName ?? `#${r.productId}`}</Link></td>
                    <td className="small">{r.authorName}</td>
                    <td className="stars">{stars(r.rating)}</td>
                    <td className="small">{r.comment}{!r.visible && r.moderationNote && <div className="muted">Đã ẩn: {r.moderationNote}</div>}</td>
                    <td className="small">{dateTime(r.createdAt)}</td>
                    <td><button className={`btn ${r.visible ? 'btn-danger' : 'btn-ghost'}`} onClick={() => toggle(r)}>{r.visible ? 'Ẩn' : 'Hiện lại'}</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {reviews && <Pagination page={reviews.page} totalPages={reviews.totalPages} onChange={setPage} />}
        </div>
      )}
    </div>
  );
}
