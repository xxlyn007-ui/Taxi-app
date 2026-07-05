import * as TaskManager from "expo-task-manager";
import AsyncStorage from "@react-native-async-storage/async-storage";

export const BACKGROUND_LOCATION_TASK = "taxi-impulse-driver-location";

const SITE_URL = "https://taxiimpulse.ru";

const AUTH_TOKEN_KEY = "taxi_native_driver_token";
const DRIVER_ID_KEY = "taxi_native_driver_id";

export async function saveDriverAuthInfo(token: string, driverId: number) {
  try {
    await AsyncStorage.setItem(AUTH_TOKEN_KEY, token);
    await AsyncStorage.setItem(DRIVER_ID_KEY, String(driverId));
  } catch {
    // ignore
  }
}

export async function clearDriverAuthInfo() {
  try {
    await AsyncStorage.multiRemove([AUTH_TOKEN_KEY, DRIVER_ID_KEY]);
  } catch {
    // ignore
  }
}

TaskManager.defineTask(BACKGROUND_LOCATION_TASK, async ({ data, error }) => {
  if (error) return;
  const locations = (data as any)?.locations;
  const latest = Array.isArray(locations) ? locations[locations.length - 1] : null;
  if (!latest) return;

  try {
    const [token, driverId] = await Promise.all([
      AsyncStorage.getItem(AUTH_TOKEN_KEY),
      AsyncStorage.getItem(DRIVER_ID_KEY),
    ]);
    if (!token || !driverId) return;

    await fetch(`${SITE_URL}/api/drivers/${driverId}/location`, {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        lat: latest.coords.latitude,
        lon: latest.coords.longitude,
      }),
    });
  } catch {
    // network errors are expected in background — just skip this tick
  }
});
