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
    version="2.1.0"
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

# Ultra-compact, token-optimized system prompts (< 50 tokens each vs previous 450+ tokens)
# Specifically tuned for authentic human writing without AI em-dashes (—)
SYSTEM_PROMPTS = {
    "simple": (
        "You are Keyflow. Rewrite rough text, typos, or Indian languages (Hindi, Hinglish, etc.) "
        "into clean, warm, natural conversational English like a real person texting a friend. "
        "Sound natural and human. Do NOT use em-dashes (—). Output ONLY the rewritten text."
    ),
    "formal": (
        "You are Keyflow. Rewrite rough text or Indian languages into polite, respectful formal English. "
        "Write like an articulate, polished human, not an AI. Do NOT use em-dashes (—) or robotic jargon. "
        "Output ONLY the rewritten text."
    ),
    "professional": (
        "You are Keyflow. Rewrite rough text or Indian languages into crisp, confident workplace English. "
        "Sound like an authentic business professional, not an AI. Do NOT use em-dashes (—). "
        "Output ONLY the rewritten text."
    ),
    "email": (
        "You are Keyflow. Convert rough text into a clean, human-written professional email without em-dashes (—). "
        "Format strictly as:\n"
        "Subject: <Subject>\n\n"
        "Dear <Name>,\n\n"
        "<Body>\n\n"
        "Best regards,\n"
        "[Your Name]\n"
        "Output ONLY the email text."
    )
}

def get_system_prompt(tone: str) -> str:
    norm = (tone or "simple").lower().strip()
    return SYSTEM_PROMPTS.get(norm, SYSTEM_PROMPTS["simple"])

def clean_output(raw_text: str, is_email: bool = False) -> str:
    """Strips thinking scratchpads, outer quotes, prefixes, and markdown blocks, ensuring zero em-dashes."""
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

    # 5. Eliminate artificial AI em-dashes and replace with natural human punctuation
    text = text.replace("—", ", ").replace(" – ", ", ").replace(" -- ", ", ")
    text = re.sub(r"(\s*,\s*)+", ", ", text)

    return text.strip()

async def rewrite_with_groq(text: str, tone: str, api_key: str) -> Optional[str]:
    """Calls Groq API with ultra-fast LPU inference (qwen/qwen3.8-27b). Token-optimized."""
    is_email = (tone or "").lower().strip() == "email"
    sys_prompt = get_system_prompt(tone)
    url = "https://api.groq.com/openai/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }

    models_to_try = ["qwen/qwen3.8-27b", "groq/compound-mini"]

    async with httpx.AsyncClient(timeout=4.0) as client:
        for model in models_to_try:
            payload = {
                "model": model,
                "messages": [
                    {"role": "system", "content": sys_prompt},
                    {"role": "user", "content": text}
                ],
                "temperature": 0.25,
                "max_tokens": 350 if is_email else 180
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
    """Calls Google Gemini API as fallback (gemini-3.5-flash-lite / gemini-3.6-flash). Token-optimized."""
    is_email = (tone or "").lower().strip() == "email"
    sys_prompt = get_system_prompt(tone)
    full_prompt = f"{sys_prompt}\n\nInput text: {text}\nOutput:"
    candidate_models = ["gemini-3.5-flash-lite", "gemini-3.6-flash"]

    # 1. Try google-genai SDK
    try:
        from google import genai
        from google.genai import types

        client = genai.Client(api_key=api_key)
        for model_name in candidate_models:
            try:
                config = types.GenerateContentConfig(
                    max_output_tokens=400 if is_email else 200,
                    temperature=0.25,
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
                "generationConfig": {"maxOutputTokens": 400 if is_email else 200}
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
            "Subject: Follow-up Regarding Our Conversation\n\n"
            "Dear Colleague,\n\n"
            f"I am writing to follow up regarding: {text.strip()}\n\n"
            "Please let me know if you have any questions.\n\n"
            "Best regards,\n"
            "Keyflow User"
        )

    if "kya scene" in lower or "are you coming" in lower:
        return "What's the plan? Are you coming today?"
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
        "version": "2.1.0",
        "status": "online",
        "groq_configured": bool(os.environ.get("GROQ_API_KEY")),
        "gemini_configured": bool(os.environ.get("GEMINI_API_KEY")),
        "supported_tones": list(SYSTEM_PROMPTS.keys())
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
    # Priority 1: Groq LPU (Ultra-Fast ~0.5s)
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
