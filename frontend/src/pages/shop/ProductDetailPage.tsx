import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { Availability, Product } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { ErrorBox, Loading } from '../../components/Common';
import { ProductQuestions, ProductReviews } from '../../components/Reviews';
import { money } from '../../utils/format';
import { FlashPrice } from '../../components/Promotions';
import { useFlashOffers } from '../../hooks/useFlashSales';
import { RecommendationStrip } from '../../components/Recommendations';

export default function ProductDetailPage() {
  const { id } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [product, setProduct] = useState<Product | null>(null);
  const offers = useFlashOffers();
  const [stock, setStock] = useState<Availability | null>(null);
  const [image, setImage] = useState<string | null>(null);
  const [qty, setQty] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(() => {
    api.get<Product>(`/api/products/${id}`)
      .then((p) => {
        setProduct(p);
        setImage(p.imageUrl ?? p.images[0] ?? null);
      })
      .catch((e) => setError(e.message));
    api.get<Availability[]>('/api/inventory/availability', { productIds: String(id) })
      .then((list) => setStock(list[0] ?? null))
      .catch(() => undefined);
  }, [id]);

  const addToCart = async (goToCart: boolean) => {
    if (!user) {
      navigate('/login', { state: { from: `/products/${id}` } });
      return;
    }
    setError(null);
    try {
      await api.post('/api/cart/items', { productId: Number(id), quantity: qty });
      if (goToCart) navigate('/cart');
      else setMessage(`Đã thêm ${qty} sản phẩm vào giỏ hàng`);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  if (error && !product) return <ErrorBox error={error} />;
  if (!product) return <Loading />;

  return (
    <>
    <div className="detail card">
      <div className="gallery">
        <div className="gallery-main">{image ? <img src={image} alt={product.name} /> : <span>📦</span>}</div>
        <div className="thumbs">
          {[product.imageUrl, ...product.images].filter((x, i, arr): x is string => !!x && arr.indexOf(x) === i).map((src) => (
            <button key={src} className={`thumb ${src === image ? 'active' : ''}`} onClick={() => setImage(src)}>
              <img src={src} alt="" />
            </button>
          ))}
        </div>
      </div>
      <div className="detail-info">
        <Link to={`/?categoryId=${product.categoryId ?? ''}`} className="muted small">{product.categoryName}</Link>
        <h1>{product.name}</h1>
        <div className="muted small">SKU: {product.sku}</div>
        <FlashPrice price={product.price} offer={offers[product.id]} big />
        {stock && (stock.inStock
          ? <div className={`stock ${stock.available <= 10 ? 'low' : ''}`}>Còn {stock.available} sản phẩm</div>
          : <div className="stock out">Tạm hết hàng</div>)}
        {!product.active && <div className="alert alert-error">Sản phẩm đã ngừng kinh doanh</div>}
        <p>{product.description}</p>
        <div className="row">
          <input type="number" min={1} max={20} value={qty} onChange={(e) => setQty(Math.max(1, Math.min(20, Number(e.target.value))))} style={{ width: 80 }} />
          <button className="btn" disabled={!product.active || stock?.inStock === false} onClick={() => addToCart(false)}>Thêm vào giỏ</button>
          <button className="btn btn-primary" disabled={!product.active || stock?.inStock === false} onClick={() => addToCart(true)}>Mua ngay</button>
        </div>
        {message && <div className="alert alert-success">{message} · <Link to="/cart">Xem giỏ hàng</Link></div>}
        <ErrorBox error={error} />
      </div>
    </div>
    <RecommendationStrip path={`products/${product.id}/bought-together`} query={{ limit: 4 }}
                         title="🛍️ Thường được mua cùng" subtitle="Từ các đơn hàng đã thanh toán" />
    <RecommendationStrip path={`products/${product.id}/similar`} query={{ limit: 8 }} title="Sản phẩm tương tự" />
    <ProductReviews productId={product.id} />
    <ProductQuestions productId={product.id} />
    </>
  );
}
