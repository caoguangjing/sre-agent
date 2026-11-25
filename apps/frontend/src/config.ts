declare const __PUBLIC_CONFIG__: { apiBaseUrl: string; title: string };
export const APP_TITLE = __PUBLIC_CONFIG__.title;
export const API_BASE_URL = resolveApiBaseUrl(__PUBLIC_CONFIG__.apiBaseUrl);
export function resolveApiBaseUrl(apiBaseUrl: string): string { return apiBaseUrl.trim().replace(/\/+$/, ""); }
