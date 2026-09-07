import json
import urllib.request
import urllib.error
import sys

def test_endpoint(endpoint_url: str, text: str):
    payload = json.dumps({"text": text}).encode("utf-8")
    req = urllib.request.Request(
        f"{endpoint_url}/rewrite",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST"
    )

    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            data = json.loads(response.read().decode("utf-8"))
            provider = data.get("provider", "")
            tag = f"({provider})" if provider else ""
            print(f"\n✅ Input:     \"{text}\"")
            print(f"   Rewritten: \"{data.get('rewritten_text')}\" {tag}")
    except urllib.error.HTTPError as e:
        body = ""
        try:
            body = e.read().decode("utf-8")
        except Exception:
            pass
        print(f"\n❌ Server returned HTTP {e.code}: {e.reason}")
        if body:
            print(f"   Detail: {body}")
    except urllib.error.URLError as e:
        print(f"\n❌ Could not connect to backend at {endpoint_url}: {e.reason}")
        print("   Make sure your backend server is running (e.g. uvicorn main:app --reload)")
    except Exception as e:
        print(f"\n❌ Error: {e}")

def check_health(endpoint_url: str):
    try:
        req = urllib.request.Request(endpoint_url, method="GET")
        with urllib.request.urlopen(req, timeout=5) as res:
            info = json.loads(res.read().decode("utf-8"))
            print(f"Server Status:     {info.get('status', 'unknown').upper()}")
            print(f"Gemini Configured: {info.get('gemini_configured')}")
            print(f"Groq Configured:   {info.get('groq_configured')}")
    except Exception as e:
        print(f"Server unreachable at {endpoint_url}: {e}")
        return False
    return True

if __name__ == "__main__":
    base_url = "http://127.0.0.1:8000"
    print("=" * 55)
    print(" Keyflow Backend Test Client")
    print("=" * 55)

    if not check_health(base_url):
        print("\n👉 Please start the backend first using:")
        print("   cd /Users/vishalsikdar/Desktop/Keyflow/backend")
        print("   ./run_backend.sh\n")
        sys.exit(1)

    # If user provided text via CLI arguments, e.g. python3 test_api.py "kasie ho?"
    if len(sys.argv) > 1:
        custom_text = " ".join(sys.argv[1:])
        test_endpoint(base_url, custom_text)
        sys.exit(0)

    print("\nSending sample test query...")
    test_endpoint(base_url, "kal meeting kitne baje hai bro?")

    print("\n" + "=" * 55)
    print(" Interactive Mode (Type any Hinglish/rough English phrase)")
    print(" Press Ctrl+C or Enter on empty line to exit.")
    print("=" * 55)

    try:
        while True:
            text = input("\nType text: ").strip()
            if not text:
                break
            test_endpoint(base_url, text)
    except (KeyboardInterrupt, EOFError):
        print("\nExiting.")
