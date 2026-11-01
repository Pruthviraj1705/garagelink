// Supabase Edge Function: scan-sale-bill
// Reads a photo of a bill the garage gave to a customer (parts sold) with Gemini and
// returns the line items as JSON, so the app can deduct them from inventory.
//
// Auth:    caller must send a valid Supabase user JWT. Garage ownership and the monthly
//          quota are enforced inside the check_and_log_sale_scan RPC, which runs as the
//          verified user (auth.uid()).
// Secrets: GEMINI_API_KEY   (Supabase dashboard -> Edge Functions -> Secrets)
// Optional: GEMINI_MODEL    (defaults to the model below)

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
const GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemini-3.6-flash";
const MAX_IMAGE_CHARS = 2_000_000;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "content-type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });

  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) return json({ error: "Missing authorization" }, 401);

    const userClient = createClient(
      Deno.env.get("SUPABASE_URL")!,
      Deno.env.get("SUPABASE_ANON_KEY")!,
      { global: { headers: { Authorization: authHeader } } },
    );

    const { data: userData, error: userError } = await userClient.auth.getUser();
    if (userError || !userData?.user) return json({ error: "Not authenticated" }, 401);

    let body: any;
    try {
      body = await req.json();
    } catch {
      return json({ error: "Invalid request body" }, 400);
    }
    const { garageId, imageBase64 } = body ?? {};
    if (typeof garageId !== "string" || !garageId || typeof imageBase64 !== "string" || !imageBase64) {
      return json({ error: "Missing image or garageId" }, 400);
    }
    if (imageBase64.length > MAX_IMAGE_CHARS) return json({ error: "Image too large" }, 400);

    const geminiKey = Deno.env.get("GEMINI_API_KEY");
    if (!geminiKey) {
      console.error("GEMINI_API_KEY is not set");
      return json({ error: "Service is not configured" }, 500);
    }

    // Quota + ownership check, enforced by check_and_log_sale_scan (auth.uid()-based).
    // Note: the scan is counted before Gemini is called.
    const { data: usageCheck, error: usageError } = await userClient.rpc(
      "check_and_log_sale_scan",
      { p_garage_id: garageId },
    );
    if (usageError) {
      console.error("usage check failed", usageError);
      return json({ error: "Couldn't verify usage limit" }, 500);
    }
    if (!usageCheck?.success) {
      return json({ error: "scan_limit_reached", limit: usageCheck?.limit }, 429);
    }

    const prompt = `Extract all line items from this sales bill / receipt image - this is a bill a garage gave to a customer for parts sold, not a supplier invoice. Ignore headers/footers/totals/tax lines/customer details.
Respond with a JSON object with a single key "items", containing an array of objects shaped like:
{"name": "product name", "quantity": 1, "price": 123.45}`;

    const response = await fetch(GEMINI_URL, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${geminiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: GEMINI_MODEL,
        response_format: { type: "json_object" },
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              { type: "image_url", image_url: { url: `data:image/jpeg;base64,${imageBase64}` } },
            ],
          },
        ],
      }),
    });

    const data = await response.json();

    if (!response.ok) {
      // Log the provider's error server-side; never forward it to the client.
      console.error("Gemini error", response.status, JSON.stringify(data));
      return json({ error: "Couldn't read the bill. Please try again." }, 502);
    }

    const rawText = data.choices?.[0]?.message?.content ?? '{"items":[]}';
    return new Response(rawText, {
      headers: { ...corsHeaders, "content-type": "application/json" },
    });
  } catch (e) {
    console.error("scan-sale-bill failed", e);
    return json({ error: "Something went wrong. Please try again." }, 500);
  }
});
