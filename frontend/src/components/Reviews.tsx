import { FormEvent, useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { Page, Question, Review, ReviewEligibility, ReviewSummary } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ErrorBox, Pagination } from './Common';
import { dateTime, stars } from '../utils/format';

/** Đánh giá sản phẩm (Review Service): tổng quan sao, danh sách, form cho người mua đã nhận hàng. */
export function ProductReviews({ productId }: { productId: number }) {
  const { user } = useAuth();
  const [summary, setSummary] = useState<ReviewSummary | null>(null);
  const [reviews, setReviews] = useState<Page<Review> | null>(null);
  const [eligibility, setEligibility] = useState<ReviewEligibility | null>(null);
  const [filter, setFilter] = useState<number | null>(null);
  const [page, setPage] = useState(0);

  const load = useCallback(() => {
    api.get<ReviewSummary>(`/api/reviews/products/${productId}/summary`).then(setSummary).catch(() => undefined);
    api.get<Page<Review>>(`/api/reviews/products/${productId}`, { rating: filter ?? undefined, page, size: 5 })
      .then(setReviews).catch(() => undefined);
    api.get<ReviewEligibility>(`/api/reviews/products/${productId}/eligibility`).then(setEligibility).catch(() => undefined);
  }, [productId, filter, page]);

  useEffect(load, [load]);

  return (
    <section className="card" aria-labelledby="reviews-title">
      <h2 id="reviews-title">Đánh giá sản phẩm</h2>
      {summary && (
        <div className="review-summary">
          <div>
            <div className="avg">{summary.count ? summary.average.toFixed(1) : '–'}</div>
            <div className="stars big" aria-label={`${summary.average} trên 5 sao`}>{stars(summary.average)}</div>
            <div className="muted small">{summary.count} đánh giá</div>
          </div>
          <div>
            {[5, 4, 3, 2, 1].map((star) => {
              const n = summary.distribution[String(star)] ?? 0;
              const pct = summary.count ? Math.round((n * 100) / summary.count) : 0;
              return (
                <button key={star} type="button" className={`dist-row chip ${filter === star ? 'active' : ''}`}
                        style={{ width: '100%', borderRadius: 8 }}
                        onClick={() => { setFilter(filter === star ? null : star); setPage(0); }}
                        aria-pressed={filter === star}>
                  <span>{star}★</span>
                  <span className="bar"><span style={{ width: `${pct}%` }} /></span>
                  <span>{n}</span>
                </button>
              );
            })}
          </div>
        </div>
      )}

      {eligibility?.canReview
        ? <ReviewForm productId={productId} onDone={load} />
        : <p className="muted small">
            {user ? eligibility?.reason : <><Link to="/login">Đăng nhập</Link> để đánh giá sản phẩm đã mua.</>}
          </p>}

      {reviews && reviews.content.length === 0 && <div className="empty">Chưa có đánh giá{filter ? ` ${filter} sao` : ''}</div>}
      {reviews?.content.map((r) => (
        <article key={r.id} className="review-item">
          <div className="row space-between">
            <strong>{r.authorName}</strong>
            <span className="muted tiny">{dateTime(r.createdAt)}</span>
          </div>
          <div className="stars" aria-label={`${r.rating} sao`}>{stars(r.rating)}</div>
          <span className="chip small">✔ Đã mua hàng</span>
          {r.comment && <p>{r.comment}</p>}
          {r.images.length > 0 && (
            <div className="review-images">
              {r.images.map((src) => <a key={src} href={src} target="_blank" rel="noreferrer"><img src={src} alt="Ảnh đánh giá" /></a>)}
            </div>
          )}
        </article>
      ))}
      {reviews && <Pagination page={reviews.page} totalPages={reviews.totalPages} onChange={setPage} />}
    </section>
  );
}

