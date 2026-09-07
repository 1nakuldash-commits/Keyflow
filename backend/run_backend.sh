#!/bin/bash
set -e

cd "$(dirname "$0")"

echo "========================================"
echo " Starting Keyflow FastAPI Backend "
echo "========================================"

# Create virtual environment if it does not exist
if [ ! -d "venv" ]; then
    echo "Creating Python virtual environment (backend/venv)..."
    python3 -m venv venv
fi

# Activate virtual environment
source venv/bin/activate

# Upgrade pip & install requirements
echo "Checking and installing dependencies..."
pip install --quiet -r requirements.txt

# Load environment variables from .env if present
if [ -f ".env" ]; then
    export $(cat .env | grep -v '#' | awk '/=/ {print $1}')
fi

if [ -z "$GEMINI_API_KEY" ] && [ -z "$GROQ_API_KEY" ]; then
    echo "⚠️  NOTE: Neither GEMINI_API_KEY nor GROQ_API_KEY is set."
    echo "   Backend will run in OFFLINE MOCK MODE for immediate testing."
    echo "   To use live AI, set GEMINI_API_KEY or edit backend/.env."
else
    echo "✨ Live AI provider detected!"
fi

echo "🚀 Starting server at http://127.0.0.1:8000 (and http://10.0.2.2:8000 for Android Emulator)"
echo "Press Ctrl+C to stop."
echo ""

uvicorn main:app --host 0.0.0.0 --port 8000 --reload
