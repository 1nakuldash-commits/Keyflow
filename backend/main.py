import os
import re
import time
import logging
from typing import Optional
from fastapi import FastAPI, HTTPException, UploadFile, File, Form, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel
import httpx

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("keyflow-backend")

def load_env_file():
    """Loads environment variables from .env and .env.voice files if present (local dev)."""
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

# Load environment files on startup (for local development)
load_env_file()

# Master default Groq key loaded strictly from environment variables (.env / .env.voice / Vercel)
GROQ_DEFAULT_KEY = (
    os.environ.get("GROQ_DEFAULT_KEY") or
    os.environ.get("GROQ_AUDIO_API_KEY") or
    os.environ.get("GROQ_API_KEY") or
    ""
).strip()

# Configurable Model Cascades (overridable via environment variables)
# Uses high-throughput instruct models on Groq (strictly avoiding reasoning models that dump <think> tags)
DEFAULT_REWRITE_MODELS = [
    m.strip() for m in os.environ.get("REWRITE_MODELS", "qwen/qwen3.8-27b,openai/gpt-oss-120b,openai/gpt-oss-20b,groq/compound-mini").split(",") if m.strip()
]
DEFAULT_ROMANIZATION_MODELS = [
    m.strip() for m in os.environ.get("ROMANIZATION_MODELS", "qwen/qwen3.8-27b,openai/gpt-oss-120b,openai/gpt-oss-20b,groq/compound-mini").split(",") if m.strip()
]

app = FastAPI(
    title="Keyflow Rewrite & Voice API",
    description="Multi-model AI backend supporting J-Mode Intelligent Routing (Raw, Normal, Professional)",
    version="3.0.0"
)

# Enable CORS for all origins
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    """Sanitizes binary data from validation errors to completely prevent UnicodeDecodeError 500s."""
    clean_errors = []
    for err in exc.errors():
        err_dict = dict(err)
        if "input" in err_dict:
            raw_val = err_dict["input"]
            if isinstance(raw_val, bytes):
                err_dict["input"] = f"<binary data: {len(raw_val)} bytes>"
            elif not isinstance(raw_val, (str, int, float, bool, list, dict, type(None))):
                err_dict["input"] = str(raw_val)
        clean_errors.append(err_dict)
    logger.warning("Request validation error on %s: %s", request.url.path, clean_errors)
    return JSONResponse(
        status_code=422,
        content={"detail": clean_errors}
    )

class RewriteRequest(BaseModel):
    text: str
    tone: Optional[str] = "normal"  # "raw", "normal", "professional", "email"

class RewriteResponse(BaseModel):
    rewritten_text: str
    provider: Optional[str] = "groq"
    tone: Optional[str] = "normal"

class TranscribeResponse(BaseModel):
    transcribed_text: str
    rewritten_text: str
    tone: Optional[str] = "normal"
    provider: Optional[str] = "whisper-large-v3"
    rewrite_provider: Optional[str] = "groq"
    script_normalized: Optional[bool] = False
    normalization_latency_ms: Optional[float] = 0.0

