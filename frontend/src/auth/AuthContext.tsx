import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, getToken, setToken } from '../api/client';
import type { TokenResponse, User } from '../api/types';

export const STAFF_ROLES = ['ADMIN', 'PRODUCT_MANAGER', 'WAREHOUSE_MANAGER', 'ANALYST'];

interface AuthState {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<User>;
  register: (email: string, password: string, fullName: string) => Promise<User>;
  logout: () => void;
  hasRole: (...roles: string[]) => boolean;
  isStaff: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!getToken()) {
      setLoading(false);
      return;
    }
    api.get<User>('/api/auth/me')
      .then(setUser)
      .catch(() => setToken(null))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const onUnauthorized = () => setUser(null);
    window.addEventListener('shop:unauthorized', onUnauthorized);
    return () => window.removeEventListener('shop:unauthorized', onUnauthorized);
  }, []);

  const handleToken = (res: TokenResponse) => {
    setToken(res.accessToken);
    setUser(res.user);
    return res.user;
  };

  const login = useCallback(
    (email: string, password: string) => api.post<TokenResponse>('/api/auth/login', { email, password }).then(handleToken),
    [],
  );

  const register = useCallback(
    (email: string, password: string, fullName: string) =>
      api.post<TokenResponse>('/api/auth/register', { email, password, fullName }).then(handleToken),
    [],
  );

  const logout = useCallback(() => {
    api.post('/api/auth/logout').catch(() => undefined);
    setToken(null);
    setUser(null);
  }, []);

  const value = useMemo<AuthState>(() => {
    const hasRole = (...roles: string[]) => !!user && roles.some((r) => user.roles.includes(r));
    return { user, loading, login, register, logout, hasRole, isStaff: hasRole(...STAFF_ROLES) };
  }, [user, loading, login, register, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth phải nằm trong AuthProvider');
  return ctx;
}
