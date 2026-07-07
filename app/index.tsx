import { useRef, useState, useCallback, useEffect } from "react";
import {
  StyleSheet,
  View,
  ActivityIndicator,
  Text,
  TouchableOpacity,
  Platform,
  BackHandler,
  StatusBar,
  AppState,
  PermissionsAndroid,
  NativeModules,
} from "react-native";
import { WebView } from "react-native-webview";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Audio } from "expo-av";
import * as Location from "expo-location";
import * as Notifications from "expo-notifications";
import type { WebViewNavigation } from "react-native-webview";
import {
  BACKGROUND_LOCATION_TASK,
  saveDriverAuthInfo,
  clearDriverAuthInfo,
} from "../tasks/locationTask";

const SITE_URL = "https://taxiimpulse.ru";
const { FloatingBubble } = NativeModules;

async function startBackgroundLocationTracking() {
  if (Platform.OS !== "android") return;
  try {
    const fg = await Location.requestForegroundPermissionsAsync();
    if (fg.status !== "granted") return;
    const bg = await Location.requestBackgroundPermissionsAsync();
    if (bg.status !== "granted") return;

    const alreadyStarted = await Location.hasStartedLocationUpdatesAsync(
      BACKGROUND_LOCATION_TASK
    ).catch(() => false);
    if (alreadyStarted) return;

    await Location.startLocationUpdatesAsync(BACKGROUND_LOCATION_TASK, {
      accuracy: Location.Accuracy.High,
      timeInterval: 15000,
      distanceInterval: 20,
      showsBackgroundLocationIndicator: true,
      foregroundService: {
        notificationTitle: "Taxi Impulse — водитель на линии",
        notificationBody: "Передаём геолокацию, пока вы онлайн",
        notificationColor: "#7c3aed",
      },
    });
  } catch {
    // background location requires a compiled dev/production build — no-op in Expo Go
  }
}

async function stopBackgroundLocationTracking() {
  try {
    const alreadyStarted = await Location.hasStartedLocationUpdatesAsync(
      BACKGROUND_LOCATION_TASK
    ).catch(() => false);
    if (alreadyStarted) {
      await Location.stopLocationUpdatesAsync(BACKGROUND_LOCATION_TASK);
    }
  } catch {
    // ignore
  }
  await clearDriverAuthInfo();
}

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: true,
    shouldSetBadge: true,
    shouldShowBanner: true,
    shouldShowList: true,
  }),
});

const INJECTED_JS = `
  (function() {
    window.__TAXI_NATIVE_APP__ = true;
    window.__TAXI_APP_PLATFORM__ = '${Platform.OS}';
    document.documentElement.setAttribute('data-native-app', 'true');

    // Intercept window.Notification — relay to native bridge for sound + system notification.
    var N = function(title, opts) {
      try { window.ReactNativeWebView && window.ReactNativeWebView.postMessage(
        JSON.stringify({ type: 'NOTIFICATION', title: title, body: (opts && opts.body) || '' })); } catch(e){}
    };
    N.requestPermission = function() { return Promise.resolve('granted'); };
    N.permission = 'granted';
    try { window.Notification = N; } catch(e) {}

    // Send auth token to native on every page load/focus so native can poll
    // /api/push/poll for notifications independently of the WebView JS runtime.
    // Works for ALL roles (passenger, driver, admin) without needing DRIVER_AUTH_INFO.
    function sendAuthToNative() {
      try {
        var token = localStorage.getItem('taxi_token');
        if (token && window.ReactNativeWebView) {
          window.ReactNativeWebView.postMessage(
            JSON.stringify({ type: 'USER_AUTH_INFO', token: token })
          );
        }
      } catch(e) {}
    }
    sendAuthToNative();
    document.addEventListener('visibilitychange', function() {
      if (document.visibilityState === 'visible') sendAuthToNative();
    });
    window.addEventListener('storage', function(e) {
      if (e.key === 'taxi_token') sendAuthToNative();
    });
  })();
  true;
`;

async function requestLocationPermission() {
  if (Platform.OS === "android") {
    try {
      const results = await PermissionsAndroid.requestMultiple([
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
        PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION,
      ]);
      return (
        results[PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION] === "granted" ||
        results[PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION] === "granted"
      );
    } catch {
      return false;
    }
  }
  try {
    const { status } = await Location.requestForegroundPermissionsAsync();
    return status === "granted";
  } catch {
    return false;
  }
}

async function isLocationPermissionGranted() {
  try {
    const { status } = await Location.getForegroundPermissionsAsync();
    return status === "granted";
  } catch {
    return false;
  }
}

