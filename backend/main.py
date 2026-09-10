import os
import re
import logging
from typing import Optional
from fastapi import FastAPI, HTTPException, UploadFile, File, Form, Request
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
    """Loads environment variables from .env and .env.voice files if present."""
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

    # Dedicated loader for Voice-to-Text key (.env.voice)
    voice_candidates = [
        os.path.join(base_dir, ".env.voice"),
        os.path.join(os.getcwd(), ".env.voice"),
        os.path.join(base_dir, "..", ".env.voice"),
    ]
    for v_path in voice_candidates:
        if os.path.isfile(v_path):
            try:
                with open(v_path, "r", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#") and "=" in line:
                            key, val = line.split("=", 1)
                            key = key.strip()
                            val = val.strip().strip("'\"")
                            if key and key not in os.environ:
                                os.environ[key] = val
                logger.info("Loaded voice environment variables from %s", v_path)
                break
            except Exception as e:
                logger.warning("Could not read %s: %s", v_path, e)

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

class TranscribeResponse(BaseModel):
    transcribed_text: str
    rewritten_text: str
    tone: Optional[str] = "simple"
    provider: Optional[str] = "whisper-large-v3-turbo"
    rewrite_provider: Optional[str] = "groq"

# Ultra-compact, token-optimized system prompts with strict factual & Hinglish fidelity
# Preserves 100% of facts, times, numbers, locations, and user intent without hallucinations or AI em-dashes (—)
SYSTEM_PROMPTS = {
    "simple": (
        "You are Keyflow. Rewrite rough text, typos, or Indian languages (Hindi, Hinglish, etc.) "
        "into clean, natural conversational English texting style.\n"
        "Strict rules:\n"
        "1. Preserve 100% of original facts, numbers, times, locations, and meaning. Never invent or omit details.\n"
        "2. Accurately translate colloquial Hinglish (e.g. 'bhai rapido me hu 10 min me aa raha hu' -> 'Hey, I\\'m on a Rapido and will reach in 10 mins'). Maintain the exact same speaker perspective.\n"
        "3. Keep output concise: match input length (1-2 lines for quick chats). Do NOT use em-dashes (—).\n"
        "Output ONLY the final rewritten text."
    ),
    "formal": (
        "You are Keyflow. Rewrite rough text or Indian languages (Hindi, Hinglish, etc.) into polite, respectful formal English.\n"
        "Strict rules:\n"
        "1. Preserve 100% of original facts, numbers, times, locations, and meaning.\n"
        "2. Write with articulate, dignified courtesy without robotic jargon.\n"
        "3. Do NOT use em-dashes (—). Output ONLY the rewritten text."
    ),
    "professional": (
        "You are Keyflow. Rewrite rough text or Indian languages (Hindi, Hinglish, etc.) into crisp, confident workplace English.\n"
        "Strict rules:\n"
        "1. Preserve 100% of original facts, numbers, times, and meaning with precision.\n"
        "2. Sound like an authentic business communicator: direct, polished, action-oriented.\n"
        "3. Do NOT use em-dashes (—). Output ONLY the rewritten text."
    ),
    "email": (
        "You are Keyflow. Convert rough text or Indian languages (Hindi, Hinglish, etc.) into a clean, complete professional email without em-dashes (—).\n"
        "Preserve all facts, dates, times, and requests accurately.\n"
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

async def transcribe_audio_groq(audio_bytes: bytes, filename: str, api_key: str) -> Optional[str]:
    """Transcribes audio using Groq's whisper-large-v3-turbo model with specialized Hinglish prompt."""
    url = "https://api.groq.com/openai/v1/audio/transcriptions"
    headers = {
        "Authorization": f"Bearer {api_key}"
    }
    
    # Determine MIME type based on extension
    ext = os.path.splitext(filename or "")[1].lower()
    mime_map = {
        ".m4a": "audio/m4a",
        ".mp3": "audio/mpeg",
        ".wav": "audio/wav",
        ".ogg": "audio/ogg",
        ".mp4": "audio/mp4",
        ".webm": "audio/webm",
        ".aac": "audio/aac"
    }
    mime = mime_map.get(ext, "audio/m4a")
    safe_name = filename if filename else "recording.m4a"

    files = {
        "file": (safe_name, audio_bytes, mime)
    }
    data = {
        "model": "whisper-large-v3-turbo",
        "prompt": "Keyflow transcription: English, Hindi, and Hinglish. Casual conversational phrases, numbers, times, locations, and accurate phonetic spelling.",
        "response_format": "json",
        "temperature": "0.0"
    }

    async with httpx.AsyncClient(timeout=15.0) as client:
        try:
            res = await client.post(url, headers=headers, files=files, data=data)
            if res.status_code == 200:
                result_json = res.json()
                text = result_json.get("text", "").strip()
                logger.info("Groq Whisper transcription success (%d bytes): '%s'", len(audio_bytes), text[:80])
                return text
            else:
                logger.error("Groq Whisper API returned HTTP %d: %s", res.status_code, res.text[:200])
        except Exception as e:
            logger.error("Groq Whisper API call failed: %s", e)
    return None

async def perform_rewrite_pipeline(input_text: str, tone: str) -> tuple[str, str]:
    """Executes multi-model cascade (Groq -> Gemini -> local fallback). Returns (rewritten_text, provider)."""
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

    return result, provider

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
        "version": "2.2.0",
        "status": "online",
        "groq_configured": bool(os.environ.get("GROQ_API_KEY")),
        "groq_audio_configured": bool(os.environ.get("GROQ_AUDIO_API_KEY")),
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

    result, provider = await perform_rewrite_pipeline(input_text, tone)
    logger.info("Rewritten result [%s | %s]: '%s'", provider, tone, result[:60])
    return RewriteResponse(rewritten_text=result, provider=provider, tone=tone)

@app.post("/transcribe", response_model=TranscribeResponse)
@app.post("/transcribe/", response_model=TranscribeResponse)
@app.post("/api/transcribe", response_model=TranscribeResponse)
@app.post("/api/transcribe/", response_model=TranscribeResponse)
async def transcribe_audio(
    request: Request,
    file: Optional[UploadFile] = File(None),
    tone: Optional[str] = Form(None)
):
    """Transcribes user voice with Groq whisper-large-v3-turbo, then rewrites into selected tone."""
    audio_key = os.environ.get("GROQ_AUDIO_API_KEY")
    if not audio_key:
        raise HTTPException(status_code=500, detail="GROQ_AUDIO_API_KEY is not configured on server")

    # Read audio bytes from multipart form file or directly from request body
    audio_bytes: bytes = b""
    filename: str = "recording.m4a"

    if file is not None:
        audio_bytes = await file.read()
        if file.filename:
            filename = file.filename
    else:
        # Check raw body
        body = await request.body()
        if body:
            audio_bytes = body
            # Check for header filename
            content_disposition = request.headers.get("content-disposition", "")
            if "filename=" in content_disposition:
                filename = content_disposition.split("filename=")[1].strip("'\"")

    # Resolve tone: from form, query params, or header
    selected_tone = tone or request.query_params.get("tone") or request.headers.get("x-tone") or "simple"
    selected_tone = selected_tone.lower().strip()

    if not audio_bytes:
        raise HTTPException(status_code=400, detail="No audio file or data provided in request")

    logger.info("Received audio for transcription (%d bytes, filename=%s, tone=%s)", len(audio_bytes), filename, selected_tone)

    # 1. Transcribe with Groq whisper-large-v3-turbo
    transcribed_text = await transcribe_audio_groq(audio_bytes, filename, audio_key)
    if not transcribed_text:
        raise HTTPException(status_code=502, detail="Failed to transcribe audio with Groq Whisper API")

    logger.info("Transcribed text: '%s'", transcribed_text)

    # 2. Rewrite / polish text into the selected voice tone
    rewritten_text, rewrite_provider = await perform_rewrite_pipeline(transcribed_text, selected_tone)

    return TranscribeResponse(
        transcribed_text=transcribed_text,
        rewritten_text=rewritten_text or transcribed_text,
        tone=selected_tone,
        provider="whisper-large-v3-turbo",
        rewrite_provider=rewrite_provider
    )

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
