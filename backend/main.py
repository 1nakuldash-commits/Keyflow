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
    description="Backend service for Keyflow Android Accessibility Rewrite Toolbar",
    version="1.0.0"
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

class RewriteResponse(BaseModel):
    rewritten_text: str
    provider: Optional[str] = "gemini"

SYSTEM_INSTRUCTION = (
    "You are an expert bilingual AI text-rewriting engine for a mobile keyboard.\n"
    "The user writes rough English, typos, slang, or Hinglish (Hindi written in Roman/Latin script).\n"
    "Your job is to rewrite the input into clean, natural, fluent, and grammatically correct English.\n\n"
    "STRICT RULES:\n"
    "1. Completely translate all Hinglish/Hindi words into proper English (e.g. 'Mughe kuch chize thumhe se jaanna tha' -> 'I wanted to know a few things from you').\n"
    "2. FULL LENGTH PRESERVATION: Handle short phrases, multi-sentence messages, and long paragraphs (20 to 50+ words). Never drop or truncate any thoughts or sentences.\n"
    "3. Correct all spelling mistakes, typos, and broken phrasing.\n"
    "4. Keep the natural conversational tone (casual for chat, polite for questions).\n"
    "5. Output ONLY the rewritten English text. Never output Hindi words in the result.\n"
    "6. Do NOT add quotation marks, explanations, notes, or prefixes like 'Output:'.\n\n"
    "FEW-SHOT EXAMPLES:\n"
    "Input: Mughe kuch chize thumhe se jaanna tha.\n"
    "Output: I wanted to know a few things from you.\n\n"
    "Input: Hey bro kaha per rahe gaye im weighting for you\n"
    "Output: Hey bro, where are you? I'm waiting for you.\n\n"
    "Input: bhaikab aroge im weatign for you\n"
    "Output: Brother, when will you arrive? I'm waiting for you.\n\n"
    "Input: bhai kal subah meeting me aoge kya? mai soch raha tha ki project ka presentation finalize kar lete hai\n"
    "Output: Bro, will you come to the meeting tomorrow morning? I was thinking we should finalize the project presentation."
)

def clean_output(raw_text: str) -> str:
    """Strips outer quotes, backticks, prefixes, or trailing artifacts."""
    text = raw_text.strip()

    # Strip code block wrappers
    if text.startswith("```") and text.endswith("```"):
        lines = text.splitlines()
        if len(lines) >= 3:
            text = "\n".join(lines[1:-1]).strip()

    # Strip leading 'Output:' or 'Rewritten:'
    lower_text = text.lower()
    for prefix in ["output:", "rewritten text:", "rewritten:", "english:"]:
        if lower_text.startswith(prefix):
            text = text[len(prefix):].strip()
            break

    # Strip wrapping quotes
    while (text.startswith('"') and text.endswith('"')) or (text.startswith("'") and text.endswith("'")):
        text = text[1:-1].strip()

    # Strip any stray leading/trailing quotes
    text = text.strip('"\'`')
    return text.strip()

def extract_text_from_response(response) -> Optional[str]:
    """Safely extracts the final answer text from a google-genai response, strictly filtering thoughts."""
    if not response:
        return None

    # 1. First inspect candidates and their parts, skipping any thought parts
    try:
        candidates = getattr(response, "candidates", []) or []
        for candidate in candidates:
            content = getattr(candidate, "content", None)
            if content and hasattr(content, "parts"):
                valid_texts = []
                for p in content.parts:
                    # Skip internal thought scratchpads
                    if getattr(p, "thought", False):
                        continue
                    part_text = getattr(p, "text", None)
                    if part_text and part_text.strip():
                        valid_texts.append(part_text.strip())
                if valid_texts:
                    combined = " ".join(valid_texts)
                    cleaned = clean_output(combined)
                    if cleaned:
                        return cleaned
    except Exception as e:
        logger.debug("Error parsing candidates parts: %s", e)

    # 2. Fallback to response.text if candidates did not yield parts
    try:
        if hasattr(response, "text") and response.text and response.text.strip():
            cleaned = clean_output(response.text)
            if cleaned:
                return cleaned
    except Exception:
        pass

    return None