# =========================================================================
# J-Mode Refinement Prompts (Prototype 3 Spec)
# Unified across typed text and voice transcription
# =========================================================================
SYSTEM_PROMPTS = {
    "raw": (
        "You are Keyflow Raw Mode. Your task is MINIMAL-EDIT text refinement.\n"
        "STRICT RULES:\n"
        "1. Fix only obvious typos, spelling mistakes, capitalization, punctuation, spacing, accidental duplicate words, and obvious speech disfluencies (um, uh, false starts).\n"
        "2. CRITICAL: DO NOT translate. If the input is written or transcribed in Hinglish or Hindi, KEEP IT IN HINGLISH/HINDI. Never convert it into English.\n"
        "3. DO NOT rewrite, restructure, paraphrase, polish, or make it sound professional.\n"
        "4. DO NOT add, remove, or infer any information. The output must have a minimal edit distance from the input.\n"
        "5. Preserve the exact original wording, language, tone, personality, and sentence structure.\n"
        "6. Output ONLY the refined text. Never output meta-commentary, explanations, or quotation marks."
    ),
    "normal": (
        "You are Keyflow Normal Mode. Your task: Understand what the user is trying to say and express the exact same thing in simple, natural, fluent, human English (everyday conversational texting style, like chatting on WhatsApp or Slack).\n"
        "STRICT RULES:\n"
        "1. If the input is in broken English, Hinglish, or Hindi mixed with English, convert it into clear, natural, everyday conversational English.\n"
        "2. DO ONLY WHAT THE USER SAID. Do not add ideas, explanations, advice, suggestions, or unnecessary detail.\n"
        "3. CRITICAL NEGATIVE CONSTRAINT: Never invent abbreviations, acronyms, or corporate jargon (e.g. NEVER output 'CS', 'suboptimal', 'necessitating comprehensive implementation improvements'). Never use unnecessarily sophisticated vocabulary.\n"
        "4. If the user asks for X, output a natural version of X, not a larger or better version of X.\n"
        "5. Preserve 100% of facts, numbers, dates, times, names, technical terms, requested actions, negations, and intent.\n"
        "6. Sound like a real person texting naturally. Do NOT use em-dashes (—).\n"
        "7. Output ONLY the final refined English text. Never output meta-commentary, apologies, or quotation marks."
    ),
    "professional": (
        "You are Keyflow Professional Mode. Your task: Express the user's exact message in properly structured, polished, and polite professional workplace English suitable for Slack, Teams, email, or colleagues.\n"
        "STRICT RULES:\n"
        "1. If input is in Hinglish, Hindi, or broken English, convert it into articulate, direct, and respectful workplace English.\n"
        "2. Preserve 100% of facts, dates, times, numbers, names, technical terms, requested actions, negations, and intent precisely.\n"
        "3. CRITICAL: NEVER invent information, context, acronyms, or corporate fluff not present in the user's message.\n"
        "4. Keep it concise, structured, and clear. Do not turn a simple message into an unnecessarily long message. Do NOT use em-dashes (—).\n"
        "5. Output ONLY the final polished English text. Never output meta-commentary, apologies, or quotation marks."
    ),
    "email": (
        "You are Keyflow. The user input was spoken or typed in English, Hindi, or Hinglish.\n"
        "Your task: Convert and structure it into a clean, complete professional email without em-dashes (—).\n"
        "Preserve all facts, dates, times, requests, and context accurately. NEVER invent facts or acronyms not spoken by the user.\n"
        "Format strictly as:\n"
        "Subject: <Subject>\n\n"
        "Dear <Name>,\n\n"
        "<Body>\n\n"
        "Best regards,\n"
        "[Your Name]\n"
        "Output ONLY the email text. Never output apologies or meta-commentary."
    )
}

def get_system_prompt(tone: str) -> str:
    norm = (tone or "normal").lower().strip()
    # Map backward compatible aliases
    if norm in ("simple", "normal", "default"):
        norm = "normal"
    elif norm in ("formal", "professional"):
        norm = "professional"
    return SYSTEM_PROMPTS.get(norm, SYSTEM_PROMPTS["normal"])

def clean_output(raw_text: str, is_email: bool = False) -> str:
    """Strips thinking scratchpads, outer quotes, prefixes, and markdown blocks, ensuring zero em-dashes."""
    if not raw_text:
        return ""

    text = raw_text.strip()

    # 1. Remove reasoning / thinking tags (including unclosed tags when tokens run out)
    text = re.sub(r"<think>.*?(?:</think>|$)", "", text, flags=re.DOTALL).strip()

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

def contains_indic_or_perso_arabic(text: str) -> bool:
    """Detects presence of Indic (Devanagari, Gurmukhi, etc.) or Perso-Arabic (Urdu) characters."""
    if not text:
        return False
    # Unicode ranges: Devanagari (\u0900-\u097F), Arabic/Urdu (\u0600-\u06FF)
    return bool(re.search(r"[\u0900-\u097F\u0600-\u06FF]", text))

ROMANIZATION_SYSTEM_PROMPT = (
    "You are an expert Hindi/Urdu to Romanized Hinglish transliterator.\n"
    "Your task: Convert any Hindi or Urdu script in the input text into Romanized Hinglish (Latin alphabet / English letters).\n"
    "STRICT RULES:\n"
    "1. DO NOT translate into English. Keep the exact Hindi words, pronunciation, and meaning, but write them phonetically in English letters (e.g. 'मुझे कल जाना है' -> 'Mujhe kal jaana hai', 'क्या सीन है' -> 'Kya scene hai').\n"
    "2. Keep existing English words, technical terms, names, dates, and numbers exactly as they are.\n"
    "3. Do not add, omit, or alter any words. This is strictly a script representation normalizer.\n"
    "4. Output ONLY the Romanized text. No explanation, no quotes."
)

