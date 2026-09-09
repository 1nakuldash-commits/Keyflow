import os
import re
import logging
from typing import Optional
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
import httpx

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("keyflow-backend")

def load_env_file():
    """Loads environment variables from .env file if present."""
    base_dir = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(base_dir, ".env"),
        os.path.join(os.getcwd(), ".env"),
        os.path.join(base_dir, "..", ".env"),
    ]
    for env_path in candidates:
        if os.path.isfile(env_path):
            try:
                with open(env_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            key, val = line.split("=", 1)
                            key = key.strip()
                            val = val.strip().strip("'\"")
                            if key and key not in os.environ:
                                os.environ[key] = val
                logger.info("Loaded environment variables from %s", env_path)
                break
            except Exception as e:
                logger.warning("Could not read %s: %s", env_path, e)

load_env_file()

app = FastAPI(
    title="Keyflow Rewrite API",
    description="Multi-model AI backend supporting Groq & Gemini with 4 rewrite tones",
    version="2.0.0"
)

# Enable CORS for all origins
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class RewriteRequest(BaseModel):
    text: str
    tone: Optional[str] = "simple"  # "simple", "formal", "professional", "email"

class RewriteResponse(BaseModel):
    rewritten_text: str
    provider: Optional[str] = "groq"
    tone: Optional[str] = "simple"

# Tone-specific prompt configurations
TONE_CONFIGS = {
    "simple": {
        "goal": "Rewrite the text into natural, clean, simple everyday conversational English.",
        "extra_rules": (
            "Keep the phrasing casual, warm, and natural as people normally speak in everyday messaging."
        )
    },
    "formal": {
        "goal": "Rewrite the text into courteous, polite, respectful, and grammatically polished formal English.",
        "extra_rules": (
            "Use respectful phrasing, proper polite honorifics, and meticulous formal grammar."
        )
    },
    "professional": {
        "goal": "Rewrite the text into crisp, confident, concise, and executive corporate workplace English.",
        "extra_rules": (
            "Ensure the output sounds authoritative, clear, and business-ready. Avoid fluff or overly chatty slang."
        )
    },
    "email": {
        "goal": "Format and rewrite the text into a complete, professional email.",
        "extra_rules": (
            "Format the response strictly as follows:\n"
            "Subject: <Concise, relevant subject line>\n\n"
            "Dear <Recipient/Team>,\n\n"
            "<Opening sentence clearly stating purpose>\n\n"
            "<Main body paragraph with necessary details>\n\n"
            "Best regards,\n"
            "[Your Name]"
        )
    }
}

BASE_SYSTEM_PROMPT = """You are Keyflow, an expert multilingual AI rewriting engine engineered for mobile keyboards.
The user writes rough English, typos, slang, or Indian languages (Hindi, Hinglish, Tamil, Tanglish, Telugu, Kannada, Bengali, Marathi, etc.).

CORE DIRECTIVES:
1. UNDERSTAND CONTEXT & MEANING: Fully interpret colloquial Indian phrasing, Romanized scripts (e.g. Hinglish "bhai kal meeting me kya discuss karna hai"), and intent. Do NOT translate word-for-word robotically; convey the true human intent into fluent English.
2. FULL LENGTH PRESERVATION: Handle single words, multi-sentence messages, and paragraphs. Never drop or omit thoughts.
3. FIX ALL ERRORS: Automatically correct spelling, typos, and broken grammar.
4. STRICT OUTPUT FORMAT: Output ONLY the rewritten text. Never add explanations, introductory greetings (e.g. 'Here is your rewrite:'), notes, or wrapping quotation marks.

FEW-SHOT CONTEXT EXAMPLES:
Input: bhai kal subah meeting me kya discuss karna hai bata de please
Output (Simple): Please tell me what we need to discuss in tomorrow morning's meeting.

Input: Mughe kuch chize thumhe se jaanna tha.
Output (Simple): I wanted to know a few things from you.

Input: Naan nalaiku varamudiyadhu enaku udambu sari illa
Output (Simple): I won't be able to come tomorrow as I am unwell.

Input: Nenu repu ralenandi konchem work undi
Output (Simple): I will not be able to come tomorrow as I have some work.

Input: Hey bro kaha per rahe gaye im weighting for you
Output (Simple): Hey bro, where are you? I'm waiting for you.
"""

def build_prompt(text: str, tone: str) -> str:
    normalized_tone = (tone or "simple").lower().strip()
    if normalized_tone not in TONE_CONFIGS:
        normalized_tone = "simple"

    config = TONE_CONFIGS[normalized_tone]
    prompt = (
        f"{BASE_SYSTEM_PROMPT}\n"
        f"TONE TARGET: {config['goal']}\n"
        f"SPECIFIC INSTRUCTIONS: {config['extra_rules']}\n\n"
        f"Input: {text}\n"
        f"Output:"
    )
    return prompt

def clean_output(raw_text: str, is_email: bool = False) -> str:
    """Strips thinking scratchpads, outer quotes, prefixes, and markdown blocks."""
    if not raw_text:
        return ""

    text = raw_text.strip()

    # 1. Remove reasoning / thinking tags if model outputs <think>...</think>
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()

    # 2. Strip markdown code block wrappers
    if text.startswith("```") and text.endswith("```"):
        lines = text.splitlines()
        if len(lines) >= 3:
            text = "\n".join(lines[1:-1]).strip()

    # 3. Strip leading conversational headers
    prefixes = [
        "output:", "rewritten text:", "rewritten:", "english:",
        "here is the rewritten text:", "here is the email:", "here is the rewrite:"
    ]
    lower_text = text.lower()
    for prefix in prefixes:
        if lower_text.startswith(prefix):
            text = text[len(prefix):].strip()
            break

    # 4. Strip wrapping quotation marks (unless it's an email with multiple lines)
    if not is_email:
        while (text.startswith('"') and text.endswith('"')) or (text.startswith("'") and text.endswith("'")):
            text = text[1:-1].strip()
        text = text.strip('"\'`')

    return text.strip()

async def rewrite_with_groq(text: str, tone: str, api_key: str) -> Optional[str]:
    """Calls Groq API with ultra-fast LPU inference (qwen/qwen3.8-27b)."""
    is_email = (tone or "").lower().strip() == "email"
    prompt = build_prompt(text, tone)
    url = "https://api.groq.com/openai/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    
    # Models on Groq: qwen/qwen3.8-27b (primary ~0.6s), groq/compound-mini (backup)
    models_to_try = ["qwen/qwen3.8-27b", "groq/compound-mini"]

    async with httpx.AsyncClient(timeout=4.0) as client:
        for model in models_to_try:
            payload = {
                "model": model,
                "messages": [
                    {"role": "user", "content": prompt}
                ],
                "temperature": 0.2,
                "max_tokens": 512 if is_email else 256
            }
            try:
                res = await client.post(url, headers=headers, json=payload)
                if res.status_code == 200:
                    data = res.json()
                    choices = data.get("choices", [])
                    if choices:
                        raw_content = choices[0].get("message", {}).get("content", "")
                        cleaned = clean_output(raw_content, is_email=is_email)
                        if cleaned:
                            logger.info("Successfully rewritten via Groq [%s]: '%s'", model, cleaned[:60])
                            return cleaned
                else:
                    logger.warning("Groq model %s returned HTTP %s: %s", model, res.status_code, res.text[:200])
            except Exception as e:
                logger.warning("Groq model %s failed: %s", model, e)

    return None

async def rewrite_with_gemini(text: str, tone: str, api_key: str) -> Optional[str]:
    """Calls Google Gemini API as fallback (gemini-3.5-flash-lite / gemini-3.6-flash)."""
    is_email = (tone or "").lower().strip() == "email"
    full_prompt = build_prompt(text, tone)
    candidate_models = ["gemini-3.5-flash-lite", "gemini-3.6-flash"]

    # 1. Try google-genai SDK
    try:
        from google import genai
        from google.genai import types

        client = genai.Client(api_key=api_key)
        for model_name in candidate_models:
            try:
                config = types.GenerateContentConfig(
                    max_output_tokens=768 if is_email else 256,
                    temperature=0.2,
                )
                response = client.models.generate_content(
                    model=model_name,
                    contents=full_prompt,
                    config=config,
                )
                # Parse candidates parts
                candidates = getattr(response, "candidates", []) or []
                for candidate in candidates:
                    content = getattr(candidate, "content", None)
                    if content and hasattr(content, "parts"):
                        valid_texts = [
                            getattr(p, "text", "")
                            for p in content.parts
                            if not getattr(p, "thought", False) and getattr(p, "text", "")
                        ]
                        if valid_texts:
                            combined = "\n".join(valid_texts) if is_email else " ".join(valid_texts)
                            cleaned = clean_output(combined, is_email=is_email)
                            if cleaned:
                                logger.info("Successfully rewritten via Gemini SDK (%s)", model_name)
                                return cleaned

                if hasattr(response, "text") and response.text:
                    cleaned = clean_output(response.text, is_email=is_email)
                    if cleaned:
                        logger.info("Successfully rewritten via Gemini SDK text fallback (%s)", model_name)
                        return cleaned
            except Exception as model_err:
                logger.warning("Gemini SDK model '%s' failed: %s", model_name, model_err)

    except ImportError:
        logger.debug("google-genai SDK not installed, trying REST endpoint")
    except Exception as sdk_err:
        logger.warning("Gemini SDK init error: %s", sdk_err)

    # 2. REST API fallback
    async with httpx.AsyncClient(timeout=10.0) as client:
        for model_name in candidate_models:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent?key={api_key}"
            payload = {
                "contents": [{"parts": [{"text": full_prompt}]}],
                "generationConfig": {"maxOutputTokens": 768 if is_email else 256}
            }
            try:
                res = await client.post(url, json=payload)
                if res.status_code == 200:
                    data = res.json()
                    candidates = data.get("candidates", [])
                    if candidates:
                        parts = candidates[0].get("content", {}).get("parts", [])
                        valid_chunks = [
                            p.get("text", "")
                            for p in parts
                            if not p.get("thought", False) and p.get("text")
                        ]
                        combined = "\n".join(valid_chunks) if is_email else " ".join(valid_chunks)
                        cleaned = clean_output(combined, is_email=is_email)
                        if cleaned:
                            logger.info("Successfully rewritten via Gemini REST (%s)", model_name)
                            return cleaned
            except Exception as e:
                logger.warning("Gemini REST model %s failed: %s", model_name, e)

    return None

def mock_local_rewrite(text: str, tone: str) -> str:
    """Offline rule-based fallback for immediate testing without internet/keys."""
    lower = text.lower()
    norm_tone = (tone or "simple").lower().strip()

    if norm_tone == "email":
        return (
            "Subject: Update Regarding Our Conversation\n\n"
            "Dear Colleague,\n\n"
            f"I am writing to follow up regarding: {text.strip()}\n\n"
            "Please let me know if you have any questions.\n\n"
            "Best regards,\n"
            "Keyflow User"
        )

    if "kya scene" in lower or "are you coming" in lower:
        return "What is the plan? Are you coming today?"
    if "kal meeting" in lower:
        return "What time is the meeting tomorrow?"
    if "kaise ho" in lower:
        return "How are you doing?"

    # Basic capitalization & punctuation
    cleaned = text[0].upper() + text[1:] if text else ""
    if not cleaned.endswith((".", "?", "!")):
        cleaned += "."
    return cleaned

@app.get("/")
@app.get("/api")
@app.get("/api/")
@app.get("/rewrite")
@app.get("/rewrite/")
@app.get("/api/rewrite")
@app.get("/api/rewrite/")
async def root():
    return {
        "service": "Keyflow Rewrite API",
        "version": "2.0.0",
        "status": "online",
        "groq_configured": bool(os.environ.get("GROQ_API_KEY")),
        "gemini_configured": bool(os.environ.get("GEMINI_API_KEY")),
        "supported_tones": list(TONE_CONFIGS.keys())
    }

@app.post("/")
@app.post("/api")
@app.post("/api/")
@app.post("/rewrite", response_model=RewriteResponse)
@app.post("/rewrite/", response_model=RewriteResponse)
@app.post("/api/rewrite", response_model=RewriteResponse)
@app.post("/api/rewrite/", response_model=RewriteResponse)
async def rewrite_text(req: RewriteRequest):
    input_text = req.text.strip()
    if not input_text:
        raise HTTPException(status_code=400, detail="Input text cannot be empty")

    tone = (req.tone or "simple").lower().strip()
    logger.info("Rewrite request [tone=%s]: '%s'", tone, input_text)

    groq_key = os.environ.get("GROQ_API_KEY")
    gemini_key = os.environ.get("GEMINI_API_KEY")

    result: Optional[str] = None
    provider: str = "offline"

    # Multi-Model Cascade:
    # Priority 1: Groq LPU (Ultra-Fast ~0.6s)
    if groq_key:
        try:
            result = await rewrite_with_groq(input_text, tone, groq_key)
            if result:
                provider = "groq"
        except Exception as e:
            logger.error("Error during Groq rewrite: %s", e)

    # Priority 2: Gemini (Fallback)
    if not result and gemini_key:
        try:
            logger.info("Falling back to Gemini...")
            result = await rewrite_with_gemini(input_text, tone, gemini_key)
            if result:
                provider = "gemini"
        except Exception as e:
            logger.error("Error during Gemini rewrite: %s", e)

    # Priority 3: Offline Mock Fallback
    if not result:
        logger.warning("All LLM providers unavailable. Using local offline fallback.")
        result = mock_local_rewrite(input_text, tone)
        provider = "offline_fallback"

    logger.info("Rewritten result [%s | %s]: '%s'", provider, tone, result[:60])
    return RewriteResponse(rewritten_text=result, provider=provider, tone=tone)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