async def rewrite_with_gemini(text: str, api_key: str) -> Optional[str]:
    """Calls Gemini API using google-genai SDK or direct REST API."""
    full_prompt = f"{SYSTEM_INSTRUCTION}\n\nRewrite this text into natural, clean English:\n{text}"

    # Verified available models on the account: 3.5-flash-lite (fastest, separate quota), 3.6-flash, 3.1-flash-lite, 3.7-flash
    preferred_model = os.environ.get("GEMINI_MODEL")
    candidate_models = [
        preferred_model,
        "gemini-3.5-flash-lite",
        "gemini-3.6-flash",
        "gemini-3.1-flash-lite",
        "gemini-3.7-flash",
        "gemini-flash-lite-latest",
    ]
    candidate_models = [m for m in candidate_models if m]

    # 1. Try google-genai SDK
    try:
        from google import genai
        from google.genai import types

        client = genai.Client(api_key=api_key)
        for model_name in candidate_models:
            try:
                config = types.GenerateContentConfig(
                    max_output_tokens=1024,
                )
                response = client.models.generate_content(
                    model=model_name,
                    contents=full_prompt,
                    config=config,
                )
                extracted = extract_text_from_response(response)
                if extracted:
                    logger.info("Successfully rewritten via google-genai (%s): '%s'", model_name, extracted)
                    return extracted
            except Exception as e:
                logger.warning("google-genai call failed on '%s': %s", model_name, e)

    except ImportError:
        logger.debug("google-genai SDK not installed, falling back to direct REST call")
    except Exception as e:
        logger.warning("google-genai client initialization failed: %s", e)

    # 2. Direct REST call fallback
    async with httpx.AsyncClient(timeout=30.0) as client:
        for model_name in candidate_models:
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model_name}:generateContent?key={api_key}"
            payload = {
                "contents": [
                    {
                        "parts": [{"text": full_prompt}]
                    }
                ],
                "generationConfig": {
                    "maxOutputTokens": 1024
                }
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
                        combined = " ".join(t.strip() for t in valid_chunks if t.strip())
                        cleaned = clean_output(combined)
                        if cleaned:
                            logger.info("Successfully rewritten via REST (%s): '%s'", model_name, cleaned)
                            return cleaned
                else:
                    logger.warning("Gemini REST model %s returned HTTP %s: %s", model_name, res.status_code, res.text)
            except Exception as net_err:
                logger.warning("Gemini REST network error with model %s: %s", model_name, net_err)

    return None

async def rewrite_with_groq(text: str, api_key: str) -> Optional[str]:
    """Calls Groq API via standard HTTP endpoint."""
    prompt = SYSTEM_PROMPT_TEMPLATE.format(text=text)
    url = "https://api.groq.com/openai/v1/chat/completions"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json"
    }
    payload = {
        "model": "llama-3.3-70b-versatile",
        "messages": [
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.3,
        "max_tokens": 256
    }
    async with httpx.AsyncClient(timeout=15.0) as client:
        res = await client.post(url, headers=headers, json=payload)
        if res.status_code == 200:
            data = res.json()
            choices = data.get("choices", [])
            if choices:
                return clean_output(choices[0].get("message", {}).get("content", ""))
        logger.error("Groq API error: HTTP %s - %s", res.status_code, res.text)
    return None

@app.get("/")
@app.get("/api")
async def root():
    return {
        "service": "Keyflow Rewrite API",
        "status": "online",
        "gemini_configured": bool(os.environ.get("GEMINI_API_KEY")),
        "groq_configured": bool(os.environ.get("GROQ_API_KEY"))
    }

@app.post("/rewrite", response_model=RewriteResponse)
@app.post("/api/rewrite", response_model=RewriteResponse)
async def rewrite_text(req: RewriteRequest):
    input_text = req.text.strip()
    if not input_text:
        raise HTTPException(status_code=400, detail="Input text cannot be empty")

    logger.info("Received rewrite request: '%s'", input_text)

    gemini_key = os.environ.get("GEMINI_API_KEY")
    groq_key = os.environ.get("GROQ_API_KEY")

    result: Optional[str] = None

    # Priority 1: Gemini
    if gemini_key:
        try:
            result = await rewrite_with_gemini(input_text, gemini_key)
        except Exception as e:
            logger.error("Error during Gemini rewrite: %s", e)

    # Priority 2: Groq
    if not result and groq_key:
        try:
            result = await rewrite_with_groq(input_text, groq_key)
        except Exception as e:
            logger.error("Error during Groq rewrite: %s", e)

    # Fallback if LLM failed or no API key is provided
    provider = "gemini" if gemini_key else "groq"
    if not result:
        logger.warning("LLM did not return a result. Falling back to mock engine.")
        result = mock_local_rewrite(input_text)
        provider = "mock_fallback"

    logger.info("Rewritten result [%s]: '%s'", provider, result)
    return RewriteResponse(rewritten_text=result, provider=provider)

def mock_local_rewrite(text: str) -> str:
    """Simple offline mock fallback for immediate testing without keys."""
    lower = text.lower()
    if "kya scene" in lower or "are you coming" in lower:
        return "What is the plan? Are you coming today?"
    if "kal meeting" in lower:
        return "What time is the meeting tomorrow?"
    if "kaise ho" in lower:
        return "How are you doing?"
    # Capitalize and add proper punctuation
    cleaned = text[0].upper() + text[1:] if text else ""
    if not cleaned.endswith((".", "?", "!")):
        cleaned += "."
    return cleaned

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