async def romanize_indic_text(text: str, api_key: str) -> tuple[str, float]:
    """Transliterates Indic/Urdu script into phonetic Romanized Hinglish (Latin alphabet).
    Configurable model cascade (Groq -> Gemini fallback) with measured latency in milliseconds."""
    clean_key = (api_key or "").strip()
    if not text:
        return text, 0.0

    start_time = time.perf_counter()

    # Tier 1: Groq fast LPU transliteration
    if clean_key:
        url = "https://api.groq.com/openai/v1/chat/completions"
        headers = {
            "Authorization": f"Bearer {clean_key}",
            "Content-Type": "application/json",
            "User-Agent": "Keyflow/1.0"
        }
        async with httpx.AsyncClient(timeout=6.0) as client:
            for model in DEFAULT_ROMANIZATION_MODELS:
                payload = {
                    "model": model,
                    "messages": [
                        {"role": "system", "content": ROMANIZATION_SYSTEM_PROMPT},
                        {"role": "user", "content": text}
                    ],
                    "temperature": 0.0,
                    "max_tokens": 250
                }
                try:
                    res = await client.post(url, headers=headers, json=payload)
                    if res.status_code == 200:
                        data = res.json()
                        choices = data.get("choices", [])
                        if choices:
                            raw = choices[0].get("message", {}).get("content", "")
                            cleaned = clean_output(raw)
                            # Ensure transliteration actually produced Roman characters and removed Indic script
                            if cleaned and not contains_indic_or_perso_arabic(cleaned):
                                latency_ms = (time.perf_counter() - start_time) * 1000.0
                                logger.info("Script normalization via Groq [%s] in %.1fms: '%s'", model, latency_ms, cleaned[:60])
                                return cleaned, latency_ms
                    else:
                        logger.warning("Romanization model %s returned HTTP %d: %s", model, res.status_code, res.text[:150])
                except Exception as e:
                    logger.warning("Romanization model %s failed: %s", model, e)

    # Tier 2: Gemini Fallback for Script Normalization
    gemini_key = os.environ.get("GEMINI_API_KEY")
    if gemini_key:
        try:
            from google import genai
            from google.genai import types
            client = genai.Client(api_key=gemini_key)
            prompt = f"{ROMANIZATION_SYSTEM_PROMPT}\n\nText: {text}\nOutput:"
            for gemini_model in ["gemini-3.5-flash-lite", "gemini-3.6-flash"]:
                try:
                    response = client.models.generate_content(
                        model=gemini_model,
                        contents=prompt,
                        config=types.GenerateContentConfig(max_output_tokens=250, temperature=0.0)
                    )
                    cleaned = clean_output(response.text or "")
                    if cleaned and not contains_indic_or_perso_arabic(cleaned):
                        latency_ms = (time.perf_counter() - start_time) * 1000.0
                        logger.info("Script normalization via Gemini [%s] in %.1fms: '%s'", gemini_model, latency_ms, cleaned[:60])
                        return cleaned, latency_ms
                except Exception as ge:
                    logger.warning("Gemini model %s script normalization failed: %s", gemini_model, ge)
        except Exception as e:
            logger.warning("Gemini script normalization init error: %s", e)

    latency_ms = (time.perf_counter() - start_time) * 1000.0
    return text, latency_ms