async function playNotificationSound() {
  try {
    await Audio.setAudioModeAsync({ playsInSilentModeIOS: true });
    const { sound } = await Audio.Sound.createAsync(
      require("../assets/notification.mp3"),
      { shouldPlay: true, volume: 1.0 }
    );
    sound.setOnPlaybackStatusUpdate((status) => {
      if (status.isLoaded && status.didJustFinish) {
        sound.unloadAsync().catch(() => {});
      }
    });
  } catch {
    // ignore — sound is a nice-to-have, not critical
  }
}

export default function WebViewScreen() {
  const insets = useSafeAreaInsets();
  const webviewRef = useRef<WebView>(null);
  const [canGoBack, setCanGoBack] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [locationDenied, setLocationDenied] = useState(false);
  const hasLoadedRef = useRef(false);
  const loadingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const appStateRef = useRef(AppState.currentState);
  const bubbleActiveRef = useRef(false);
  const bubbleCountRef = useRef(0);
  const fcmTokenRef = useRef<string | null>(null);
  const driverAuthTokenRef = useRef<string | null>(null);
  const driverBaseUrlRef = useRef<string | null>(null);

  const ensureLocationPermission = useCallback(async () => {
    const granted = await requestLocationPermission();
    setLocationDenied(!granted);
    return granted;
  }, []);

  useEffect(() => {
    Notifications.requestPermissionsAsync().catch(() => {});
    if (Platform.OS === "android") {
      Notifications.setNotificationChannelAsync("taxi-impulse", {
        name: "Taxi Impulse",
        importance: Notifications.AndroidImportance.HIGH,
        sound: "notification.mp3",
        vibrationPattern: [0, 250, 250, 250],
        enableVibrate: true,
      }).catch(() => {});
    }

    ensureLocationPermission();

    // Check + request overlay ("draw over other apps") permission on first launch.
    // No-op in Expo Go — FloatingBubble is only present in a native/dev build.
    FloatingBubble?.hasPermission?.((has: boolean) => {
      if (!has) FloatingBubble?.requestPermission?.();
    });

    // Register for real push notifications (FCM) so orders/messages can be
    // delivered even when the app is fully closed — the WebView JS bridge
    // (window.Notification shim) only works while the app process is alive,
    // which is not enough for a killed/backgrounded app.
    if (Platform.OS === "android") {
      (async () => {
        try {
          const current = await Notifications.getPermissionsAsync();
          let granted = current.status === "granted";
          if (!granted) {
            const req = await Notifications.requestPermissionsAsync();
            granted = req.status === "granted";
          }
          if (!granted) return;
          const tokenData = await Notifications.getDevicePushTokenAsync();
          fcmTokenRef.current = tokenData.data;
          sendFcmTokenToWebView();
        } catch {
          // getDevicePushTokenAsync requires a compiled build with Firebase
          // configured (google-services.json) — no-op otherwise.
        }
      })();
    }

    const sub = AppState.addEventListener("change", async (next) => {
      const wasBackground = appStateRef.current !== "active";
      appStateRef.current = next;
      // Re-check permissions when the user comes back from Settings
      // (e.g. after granting location or overlay permission there).
      if (wasBackground && next === "active") {
        const granted = await isLocationPermissionGranted();
        setLocationDenied(!granted);

        // Retry starting the bubble after the user may have granted overlay permission.
        if (bubbleActiveRef.current) {
          FloatingBubble?.hasPermission?.((has: boolean) => {
            if (has) FloatingBubble?.start?.(bubbleCountRef.current);
          });
        }

        // Check if the driver tapped "Принять заказ" in the native bubble panel
        // while the app was in the background. Delay 600ms so the WebView has
        // time to finish rendering and the React component can attach its listener.
        FloatingBubble?.popPendingAccept?.((orderId: number) => {
          if (orderId > 0) {
            setTimeout(() => {
              webviewRef.current?.injectJavaScript(
                `(function(){try{window.dispatchEvent(new CustomEvent('taxi-native-accept-order',{detail:${orderId}}));}catch(e){}})();true;`
              );
            }, 600);
          }
        });

        // Check if the driver pressed × (close) in the native bubble panel.
        FloatingBubble?.popPendingClose?.((shouldClose: boolean) => {
          if (shouldClose) {
            setTimeout(() => {
              webviewRef.current?.injectJavaScript(
                `(function(){try{window.dispatchEvent(new CustomEvent('taxi-native-close-bubble'));}catch(e){}})();true;`
              );
            }, 400);
          }
        });
      }
    });
    return () => sub.remove();
  }, [ensureLocationPermission]);

  // Native push polling — runs in RN JS runtime (not the WebView), so it works
  // even when the WebView is suspended/throttled in the background.
  // Every 15 s we drain pending server-side notifications for the driver user.
  useEffect(() => {
    const interval = setInterval(async () => {
      const token = driverAuthTokenRef.current;
      const base  = driverBaseUrlRef.current;
      if (!token || !base) return;
      try {
        const resp = await fetch(`${base}/api/push/poll`, {
          headers: { Authorization: `Bearer ${token}` },
          signal: AbortSignal.timeout(8000),
        });
        if (!resp.ok) return;
        const data = await resp.json();
        const notifs: Array<{ title: string; body: string }> = data.notifications ?? [];
        for (const n of notifs) {
          await playNotificationSound();
          await Notifications.scheduleNotificationAsync({
            content: {
              title: n.title || "Taxi Impulse",
              body:  n.body  || "",
              sound: "notification.mp3",
              android: { channelId: "taxi-impulse" },
            },
            trigger: null,
          });
        }
      } catch {}
    }, 15000);
    return () => clearInterval(interval);
  }, []);

  const handleMessage = useCallback(
    async (event: { nativeEvent: { data: string } }) => {
      try {
        const msg = JSON.parse(event.nativeEvent.data);

        if (msg.type === "NOTIFICATION") {
          await playNotificationSound();
          if (appStateRef.current !== "active") {
            await Notifications.scheduleNotificationAsync({
              content: {
                title: msg.title || "Taxi Impulse",
                body: msg.body || "",
                sound: "notification.mp3",
                // Android: use our configured channel which has sound + vibration
                android: { channelId: "taxi-impulse" },
              },
              trigger: null,
            });
          }
          return;
        }

        // Driver auth info relayed from the web page — needed so the native
        // background location task can authenticate its own requests when
        // the app is not in the foreground.
        // Auth token from INJECTED_JS (fires on every page load for all roles).
        // Used by the native push-poll interval so notifications work even when
        // the WebView JS runtime is throttled in the background.
        if (msg.type === "USER_AUTH_INFO") {
          if (msg.token) {
            driverAuthTokenRef.current = msg.token;
            // Base URL is the same as the site URL hardcoded in this file
            driverBaseUrlRef.current = SITE_URL;
          }
          return;
        }

        if (msg.type === "DRIVER_AUTH_INFO") {
          if (msg.token && msg.driverId) {
            await saveDriverAuthInfo(msg.token, msg.driverId);
          }
          // Give the native FloatingBubble service the credentials it needs
          // to fetch orders itself when the WebView is suspended in background.
          if (msg.token && msg.city && msg.baseUrl) {
            FloatingBubble?.setDriverInfo?.(msg.token, msg.city, msg.baseUrl);
          }
          // Store for native push polling (runs in RN JS runtime, not WebView)
          if (msg.token) driverAuthTokenRef.current = msg.token;
          if (msg.baseUrl) driverBaseUrlRef.current = msg.baseUrl;
          return;
        }

        // Activate the floating bubble service — a real overlay drawn over
        // other apps (Android only, requires a native/dev build). While the
        // bubble is active we also keep sending GPS updates in the
        // background so the driver stays visible even off-screen.
        if (msg.type === "DRIVER_BUBBLE_ACTIVE") {
          bubbleActiveRef.current = true;
          bubbleCountRef.current = msg.count ?? 0;
          FloatingBubble?.start?.(bubbleCountRef.current);
          startBackgroundLocationTracking();
          return;
        }

        if (msg.type === "DRIVER_BUBBLE_UPDATE") {
          bubbleCountRef.current = msg.count ?? 0;
          if (bubbleActiveRef.current) {
            FloatingBubble?.update?.(bubbleCountRef.current);
            // Pass full orders so native panel can show addresses + prices
            if (msg.orders !== undefined) {
              FloatingBubble?.updateOrders?.(JSON.stringify(msg.orders ?? []));
            }
          }
          return;
        }

        if (msg.type === "DRIVER_BUBBLE_HIDE") {
          bubbleActiveRef.current = false;
          bubbleCountRef.current = 0;
          FloatingBubble?.stop?.();
          stopBackgroundLocationTracking();
          return;
        }

        if (msg.type === "REQUEST_LOCATION_PERMISSION") {
          await ensureLocationPermission();
          return;
        }
      } catch {
        // ignore malformed messages from the page
      }
    },
    [ensureLocationPermission]
  );

  const sendFcmTokenToWebView = useCallback(() => {
    const token = fcmTokenRef.current;
    if (!token) return;
    const js = `
      (function() {
        try {
          window.dispatchEvent(new CustomEvent('taxi-native-fcm-token', { detail: ${JSON.stringify(token)} }));
        } catch (e) {}
      })();
      true;
    `;
    webviewRef.current?.injectJavaScript(js);
  }, []);

  const handleBack = useCallback(() => {
    if (canGoBack) {
      webviewRef.current?.goBack();
      return true;
    }
    return false;
  }, [canGoBack]);

  useEffect(() => {
    if (Platform.OS !== "android") return;
    const sub = BackHandler.addEventListener("hardwareBackPress", handleBack);
    return () => sub.remove();
  }, [handleBack]);

  return (
    <View style={[styles.container, { paddingTop: insets.top }]}>
      <StatusBar barStyle="light-content" backgroundColor="#19063e" />

      {loading && !error && (
        <View style={styles.loadingOverlay}>
          <Text style={styles.loadingTitle}>TAXI IMPULSE</Text>
          <ActivityIndicator size="large" color="#7c3aed" style={{ marginTop: 24 }} />
          <Text style={styles.loadingHint}>Загрузка...</Text>
        </View>
      )}

      {!loading && error && (
        <View style={styles.errorContainer}>
          <Text style={styles.errorTitle}>Нет соединения</Text>
          <Text style={styles.errorText}>Проверьте подключение к интернету</Text>
          <TouchableOpacity
            style={styles.retryBtn}
            onPress={() => {
              setError(false);
              setLoading(true);
              hasLoadedRef.current = false;
              webviewRef.current?.reload();
            }}
          >
            <Text style={styles.retryText}>Повторить</Text>
          </TouchableOpacity>
        </View>
      )}

      {!loading && !error && locationDenied && (
        <TouchableOpacity style={styles.locationBanner} onPress={ensureLocationPermission}>
          <Text style={styles.locationBannerText}>
            Геолокация выключена — нажмите, чтобы разрешить доступ
          </Text>
        </TouchableOpacity>
      )}

      <WebView
        ref={webviewRef}
        source={{ uri: SITE_URL }}
        style={[styles.webview, error && styles.hidden]}
        injectedJavaScript={INJECTED_JS}
        javaScriptEnabled
        domStorageEnabled
        geolocationEnabled
        allowsBackForwardNavigationGestures
        allowsInlineMediaPlayback
        mediaPlaybackRequiresUserAction={false}
        onNavigationStateChange={(nav: WebViewNavigation) => setCanGoBack(nav.canGoBack)}
        onLoadStart={() => {
          if (hasLoadedRef.current) return;
          setLoading(true);
          setError(false);
          if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
          loadingTimerRef.current = setTimeout(() => setLoading(false), 8000);
        }}
        onLoadEnd={() => {
          if (loadingTimerRef.current) clearTimeout(loadingTimerRef.current);
          hasLoadedRef.current = true;
          setLoading(false);
          sendFcmTokenToWebView();
        }}
        onError={() => {
          setLoading(false);
          setError(!hasLoadedRef.current);
        }}
        onHttpError={(e) => {
          if (e.nativeEvent.statusCode >= 500 && !hasLoadedRef.current) {
            setError(true);
            setLoading(false);
          }
        }}
        onMessage={handleMessage}
        userAgent={`TaxiImpulseApp/1.0 (${Platform.OS})`}
        sharedCookiesEnabled
        thirdPartyCookiesEnabled
        cacheEnabled
        originWhitelist={["*"]}
        onContentProcessDidTerminate={() => webviewRef.current?.reload()}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#19063e" },
  webview: { flex: 1, backgroundColor: "#08081a" },
  hidden: { opacity: 0, flex: 0, height: 0 },
  loadingOverlay: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "#19063e",
    alignItems: "center",
    justifyContent: "center",
    zIndex: 10,
  },
  loadingTitle: { fontSize: 24, fontWeight: "700", color: "#fff", letterSpacing: 3 },
  loadingHint: { fontSize: 13, color: "#ffffff50", marginTop: 12 },
  errorContainer: { flex: 1, alignItems: "center", justifyContent: "center", padding: 40, gap: 12 },
  errorTitle: { fontSize: 20, fontWeight: "700", color: "#fff" },
  errorText: { fontSize: 14, color: "#ffffff50", textAlign: "center" },
  retryBtn: { marginTop: 12, backgroundColor: "#7c3aed", paddingHorizontal: 24, paddingVertical: 12, borderRadius: 12 },
  retryText: { color: "#fff", fontWeight: "600", fontSize: 15 },
  locationBanner: {
    position: "absolute",
    top: 8,
    left: 12,
    right: 12,
    zIndex: 20,
    backgroundColor: "#f59e0b",
    borderRadius: 10,
    paddingVertical: 8,
    paddingHorizontal: 14,
  },
  locationBannerText: { color: "#1a1a1a", fontSize: 12, fontWeight: "600", textAlign: "center" },
});