function ReviewForm({ productId, onDone }: { productId: number; onDone: () => void }) {
  const [rating, setRating] = useState(5);
  const [comment, setComment] = useState('');
  const [images, setImages] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.post(`/api/reviews/products/${productId}`, {
        rating,
        comment,
        images: images.split(/\s+/).map((s) => s.trim()).filter(Boolean),
      });
      onDone();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className="form card" onSubmit={submit} style={{ background: 'var(--surface-2)' }}>
      <strong>Viết đánh giá của bạn (+ điểm thưởng thành viên)</strong>
      <div className="star-input" role="radiogroup" aria-label="Số sao">
        {[1, 2, 3, 4, 5].map((s) => (
          <button key={s} type="button" role="radio" aria-checked={rating === s} aria-label={`${s} sao`}
                  className={s <= rating ? 'on' : ''} onClick={() => setRating(s)}>★</button>
        ))}
      </div>
      <label>Nhận xét<textarea rows={3} maxLength={2000} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
      <label>Link ảnh (tối đa 5, cách nhau bởi dấu cách)<input value={images} onChange={(e) => setImages(e.target.value)} placeholder="https://..." /></label>
      <ErrorBox error={error} />
      <button className="btn btn-primary" disabled={busy}>{busy ? 'Đang gửi…' : 'Gửi đánh giá'}</button>
    </form>
  );
}

/** Hỏi đáp sản phẩm: khách hỏi, nhân viên (hoặc người đã mua) trả lời. */
export function ProductQuestions({ productId }: { productId: number }) {
  const { user, hasRole } = useAuth();
  const canAnswer = hasRole('ADMIN', 'PRODUCT_MANAGER');
  const [questions, setQuestions] = useState<Question[]>([]);
  const [content, setContent] = useState('');
  const [answers, setAnswers] = useState<Record<number, string>>({});
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Question[]>(`/api/reviews/products/${productId}/questions`).then(setQuestions).catch(() => undefined);
  }, [productId]);

  useEffect(load, [load]);

  const ask = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    api.post(`/api/reviews/products/${productId}/questions`, { content })
      .then(() => { setContent(''); load(); })
      .catch((err) => setError(err.message));
  };

  const answer = (questionId: number) => {
    setError(null);
    api.post(`/api/reviews/questions/${questionId}/answers`, { content: answers[questionId] ?? '' })
      .then(() => { setAnswers({ ...answers, [questionId]: '' }); load(); })
      .catch((err) => setError(err.message));
  };

  return (
    <section className="card" aria-labelledby="qa-title">
      <h2 id="qa-title">Hỏi đáp</h2>
      {user ? (
        <form className="row" onSubmit={ask}>
          <input style={{ flex: 1 }} value={content} maxLength={1000} required placeholder="Đặt câu hỏi về sản phẩm…"
                 onChange={(e) => setContent(e.target.value)} aria-label="Câu hỏi" />
          <button className="btn">Gửi câu hỏi</button>
        </form>
      ) : <p className="muted small"><Link to="/login">Đăng nhập</Link> để đặt câu hỏi.</p>}
      <ErrorBox error={error} />
      {questions.length === 0 && <div className="empty">Chưa có câu hỏi nào</div>}
      {questions.map((q) => (
        <div key={q.id} className="qa-item">
          <div><strong>Hỏi:</strong> {q.content} <span className="muted tiny">— {q.authorName}, {dateTime(q.createdAt)}</span></div>
          {q.answers.map((a) => (
            <div key={a.id} className="qa-answer">
              <strong>{a.staff ? '🏬 ' : ''}{a.authorName}:</strong> {a.content}
            </div>
          ))}
          {canAnswer && (
            <div className="row" style={{ marginTop: 6 }}>
              <input style={{ flex: 1 }} value={answers[q.id] ?? ''} placeholder="Trả lời với tư cách nhân viên…"
                     onChange={(e) => setAnswers({ ...answers, [q.id]: e.target.value })} aria-label="Câu trả lời" />
              <button className="btn" type="button" disabled={!answers[q.id]?.trim()} onClick={() => answer(q.id)}>Trả lời</button>
            </div>
          )}
        </div>
      ))}
    </section>
  );
}