async def rewrite_with_groq(text: str, tone: str, api_key: str) -> Optional[str]:
    """Calls Groq API with ultra-fast LPU inference. Token-optimized with configurable models."""
    clean_key = (api_key or "").strip()
    if not clean_key:
        return None

    is_email = (tone or "").lower().strip() == "email"
    sys_prompt = get_system_prompt(tone)
    url = "https://api.groq.com/openai/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {clean_key}",
        "Content-Type": "application/json",
        "User-Agent": "Keyflow/1.0"
    }

    async with httpx.AsyncClient(timeout=6.0) as client:
        for model in DEFAULT_REWRITE_MODELS:
            payload = {
                "model": model,
                "messages": [
                    {"role": "system", "content": sys_prompt},
                    {"role": "user", "content": text}
                ],
                "temperature": 0.1 if tone == "raw" else 0.2,
                "max_tokens": 450 if is_email else 320
            }
            try:
                res = await client.post(url, headers=headers, json=payload)
                if res.status_code == 200:
                    data = res.json()
                    choices = data.get("choices", [])
                    if choices:
                        finish_reason = choices[0].get("finish_reason", "")
                        if finish_reason == "length":
                            logger.warning("Groq model %s response cut off (finish_reason=length)", model)
                            continue
                        raw_content = choices[0].get("message", {}).get("content", "")
                        cleaned = clean_output(raw_content, is_email=is_email)
                        if cleaned:
                            logger.info("Successfully rewritten via Groq [%s | tone=%s]: '%s'", model, tone, cleaned[:60])
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

# Known Whisper silence hallucinations produced when audio lacks clear speech energy
WHISPER_SILENCE_HALLUCINATIONS = {
    "thank you for watching",
    "thank you for watching.",
    "thank you for watching!",
    "thanks for watching",
    "thanks for watching.",
    "thanks for watching!",
    "thank you",
    "thank you.",
    "thank you!",
    "please subscribe",
    "please subscribe.",
    "please subscribe!",
    "subscribe to my channel",
    "subtitles by",
    "you",
    "you.",
    "bye",
    "bye.",
    ".",
    "...",
    "!"
}

def is_silence_hallucination(text: str) -> bool:
    """Detects whether Whisper text is a known silence/outro hallucination."""
    if not text:
        return True
    cleaned = text.lower().strip().rstrip(".!? \t\n")
    if not cleaned or cleaned in WHISPER_SILENCE_HALLUCINATIONS or text.strip() in WHISPER_SILENCE_HALLUCINATIONS:
        return True
    clean_no_punct = re.sub(r"[^\w\s]", "", cleaned).strip()
    return clean_no_punct in {
        "thank you for watching",
        "thanks for watching",
        "thank you",
        "please subscribe",
        "subscribe to my channel",
        "subtitles by",
        "you",
        "bye",
        ""
    }

async def transcribe_audio_groq(audio_bytes: bytes, filename: str, api_key: str) -> tuple[str, bool, float]:
    """Transcribes audio accurately using Groq's whisper-large-v3 / whisper-large-v3-turbo via /audio/transcriptions.
    Biased with a Romanized Hinglish dictionary prompt.
    Applies script normalization if Indic/Urdu characters are detected in the transcript.
    Returns (transcribed_text, script_normalized, normalization_latency_ms)."""
    clean_key = (api_key or "").strip()
    if not clean_key:
        return "", False, 0.0

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

    # Priming prompt biased toward Latin alphabet (Romanized Hinglish)
    prompt = (
        "Keyflow dictation in English and Romanized Hinglish: mujhe kal office jana hai, "
        "client ko call karna hai, meeting kitne baje hai, bhai main 10 min me aa raha hu, aap kahan ho, "
        "kya scene hai, please check the UI bug, screen, buttons, settings, app update kar lena."
    )

    headers = {
        "Authorization": f"Bearer {clean_key}",
        "User-Agent": "Keyflow/1.0"
    }

    raw_text = ""
    transcription_url = "https://api.groq.com/openai/v1/audio/transcriptions"

    async with httpx.AsyncClient(timeout=18.0) as client:
        for model_name in ["whisper-large-v3", "whisper-large-v3-turbo"]:
            files = {"file": (safe_name, audio_bytes, mime)}
            data = {
                "model": model_name,
                "prompt": prompt,
                "response_format": "json",
                "temperature": "0.0"
            }
            try:
                res = await client.post(transcription_url, headers=headers, files=files, data=data)
                if res.status_code == 200:
                    text = res.json().get("text", "").strip()
                    if text and not is_silence_hallucination(text):
                        logger.info("Groq Whisper Transcription [%s] success (%d bytes): '%s'", model_name, len(audio_bytes), text[:80])
                        raw_text = text
                        break
                    elif is_silence_hallucination(text):
                        logger.warning("Groq Whisper Transcription [%s] detected silence hallucination: '%s'", model_name, text)
                        return "", False, 0.0
                else:
                    logger.warning("Groq Whisper Transcription [%s] returned HTTP %d: %s", model_name, res.status_code, res.text[:200])
            except Exception as e:
                logger.warning("Groq Whisper Transcription [%s] failed: %s", model_name, e)

    if not raw_text:
        return "", False, 0.0

    # Script Normalization Step:
    # If the text contains Indic (Devanagari, etc.) or Perso-Arabic (Urdu) characters,
    # normalize to Roman characters (Latin alphabet) while strictly preserving the Hindi words and phonetics.
    script_normalized = False
    norm_latency_ms = 0.0
    final_text = raw_text

    if contains_indic_or_perso_arabic(raw_text):
        logger.info("Non-Roman script detected in ASR output: '%s'. Initiating script normalization...", raw_text[:60])
        final_text, norm_latency_ms = await romanize_indic_text(raw_text, clean_key)
        script_normalized = True
        logger.info("Script normalization complete (%.1fms): '%s'", norm_latency_ms, final_text[:60])

    return final_text, script_normalized, norm_latency_ms

