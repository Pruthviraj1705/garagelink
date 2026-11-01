// Supabase Edge Function: send-push
// Triggered by a Database Webhook on INSERT into public.notifications.
// Looks up the recipient's device tokens and sends an FCM (HTTP v1) push.
//
// Auth:    NOT user-facing. The webhook must send the shared secret in the
//          "x-webhook-secret" header. The anon key is public, so this secret is the
//          real gatekeeper. Requests without it are rejected with 401.
// Required secrets: FIREBASE_SERVICE_ACCOUNT (full JSON), PUSH_WEBHOOK_SECRET (random string)
// Default secrets used: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY
//   (the service-role key is read from the function's environment only; never commit it)

import { createClient } from "npm:@supabase/supabase-js@2";
import { importPKCS8, SignJWT } from "npm:jose@5";

const sa = JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "{}");
const WEBHOOK_SECRET = Deno.env.get("PUSH_WEBHOOK_SECRET") ?? "";
const CHANNEL_ID = "garagelink_default";

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
);

let cachedToken: { value: string; exp: number } | null = null;

async function getAccessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 60 > now) return cachedToken.value;

  const key = await importPKCS8(sa.private_key, "RS256");
  const assertion = await new SignJWT({
    scope: "https://www.googleapis.com/auth/firebase.messaging",
  })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(sa.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(key);

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  if (!res.ok) throw new Error(`Google token error ${res.status}: ${await res.text()}`);
  const json = await res.json();
  cachedToken = { value: json.access_token, exp: now + (json.expires_in ?? 3600) };
  return cachedToken.value;
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("method not allowed", { status: 405 });

  // The anon key is public, so this shared secret is the real gatekeeper.
  const provided = req.headers.get("x-webhook-secret") ?? "";
  if (!WEBHOOK_SECRET || !safeEqual(provided, WEBHOOK_SECRET)) {
    return new Response("unauthorized", { status: 401 });
  }

  let payload: any;
  try {
    payload = await req.json();
  } catch {
    return new Response("bad request", { status: 400 });
  }

  if (payload?.type !== "INSERT" || payload?.table !== "notifications") {
    return Response.json({ skipped: true });
  }
  const n = payload.record;
  if (!n?.user_id || !n?.title) return Response.json({ skipped: true });

  const { data: rows, error } = await admin
    .from("device_tokens")
    .select("token")
    .eq("user_id", n.user_id);
  if (error) {
    console.error("token lookup failed", error);
    return new Response("lookup failed", { status: 500 });
  }
  if (!rows || rows.length === 0) return Response.json({ sent: 0, reason: "no tokens" });

  if (!sa.private_key || !sa.client_email || !sa.project_id) {
    console.error("FIREBASE_SERVICE_ACCOUNT secret is missing or incomplete");
    return new Response("push not configured", { status: 500 });
  }

  let accessToken: string;
  try {
    accessToken = await getAccessToken();
  } catch (e) {
    console.error("couldn't get FCM access token", e);
    return new Response("auth to FCM failed", { status: 502 });
  }

  const url = `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`;
  let sent = 0;
  const dead: string[] = [];

  for (const { token } of rows) {
    const res = await fetch(url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${accessToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          notification: { title: String(n.title), body: String(n.body ?? "") },
          data: { type: String(n.type ?? ""), notification_id: String(n.id ?? "") },
          android: { priority: "HIGH", notification: { channel_id: CHANNEL_ID } },
        },
      }),
    });

    if (res.ok) {
      sent++;
    } else {
      const text = await res.text();
      console.error("FCM error", res.status, text);
      // Token no longer valid (app uninstalled / token rotated): clean it up.
      if (res.status === 404 || text.includes("UNREGISTERED")) dead.push(token);
    }
  }

  if (dead.length > 0) {
    await admin.from("device_tokens").delete().in("token", dead);
  }

  return Response.json({ sent, removed: dead.length });
});
