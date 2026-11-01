// Supabase Edge Function: bright-action  (the in-app AI Assistant)
// Answers short questions for garage owners (restocking / pricing advice, using their own
// stock data) and for drivers (part identification / vehicle troubleshooting), via Gemini.
//
// Auth:    caller must send a valid Supabase user JWT. The per-user message limit is
//          enforced in the check_and_log_ai_message RPC. The user id passed to it comes
//          from the verified JWT, never from the request body.
// Secrets: GEMINI_API_KEY   (Supabase dashboard -> Edge Functions -> Secrets)
// Optional: GEMINI_MODEL    (defaults to the model below)

import { createClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

const GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
const GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") ?? "gemini-3.6-flash";

// Input limits: keep cost and abuse bounded no matter what a client sends.
const MAX_MESSAGE_CHARS = 4000;
const MAX_IMAGE_CHARS = 2_000_000;
const MAX_HISTORY_ITEMS = 10;
const MAX_HISTORY_TEXT_CHARS = 2000;
const MAX_CONTEXT_CHARS = 6000;

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

const conciseInstruction =
  "Answer in 2-3 short sentences. No preamble, no restating the question, no filler. If you are running low on space, prioritize finishing your current sentence over adding another point — an incomplete answer is worse than a shorter complete one.";

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
    const { message, imageBase64, role, businessContext, history } = body ?? {};

    if (typeof message !== "string" || !message.trim()) {
      return json({ error: "Missing message" }, 400);
    }
    if (message.length > MAX_MESSAGE_CHARS) return json({ error: "Message too long" }, 400);
    if (imageBase64 != null && (typeof imageBase64 !== "string" || imageBase64.length > MAX_IMAGE_CHARS)) {
      return json({ error: "Image too large" }, 400);
    }

    const geminiKey = Deno.env.get("GEMINI_API_KEY");
    if (!geminiKey) {
      console.error("GEMINI_API_KEY is not set");
      return json({ error: "Service is not configured" }, 500);
    }

    const { data: usageCheck, error: usageError } = await userClient.rpc(
      "check_and_log_ai_message",
      { p_user_id: userData.user.id },
    );
    if (usageError) {
      console.error("usage check failed", usageError);
      return json({ error: "Couldn't verify usage limit" }, 500);
    }
    if (!usageCheck?.success) {
      return json({ error: "ai_limit_reached", limit: usageCheck?.limit }, 429);
    }

    const context = typeof businessContext === "string" ? businessContext.slice(0, MAX_CONTEXT_CHARS) : "";

    const systemPrompt = role === "garage_owner"
      ? `You are a business advisor for a spare-parts garage owner using the GarageLink app. Help with restocking decisions, pricing tips, and general business advice. When relevant, refer to the garage's actual data provided below. ${conciseInstruction}
${context ? `\nCurrent garage data:\n${context}` : ""}`
      : `You are a helpful vehicle assistant for a driver using the GarageLink app. Help identify vehicle parts (from descriptions or photos) and give general vehicle troubleshooting advice. ${conciseInstruction} If the issue involves brakes, steering, fuel/gas smell, smoke, burning smell, or any sudden loss of control, clearly tell them to stop driving and get it checked immediately rather than continuing to their destination. For routine issues (AC, noises unrelated to braking/steering, electrical accessories, etc.), just answer directly with no added disclaimer.`;

    const contentParts: any[] = [{ type: "text", text: message }];
    if (imageBase64) {
      contentParts.push({ type: "image_url", image_url: { url: `data:image/jpeg;base64,${imageBase64}` } });
    }

    const historyMessages = Array.isArray(history)
      ? history
          .slice(-MAX_HISTORY_ITEMS)
          .filter((h: any) => h && typeof h.text === "string")
          .map((h: { text: string; isUser: boolean }) => ({
            role: h.isUser ? "user" : "assistant",
            content: h.text.slice(0, MAX_HISTORY_TEXT_CHARS),
          }))
      : [];

    const response = await fetch(GEMINI_URL, {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${geminiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: GEMINI_MODEL,
        max_tokens: 600,
        messages: [
          { role: "system", content: systemPrompt },
          ...historyMessages,
          { role: "user", content: contentParts },
        ],
      }),
    });

    const data = await response.json();

    if (!response.ok) {
      console.error("Gemini error", response.status, JSON.stringify(data));
      const isQuotaExceeded = data?.error?.code === 429 || data?.error?.status === "RESOURCE_EXHAUSTED";
      const friendlyMessage = isQuotaExceeded
        ? "The AI Assistant is temporarily unavailable due to high usage across the app. Please try again later."
        : "The AI Assistant couldn't process that. Please try again.";
      return json({ error: friendlyMessage }, 502);
    }

    const replyText = data.choices?.[0]?.message?.content ?? "Sorry, I couldn't generate a response.";
    return json({ reply: replyText });
  } catch (e) {
    console.error("bright-action failed", e);
    return json({ error: "Something went wrong. Please try again." }, 500);
  }
});
