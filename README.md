# Keyflow (Prototype 1)

Keyflow is an AI-powered text-rewriting tool for Android that docks a floating glassmorphic toolbar directly above the system keyboard whenever typing in any application (e.g., WhatsApp, Notes, Chrome). When the user taps **"✨ Rewrite"**, it extracts rough English or Hinglish from the active input field, sends it to a FastAPI backend powered by an LLM, and seamlessly replaces the text in-place.

---

## Project Structure

```
Keyflow/
├── android/                             # Android Client (Kotlin)
│   ├── app/
│   │   ├── build.gradle.kts
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml      # Permissions (INTERNET, SYSTEM_ALERT_WINDOW, BIND_ACCESSIBILITY_SERVICE)
│   │   │   ├── java/com/keyflow/app/
│   │   │   │   ├── MainActivity.kt      # Setup screen & service status check
│   │   │   │   └── service/
│   │   │   │       └── RewriteAccessibilityService.kt # Core Accessibility & WindowManager logic
│   │   │   └── res/
│   │   │       ├── layout/
│   │   │       │   ├── overlay_toolbar.xml # 48dp glassmorphic floating bar layout
│   │   │       │   └── activity_main.xml
│   │   │       ├── drawable/            # Glassmorphic backgrounds and button ripple states
│   │   │       ├── values/              # Strings, colors, and dark theme
│   │   │       └── xml/
│   │   │           └── accessibility_service_config.xml # Configured for typeWindowsChanged & typeViewFocused
│   │   └── proguard-rules.pro
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── backend/                             # Python FastAPI Backend
    ├── main.py                          # Single-file FastAPI app with LLM prompt integration
    ├── requirements.txt
    └── .env.example
```

---

## 1. Running the Backend

### Prerequisites
- Python 3.9+
- A Gemini API key (`GEMINI_API_KEY`) or Groq API key (`GROQ_API_KEY`)

### Setup & Run
```bash
cd backend

# Create and activate virtual environment
python3 -m venv venv
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Export your API key
export GEMINI_API_KEY="your-gemini-api-key"
# or: export GROQ_API_KEY="your-groq-api-key"

# Start the FastAPI server
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

> **Note**: If no API key is provided, the backend operates in an offline mock mode so you can test end-to-end connectivity without keys.

### Test Backend Endpoint
```bash
curl -X POST http://127.0.0.1:8000/rewrite \
  -H "Content-Type: application/json" \
  -d '{"text": "kal meeting kitne baje hai bro?"}'
```

---

## 2. Running the Android Client

### In Android Studio
1. Open Android Studio and select **Open**, then choose the `android` folder (`Keyflow/android`).
2. Allow Gradle to sync dependencies (`OkHttp`, `Coroutines`, `Material Components`).
3. Select an Android Emulator (API 26+) or a connected physical device and click **Run** (`Shift + F10`).

> **Emulator Networking**: When running inside an Android Emulator, `http://10.0.2.2:8000` automatically routes to `localhost:8000` on your development machine where FastAPI is running.

---

## 3. First-Time Setup on Device / Emulator

1. Open the **Keyflow** app on the device.
2. Tap **"Enable Accessibility Service"** to open Android System Settings.
3. Navigate to **Downloaded Apps** (or **Installed Services**) ➔ **Keyflow Rewrite Toolbar** ➔ **Enable**.
4. Return to Keyflow or open any messaging app (e.g., WhatsApp, Notes, Chrome).
5. Tap inside any text field to open the keyboard (Gboard).
6. The sleek 48dp **Keyflow Toolbar** will automatically dock directly above the keyboard.
7. Type some rough text or Hinglish (e.g. `kya scene hai bro are you coming today`).
8. Tap **"✨ Rewrite"**. The progress bar will spin, the LLM will correct the text, and the input field will be replaced with clean, natural English.
