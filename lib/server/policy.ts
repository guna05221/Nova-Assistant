export type NovaDeviceAction =
  | { type: "OPEN_APP"; packageName: string }
  | { type: "OPEN_MAPS_SEARCH"; query: string }
  | { type: "WEB_SEARCH"; query: string };

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH";

const DEVICE_ACTIONS = new Set(["OPEN_APP", "OPEN_MAPS_SEARCH", "WEB_SEARCH"]);
const HIGH_RISK_INTENTS = new Set([
  "SEND_MESSAGE", "SEND_EMAIL", "PLACE_ORDER", "MAKE_PAYMENT",
  "PURCHASE", "DELETE_DATA", "CHANGE_SECURITY_SETTINGS", "PUBLISH",
]);

export function isAllowedDeviceAction(value: unknown): value is NovaDeviceAction {
  if (!value || typeof value !== "object") return false;
  const action = value as Record<string, unknown>;
  if (typeof action.type !== "string" || !DEVICE_ACTIONS.has(action.type)) return false;
  if (action.type === "OPEN_APP") {
    return typeof action.packageName === "string" &&
      /^[A-Za-z0-9_.]{3,180}$/.test(action.packageName);
  }
  return typeof action.query === "string" &&
    action.query.trim().length > 0 && action.query.length <= 500;
}

export function classifyIntent(intent: string): RiskLevel {
  return HIGH_RISK_INTENTS.has(intent) ? "HIGH" : "LOW";
}

export function requiresConfirmation(risk: RiskLevel): boolean {
  return risk !== "LOW";
}