async def perform_rewrite_pipeline(input_text: str, tone: str) -> tuple[str, str]:
    """Executes multi-model cascade (Groq -> Gemini -> local fallback). Returns (rewritten_text, provider)."""
    groq_key = os.environ.get("GROQ_API_KEY") or os.environ.get("GROQ_AUDIO_API_KEY") or GROQ_DEFAULT_KEY
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

async def execute_rewrite(input_text: str, tone: str) -> RewriteResponse:
    text = (input_text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="Input text cannot be empty")

    norm_tone = (tone or "normal").lower().strip()
    if norm_tone in ("simple", "default"):
        norm_tone = "normal"
    elif norm_tone == "formal":
        norm_tone = "professional"

    logger.info("Execute rewrite [tone=%s]: '%s'", norm_tone, text[:80])

    result, provider = await perform_rewrite_pipeline(text, norm_tone)
    logger.info("Rewritten result [%s | %s]: '%s'", provider, norm_tone, result[:80])
    return RewriteResponse(rewritten_text=result, provider=provider, tone=norm_tone)

async def execute_transcribe(
    audio_bytes: bytes,
    filename: str,
    selected_tone: str
) -> TranscribeResponse:
    if not audio_bytes or len(audio_bytes) < 400:
        raise HTTPException(status_code=400, detail="Audio file too short or empty")

    audio_key = os.environ.get("GROQ_AUDIO_API_KEY") or os.environ.get("GROQ_API_KEY") or GROQ_DEFAULT_KEY
    if not audio_key:
        raise HTTPException(status_code=500, detail="GROQ API key is not configured on server")

    norm_tone = (selected_tone or "normal").lower().strip()
    if norm_tone in ("simple", "default"):
        norm_tone = "normal"
    elif norm_tone == "formal":
        norm_tone = "professional"

    safe_name = filename or "recording.m4a"
    logger.info("Execute transcribe (%d bytes, filename=%s, tone=%s)", len(audio_bytes), safe_name, norm_tone)

    # 1. Transcribe speech accurately via Whisper (/audio/transcriptions) & normalize script
    transcribed_text, script_normalized, norm_latency_ms = await transcribe_audio_groq(audio_bytes, safe_name, audio_key)
    if not transcribed_text:
        logger.info("No valid speech detected in audio file (silence or noise)")
        return TranscribeResponse(
            transcribed_text="",
            rewritten_text="",
            tone=norm_tone,
            provider="whisper-large-v3",
            rewrite_provider="none",
            script_normalized=False,
            normalization_latency_ms=0.0
        )

    logger.info("Transcribed text: '%s'", transcribed_text)

    # 2. Refinement pipeline (Shared 100% with typed text)
    rewritten_text, rewrite_provider = await perform_rewrite_pipeline(transcribed_text, norm_tone)

    return TranscribeResponse(
        transcribed_text=transcribed_text,
        rewritten_text=rewritten_text or transcribed_text,
        tone=norm_tone,
        provider="whisper-large-v3",
        rewrite_provider=rewrite_provider,
        script_normalized=script_normalized,
        normalization_latency_ms=norm_latency_ms
    )

