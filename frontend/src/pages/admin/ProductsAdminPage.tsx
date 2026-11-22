import { FormEvent, useCallback, useEffect, useState } from 'react';
import { api, ApiError } from '../../api/client';
import type { Category, Page, Product } from '../../api/types';
import { ErrorBox, Loading, Modal, Pagination } from '../../components/Common';
import { money } from '../../utils/format';

interface FormState {
  id?: number;
  sku: string;
  name: string;
  description: string;
  price: string;
  categoryId: string;
  imageUrl: string;
  images: string;
  initialStock: string;
}

const EMPTY: FormState = { sku: '', name: '', description: '', price: '', categoryId: '', imageUrl: '', images: '', initialStock: '100' };

export default function ProductsAdminPage() {
  const [data, setData] = useState<Page<Product> | null>(null);
  const [categories, setCategories] = useState<Category[]>([]);
  const [page, setPage] = useState(0);
  const [q, setQ] = useState('');
  const [form, setForm] = useState<FormState | null>(null);
  const [newCategory, setNewCategory] = useState({ name: '', slug: '' });
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    api.get<Page<Product>>('/api/products', { q, page, size: 15, includeInactive: true }).then(setData).catch((e) => setError(e.message));
  }, [q, page]);

  const loadCategories = () => api.get<Category[]>('/api/categories').then(setCategories).catch(() => undefined);

  useEffect(load, [load]);
  useEffect(() => {
    loadCategories();
  }, []);

  const fail = (err: unknown) =>
    setError(err instanceof ApiError && err.errors ? Object.entries(err.errors).map(([k, v]) => `${k}: ${v}`).join('. ') : (err as Error).message);

  const save = (e: FormEvent) => {
    e.preventDefault();
    if (!form) return;
    setError(null);
    const body = {
      sku: form.sku, name: form.name, description: form.description, price: Number(form.price),
      categoryId: form.categoryId ? Number(form.categoryId) : null, imageUrl: form.imageUrl || null,
      images: form.images.split('\n').map((s) => s.trim()).filter(Boolean),
      initialStock: form.initialStock ? Number(form.initialStock) : null,
    };
    (form.id ? api.put(`/api/products/${form.id}`, body) : api.post('/api/products', body))
      .then(() => { setForm(null); load(); })
      .catch(fail);
  };

  const toggle = (p: Product) =>
    (p.active ? api.del(`/api/products/${p.id}`) : api.post(`/api/products/${p.id}/activate`)).then(load).catch(fail);

  const addCategory = (e: FormEvent) => {
    e.preventDefault();
    api.post('/api/categories', newCategory).then(() => { setNewCategory({ name: '', slug: '' }); loadCategories(); }).catch(fail);
  };

  return (
    <div>
      <div className="row space-between">
        <h2>Quản lý sản phẩm</h2>
        <button className="btn btn-primary" onClick={() => setForm({ ...EMPTY })}>+ Thêm sản phẩm</button>
      </div>
      <ErrorBox error={error} />
      <div className="card">
        <div className="row">
          <input placeholder="Tìm theo tên / SKU" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
        </div>
        {!data ? <Loading /> : (
          <table className="table">
            <thead><tr><th>ID</th><th>SKU</th><th>Tên</th><th>Danh mục</th><th>Giá</th><th>Trạng thái</th><th /></tr></thead>
            <tbody>
              {data.content.map((p) => (
                <tr key={p.id} className={p.active ? '' : 'disabled'}>
                  <td>{p.id}</td><td>{p.sku}</td><td>{p.name}</td><td>{p.categoryName}</td><td>{money(p.price)}</td>
                  <td>{p.active ? <span className="badge badge-confirmed">Đang bán</span> : <span className="badge badge-cancelled">Ngừng bán</span>}</td>
                  <td className="row">
                    <button className="btn btn-ghost" onClick={() => setForm({
                      id: p.id, sku: p.sku, name: p.name, description: p.description ?? '', price: String(p.price),
                      categoryId: p.categoryId ? String(p.categoryId) : '', imageUrl: p.imageUrl ?? '', images: p.images.join('\n'), initialStock: '',
                    })}>Sửa</button>
                    <button className="btn btn-ghost" onClick={() => toggle(p)}>{p.active ? 'Ngừng bán' : 'Mở bán'}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />}
      </div>

      <div className="card">
        <h3>Danh mục</h3>
        <div className="cat-list">
          {categories.map((c) => (
            <span key={c.id} className="chip">
              {c.name}
              <button className="btn-link" title="Xóa" onClick={() => api.del(`/api/categories/${c.id}`).then(loadCategories).catch(fail)}>✕</button>
            </span>
          ))}
        </div>
        <form className="row" onSubmit={addCategory}>
          <input placeholder="Tên danh mục" value={newCategory.name} onChange={(e) => setNewCategory({ ...newCategory, name: e.target.value })} required />
          <input placeholder="slug (vd: do-choi)" value={newCategory.slug} onChange={(e) => setNewCategory({ ...newCategory, slug: e.target.value })} required pattern="[a-z0-9-]+" />
          <button className="btn">Thêm danh mục</button>
        </form>
      </div>

      {form && (
        <Modal title={form.id ? `Sửa sản phẩm #${form.id}` : 'Thêm sản phẩm'} onClose={() => setForm(null)}>
          <form className="form" onSubmit={save}>
            <div className="row">
              <label>SKU<input value={form.sku} disabled={!!form.id} onChange={(e) => setForm({ ...form, sku: e.target.value })} required /></label>
              <label>Giá (₫)<input type="number" min={1} value={form.price} onChange={(e) => setForm({ ...form, price: e.target.value })} required /></label>
            </div>
            <label>Tên<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
            <label>Danh mục
              <select value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
                <option value="">— Không —</option>
                {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </label>
            <label>Mô tả<textarea rows={3} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
            <label>Ảnh đại diện (URL)<input value={form.imageUrl} onChange={(e) => setForm({ ...form, imageUrl: e.target.value })} /></label>
            <label>Ảnh khác (mỗi dòng 1 URL)<textarea rows={2} value={form.images} onChange={(e) => setForm({ ...form, images: e.target.value })} /></label>
            {!form.id && (
              <label>Tồn kho ban đầu (Inventory Service nhận qua sự kiện ProductCreated)
                <input type="number" min={0} value={form.initialStock} onChange={(e) => setForm({ ...form, initialStock: e.target.value })} />
              </label>
            )}
            <button className="btn btn-primary">Lưu</button>
          </form>
        </Modal>
      )}
    </div>
  );
}
