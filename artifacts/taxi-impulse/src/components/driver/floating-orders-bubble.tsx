import { useState, useRef, useEffect, useCallback } from "react";
import { useLocation } from "wouter";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useAuthHeaders } from "@/hooks/use-auth";
import { X, ChevronRight, MapPin, Clock, Zap } from "lucide-react";
import { formatMoney } from "@/lib/utils";

const BASE = import.meta.env.BASE_URL?.replace(/\/$/, "") || "";

interface Props {
  workCity: string;
  driverId: number;
  onClose: () => void;
}

export function FloatingOrdersBubble({ workCity, driverId, onClose }: Props) {
  const [, navigate] = useLocation();
  const { headers } = useAuthHeaders();
  const qc = useQueryClient();
  const [expanded, setExpanded] = useState(false);
  const [pos, setPos] = useState({ x: window.innerWidth - 76, y: window.innerHeight - 160 });
  const dragging = useRef(false);
  const startRef = useRef({ mx: 0, my: 0, ox: 0, oy: 0 });
  const bubbleRef = useRef<HTMLDivElement>(null);
  const gpsWatchRef = useRef<number | null>(null);

  // GPS всегда активен пока пузырёк на экране
  useEffect(() => {
    if (!navigator.geolocation) return;
    const sendPos = (pos: GeolocationPosition) => {
      fetch(`${BASE}/api/drivers/${driverId}/location`, {
        method: "PATCH",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify({ lat: pos.coords.latitude, lon: pos.coords.longitude }),
      }).catch(() => {});
    };
    gpsWatchRef.current = navigator.geolocation.watchPosition(sendPos, () => {}, {
      enableHighAccuracy: true, maximumAge: 5000, timeout: 15000,
    });
    return () => {
      if (gpsWatchRef.current !== null) navigator.geolocation.clearWatch(gpsWatchRef.current);
    };
  }, [driverId]);

  // Доступные заказы
  const { data: orders } = useQuery({
    queryKey: ["/api/orders", "pending", workCity],
    queryFn: async () => {
      const r = await fetch(`${BASE}/api/orders?status=pending&city=${encodeURIComponent(workCity)}`, { headers });
      const data = await r.json() as any[];
      return Array.isArray(data) ? data.filter((o: any) => o.city === workCity && o.status === "pending") : [];
    },
    refetchInterval: 5000,
  });

  const acceptOrder = useMutation({
    mutationFn: async (orderId: number) => {
      const r = await fetch(`${BASE}/api/orders/${orderId}/accept`, {
        method: "PATCH",
        headers: { ...headers, "Content-Type": "application/json" },
        body: JSON.stringify({ driverId }),
      });
      if (!r.ok) throw new Error("Ошибка принятия");
      return r.json();
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["/api/orders"] });
      onClose();
      navigate("/driver");
    },
  });

  // При монтировании сообщаем нативному — пузырёк активен, и передаём
  // токен + id водителя, чтобы нативное приложение могло само слать
  // геолокацию в фоне (когда WebView не активен на экране).
  useEffect(() => {
    if (!(window as any).__TAXI_NATIVE_APP__) return;
    try {
      const token = localStorage.getItem("taxi_token");
      if (token) {
        (window as any).ReactNativeWebView?.postMessage(
          JSON.stringify({ type: 'DRIVER_AUTH_INFO', token, driverId })
        );
      }
      (window as any).ReactNativeWebView?.postMessage(
        JSON.stringify({ type: 'DRIVER_BUBBLE_ACTIVE', count: orders?.length ?? 0 })
      );
    } catch {}
    return () => {
      try {
        (window as any).ReactNativeWebView?.postMessage(
          JSON.stringify({ type: 'DRIVER_BUBBLE_HIDE' })
        );
      } catch {}
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Уведомляем нативное о заказах при каждом обновлении (count + полный список)
  useEffect(() => {
    if (!(window as any).__TAXI_NATIVE_APP__) return;
    const count = orders?.length ?? 0;
    const orderPayload = (orders ?? []).slice(0, 10).map((o: any) => ({
      id: o.id,
      fromAddress: o.fromAddress,
      toAddress: o.toAddress,
      price: o.price,
    }));
    try {
      (window as any).ReactNativeWebView?.postMessage(
        JSON.stringify({ type: 'DRIVER_BUBBLE_UPDATE', count, orders: orderPayload })
      );
    } catch {}
  }, [orders]);

  // Слушаем события от нативного пузыря
  useEffect(() => {
    if (!(window as any).__TAXI_NATIVE_APP__) return;

    const onAccept = (e: Event) => {
      const orderId = (e as CustomEvent).detail;
      if (orderId) acceptOrder.mutate(orderId);
    };
    const onNativeClose = () => onClose();

    window.addEventListener('taxi-native-accept-order', onAccept);
    window.addEventListener('taxi-native-close-bubble', onNativeClose);
    return () => {
      window.removeEventListener('taxi-native-accept-order', onAccept);
      window.removeEventListener('taxi-native-close-bubble', onNativeClose);
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Drag — touch
  const onTouchStart = useCallback((e: React.TouchEvent) => {
    if (expanded) return;
    dragging.current = true;
    const t = e.touches[0];
    startRef.current = { mx: t.clientX, my: t.clientY, ox: pos.x, oy: pos.y };
  }, [expanded, pos]);

  const onTouchMove = useCallback((e: React.TouchEvent) => {
    if (!dragging.current) return;
    e.preventDefault();
    const t = e.touches[0];
    const nx = startRef.current.ox + (t.clientX - startRef.current.mx);
    const ny = startRef.current.oy + (t.clientY - startRef.current.my);
    setPos({
      x: Math.max(8, Math.min(window.innerWidth - 68, nx)),
      y: Math.max(8, Math.min(window.innerHeight - 68, ny)),
    });
  }, []);

  const onTouchEnd = useCallback(() => { dragging.current = false; }, []);

  // Drag — mouse
  const onMouseDown = useCallback((e: React.MouseEvent) => {
    if (expanded) return;
    dragging.current = true;
    startRef.current = { mx: e.clientX, my: e.clientY, ox: pos.x, oy: pos.y };
    const onMove = (ev: MouseEvent) => {
      if (!dragging.current) return;
      const nx = startRef.current.ox + (ev.clientX - startRef.current.mx);
      const ny = startRef.current.oy + (ev.clientY - startRef.current.my);
      setPos({
        x: Math.max(8, Math.min(window.innerWidth - 68, nx)),
        y: Math.max(8, Math.min(window.innerHeight - 68, ny)),
      });
    };
    const onUp = () => { dragging.current = false; window.removeEventListener("mousemove", onMove); window.removeEventListener("mouseup", onUp); };
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);
  }, [expanded, pos]);

  const pendingCount = orders?.length ?? 0;

  return (
    <div style={{ position: "fixed", inset: 0, zIndex: 9999, pointerEvents: "none" }}>
      {/* Затемнение при раскрытии */}
      {expanded && (
        <div style={{ pointerEvents: "auto" }} className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setExpanded(false)} />
      )}

      {/* Плавающий пузырёк */}
      <div
        ref={bubbleRef}
        style={{ position: "absolute", left: pos.x, top: pos.y, pointerEvents: "auto", touchAction: "none" }}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        onMouseDown={onMouseDown}
      >
        {!expanded ? (
          // Свёрнутый круг
          <button
            onClick={() => setExpanded(true)}
            className="w-14 h-14 rounded-full bg-violet-600 shadow-2xl flex items-center justify-center relative select-none"
            style={{ boxShadow: "0 0 0 3px rgba(124,58,237,0.4), 0 8px 24px rgba(0,0,0,0.6)" }}
          >
            <Zap className="w-6 h-6 text-white" />
            {pendingCount > 0 && (
              <span className="absolute -top-1 -right-1 w-5 h-5 rounded-full bg-red-500 text-white text-xs font-bold flex items-center justify-center">
                {pendingCount > 9 ? "9+" : pendingCount}
              </span>
            )}
          </button>
        ) : (
          // Раскрытая панель
          <div
            className="w-80 bg-[#0d0d1f] border border-violet-500/30 rounded-2xl shadow-2xl overflow-hidden"
            style={{ position: "absolute", right: 0, bottom: 0 }}
            onClick={e => e.stopPropagation()}
          >
            <div className="flex items-center justify-between px-4 py-3 border-b border-white/[0.06]">
              <div className="flex items-center gap-2">
                <Zap className="w-4 h-4 text-violet-400" />
                <span className="text-sm font-semibold text-white">Доступные заказы</span>
                {pendingCount > 0 && (
                  <span className="px-1.5 py-0.5 rounded-full bg-violet-600 text-white text-xs font-bold">{pendingCount}</span>
                )}
              </div>
              <div className="flex gap-1">
                <button onClick={() => setExpanded(false)} className="w-7 h-7 flex items-center justify-center rounded-lg text-white/40 hover:text-white/70 transition-colors">
                  <ChevronRight className="w-4 h-4 rotate-90" />
                </button>
                <button onClick={onClose} className="w-7 h-7 flex items-center justify-center rounded-lg text-white/40 hover:text-red-400 transition-colors">
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>

            <div className="max-h-96 overflow-y-auto divide-y divide-white/[0.04]">
              {!orders || orders.length === 0 ? (
                <div className="py-8 text-center text-white/30 text-sm">
                  <Clock className="w-6 h-6 mx-auto mb-2 opacity-40" />
                  Нет заказов в {workCity}
                </div>
              ) : (
                orders.slice(0, 10).map((order: any) => (
                  <div key={order.id} className="p-3">
                    <div className="flex items-start gap-2 mb-2">
                      <MapPin className="w-3.5 h-3.5 text-violet-400 mt-0.5 shrink-0" />
                      <div className="flex-1 min-w-0">
                        <div className="text-xs text-white/70 truncate">{order.fromAddress}</div>
                        {order.toAddress && <div className="text-xs text-white/30 truncate">→ {order.toAddress}</div>}
                      </div>
                      <span className="text-sm font-bold text-white shrink-0">{formatMoney(order.price || 0)} ₽</span>
                    </div>
                    <button
                      onClick={() => acceptOrder.mutate(order.id)}
                      disabled={acceptOrder.isPending}
                      className="w-full py-2 rounded-xl bg-violet-600 hover:bg-violet-500 disabled:opacity-50 text-white text-xs font-semibold transition-all"
                    >
                      Принять заказ
                    </button>
                  </div>
                ))
              )}
            </div>

            <div className="px-4 py-2.5 border-t border-white/[0.06] flex items-center gap-2">
              <div className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
              <span className="text-xs text-white/30">GPS активен · обновление каждые 5 сек</span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