@app.get("/")
@app.get("/api")
@app.get("/api/")
@app.get("/rewrite")
@app.get("/rewrite/")
@app.get("/transcribe")
@app.get("/transcribe/")
@app.get("/api/rewrite")
@app.get("/api/rewrite/")
@app.get("/api/transcribe")
@app.get("/api/transcribe/")
async def root():
    return {
        "service": "Keyflow Rewrite & Voice API",
        "version": "3.0.0",
        "status": "online",
        "groq_configured": bool(os.environ.get("GROQ_API_KEY") or GROQ_DEFAULT_KEY),
        "groq_audio_configured": bool(os.environ.get("GROQ_AUDIO_API_KEY") or GROQ_DEFAULT_KEY),
        "gemini_configured": bool(os.environ.get("GEMINI_API_KEY")),
        "supported_tones": list(SYSTEM_PROMPTS.keys()),
        "default_tone": "normal"
    }

# =========================================================================
# Unified Dispatcher: Handles POST / and POST /api seamlessly on Vercel
# Detects whether the payload is Audio (multipart/form-data or audio/*)
# or Text (application/json) and routes to the appropriate pipeline.
# =========================================================================
@app.post("/")
@app.post("/api")
@app.post("/api/")
async def unified_api_handler(request: Request):
    content_type = request.headers.get("content-type", "").lower()
    matched_path = (request.headers.get("x-matched-path", "") or request.headers.get("x-vercel-matched-path", "")).lower()

    is_audio = (
        "multipart/form-data" in content_type or
        content_type.startswith("audio/") or
        "transcribe" in matched_path or
        request.query_params.get("action") == "transcribe"
    )

    if is_audio:
        audio_bytes = b""
        filename = "recording.m4a"
        tone = request.query_params.get("tone") or request.headers.get("x-tone") or "normal"

        if "multipart/form-data" in content_type:
            try:
                form = await request.form()
                form_file = form.get("file")
                if form_file and hasattr(form_file, "read"):
                    audio_bytes = await form_file.read()
                    filename = getattr(form_file, "filename", "recording.m4a") or "recording.m4a"
                tone = form.get("tone", tone)
            except Exception as e:
                logger.warning("Error reading multipart form in unified handler: %s", e)
                audio_bytes = await request.body()
        else:
            audio_bytes = await request.body()
            disposition = request.headers.get("content-disposition", "")
            if "filename=" in disposition:
                filename = disposition.split("filename=")[1].strip("'\"")

        return await execute_transcribe(audio_bytes, filename, str(tone))

    # Text Rewrite request
    try:
        data = await request.json()
    except Exception as e:
        logger.error("Failed to parse JSON body on %s: %s", request.url.path, e)
        raise HTTPException(status_code=400, detail="Expected JSON payload for text rewrite or multipart/form-data for audio")

    text = data.get("text", "")
    tone = data.get("tone", "normal")
    return await execute_rewrite(text, tone)

# Dedicated Text Rewrite endpoints
@app.post("/rewrite", response_model=RewriteResponse)
@app.post("/rewrite/", response_model=RewriteResponse)
@app.post("/api/rewrite", response_model=RewriteResponse)
@app.post("/api/rewrite/", response_model=RewriteResponse)
async def rewrite_text_endpoint(req: RewriteRequest):
    return await execute_rewrite(req.text, req.tone or "normal")

# Dedicated Voice Transcribe endpoints
@app.post("/transcribe", response_model=TranscribeResponse)
@app.post("/transcribe/", response_model=TranscribeResponse)
@app.post("/api/transcribe", response_model=TranscribeResponse)
@app.post("/api/transcribe/", response_model=TranscribeResponse)
async def transcribe_audio_endpoint(
    request: Request,
    file: Optional[UploadFile] = File(None),
    tone: Optional[str] = Form(None)
):
    audio_bytes: bytes = b""
    filename: str = "recording.m4a"

    if file is not None:
        audio_bytes = await file.read()
        if file.filename:
            filename = file.filename
    else:
        audio_bytes = await request.body()
        content_disposition = request.headers.get("content-disposition", "")
        if "filename=" in content_disposition:
            filename = content_disposition.split("filename=")[1].strip("'\"")

    selected_tone = tone or request.query_params.get("tone") or request.headers.get("x-tone") or "normal"
    return await execute_transcribe(audio_bytes, filename, str(selected_tone))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
