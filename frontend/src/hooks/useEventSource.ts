import { useEffect, useRef, useState } from 'react';
import { streamUrl } from '../api/client';

type Handlers = Record<string, (data: any) => void>;

/**
 * Kết nối Server-Sent Events qua API Gateway; tự kết nối lại khi mất kết nối (EventSource làm sẵn).
 * Trả về trạng thái kết nối để hiển thị trên UI.
 */
export function useEventSource(path: string | null, handlers: Handlers): 'connecting' | 'open' | 'closed' {
  const [status, setStatus] = useState<'connecting' | 'open' | 'closed'>('closed');
  const handlersRef = useRef(handlers);
  handlersRef.current = handlers;

  useEffect(() => {
    if (!path) {
      setStatus('closed');
      return;
    }
    setStatus('connecting');
    const source = new EventSource(streamUrl(path));
    source.onopen = () => setStatus('open');
    source.onerror = () => setStatus(source.readyState === EventSource.CLOSED ? 'closed' : 'connecting');

    const names = Object.keys(handlersRef.current);
    const listeners = names.map((name) => {
      const listener = (e: MessageEvent) => {
        try {
          handlersRef.current[name]?.(JSON.parse(e.data));
        } catch {
          handlersRef.current[name]?.(e.data);
        }
      };
      source.addEventListener(name, listener as EventListener);
      return [name, listener] as const;
    });

    return () => {
      listeners.forEach(([name, l]) => source.removeEventListener(name, l as EventListener));
      source.close();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path]);

  return status;
}
