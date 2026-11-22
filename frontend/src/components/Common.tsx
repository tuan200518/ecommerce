import { ReactNode } from 'react';
import { ORDER_STATUS_LABEL, PAYMENT_STATUS_LABEL } from '../utils/format';

export function StatusBadge({ status, kind = 'order' }: { status: string; kind?: 'order' | 'payment' }) {
  const label = (kind === 'order' ? ORDER_STATUS_LABEL : PAYMENT_STATUS_LABEL)[status] ?? status;
  return <span className={`badge badge-${status.toLowerCase()}`}>{label}</span>;
}

export function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  if (totalPages <= 1) return null;
  return (
    <div className="pagination">
      <button className="btn btn-ghost" disabled={page <= 0} onClick={() => onChange(page - 1)}>‹ Trước</button>
      <span>Trang {page + 1} / {totalPages}</span>
      <button className="btn btn-ghost" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)}>Sau ›</button>
    </div>
  );
}

export function ErrorBox({ error }: { error: string | null }) {
  if (!error) return null;
  return <div className="alert alert-error">{error}</div>;
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>;
}

export function Loading() {
  return <div className="empty">Đang tải…</div>;
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()} role="dialog" aria-label={title}>
        <div className="modal-header">
          <h3>{title}</h3>
          <button className="btn btn-ghost" onClick={onClose} aria-label="Đóng">✕</button>
        </div>
        {children}
      </div>
    </div>
  );
}
