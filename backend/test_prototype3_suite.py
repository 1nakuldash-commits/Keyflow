"""
Keyflow Prototype 3 Real-World Test Suite
Tests ~75 realistic inputs across English, Hindi/Hinglish (including Devanagari script),
and Code-switched Hinglish to evaluate:
1. Script Normalization (Indic/Urdu -> Romanized Hinglish)
2. Raw Mode (Minimal edit, Hinglish preservation, zero translation)
3. Normal Mode (Natural everyday English, meaning & fact preservation)
4. Professional Mode (Articulate workplace English, meaning & fact preservation)
"""

import os
import sys
import time
import asyncio
import json
from typing import Dict, Any, List

# Ensure backend directory is in python path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from main import (
    load_env_file,
    contains_indic_or_perso_arabic,
    romanize_indic_text,
    perform_rewrite_pipeline,
    GROQ_DEFAULT_KEY
)

load_env_file()

TEST_SAMPLES = [
    # =========================================================================
    # Category 1: English Sentences (20 samples)
    # Technical terms, numbers, dates, casual chat, business requests
    # =========================================================================
    {"cat": "English", "id": "EN-01", "text": "Can you check the Docker container logs? The service is crashing."},
    {"cat": "English", "id": "EN-02", "text": "I pushed the commit to staging branch. Please trigger the CI/CD pipeline."},
    {"cat": "English", "id": "EN-03", "text": "We need to update the GraphQL schema to support pagination on the user table."},
    {"cat": "English", "id": "EN-04", "text": "The meeting is rescheduled to October 14th at 3:30 PM in conference room B."},
    {"cat": "English", "id": "EN-05", "text": "Invoice #4092 for $1,250 has been approved by finance."},
    {"cat": "English", "id": "EN-06", "text": "Please ship 25 units of the model X to the warehouse by Friday."},
    {"cat": "English", "id": "EN-07", "text": "hey im running 5 mins late hold the elevator for me"},
    {"cat": "English", "id": "EN-08", "text": "can u send the figma link again i lost it"},
    {"cat": "English", "id": "EN-09", "text": "Got stuck in traffic near downtown, will reach office around 10:15 AM."},
    {"cat": "English", "id": "EN-10", "text": "Do not merge the pull request yet, we found a regression in production."},
    {"cat": "English", "id": "EN-11", "text": "I reviewed the draft agreement and left three comments on section 4."},
    {"cat": "English", "id": "EN-12", "text": "Thanks for the quick turnaround on the design assets."},
    {"cat": "English", "id": "EN-13", "text": "Could you please send over the Q3 financial summary before tomorrow's board sync?"},
    {"cat": "English", "id": "EN-14", "text": "The Redis cache TTL was set too low so memory spiked at 2 AM."},
    {"cat": "English", "id": "EN-15", "text": "Are you free for a quick 5-minute huddle on Slack?"},
    {"cat": "English", "id": "EN-16", "text": "We saw a 14% drop in API latency after deploying the new Go backend."},
    {"cat": "English", "id": "EN-17", "text": "dont forget to submit your reimbursement receipts before the 28th"},
    {"cat": "English", "id": "EN-18", "text": "The client requested an extension until November 3rd for UAT sign-off."},
    {"cat": "English", "id": "EN-19", "text": "Just pushed the hotfix to production. Monitoring error rates now."},
    {"cat": "English", "id": "EN-20", "text": "Let's catch up tomorrow morning to finalize the sprint scope."},

    # =========================================================================
    # Category 2: Hindi & Devanagari Script (20 samples)
    # Tests Script Normalization layer and Raw/Normal/Pro preservation
    # =========================================================================
    {"cat": "Indic-Script", "id": "HI-01", "text": "मुझे कल ऑफिस जाना है और फिर क्लाइंट को कॉल करना है।"},
    {"cat": "Indic-Script", "id": "HI-02", "text": "सर कल की मीटिंग में मैं शायद थोड़ा लेट आऊंगा क्योंकि मेरी एक और मीटिंग है।"},
    {"cat": "Indic-Script", "id": "HI-03", "text": "क्या आप आज शाम 5 बजे तक रिपोर्ट भेज सकते हैं?"},
    {"cat": "Indic-Script", "id": "HI-04", "text": "इस ऐप में बहुत सारे बग्स हैं और यूआई बिल्कुल भी अच्छा नहीं है।"},
    {"cat": "Indic-Script", "id": "HI-05", "text": "भाई मैं 10 मिनट में पहुंच रहा हूं प्लीज थोड़ा वेट कर लेना।"},
    {"cat": "Hinglish-Pure", "id": "HI-06", "text": "mujhe kal office jana hai and fir client ko call karna hai."},
    {"cat": "Hinglish-Pure", "id": "HI-07", "text": "bhai rapido me hu 10 min me aa raha hu wait karna."},
    {"cat": "Hinglish-Pure", "id": "HI-08", "text": "aaj shaam ko kya plan hai party ka?"},
    {"cat": "Hinglish-Pure", "id": "HI-09", "text": "paanch baje tak delivery aa jayegi tension mat lo."},
    {"cat": "Hinglish-Pure", "id": "HI-10", "text": "kal meeting kitne baje rakhi hai team ne?"},
    {"cat": "Indic-Script", "id": "HI-11", "text": "आज का काम खत्म हो गया है अब कल सुबह मिलते हैं।"},
    {"cat": "Indic-Script", "id": "HI-12", "text": "कृपया फाइल चेक करके मुझे कन्फर्म कर दीजिए।"},
    {"cat": "Hinglish-Pure", "id": "HI-13", "text": "mera phone disconnect ho gaya tha network issue ki wajah se."},
    {"cat": "Hinglish-Pure", "id": "HI-14", "text": "sir maine invoice bhej diya hai check kar lijiye please."},
    {"cat": "Hinglish-Pure", "id": "HI-15", "text": "aaj main leave pe hu tabiyat theek nahi hai meri."},
    {"cat": "Hinglish-Pure", "id": "HI-16", "text": "kya scene hai kal chal rahe ho ya nahi?"},
    {"cat": "Hinglish-Pure", "id": "HI-17", "text": "payment receive ho gayi hai account me thanks."},
    {"cat": "Hinglish-Pure", "id": "HI-18", "text": "thoda busy hu 15 minute me call back karta hu."},
    {"cat": "Indic-Script", "id": "HI-19", "text": "क्या आप 25 तारीख को दिल्ली आ रहे हैं?"},
    {"cat": "Hinglish-Pure", "id": "HI-20", "text": "wifi band ho gaya tha isliye call drop ho gaya."},

    # =========================================================================
    # Category 3: Code-Switched Hinglish (35 samples)
    # Mixed clauses, technical jargon, numbers, names, negations
    # =========================================================================
    {"cat": "Code-Switch", "id": "CS-01", "text": "Please check the UI bug, mujhe lagta hai button alignment off hai."},
    {"cat": "Code-Switch", "id": "CS-02", "text": "Kal ki client presentation ke liye slides ready kar lena."},
    {"cat": "Code-Switch", "id": "CS-03", "text": "Main Rohit ke saath 2:30 baje lunch pe ja raha hu, join karna hai kya?"},
    {"cat": "Code-Switch", "id": "CS-04", "text": "Server down ho gaya hai, kya tum production logs check kar sakte ho?"},
    {"cat": "Code-Switch", "id": "CS-05", "text": "I don't think aaj deploy karna safe hoga because QA testing incomplete hai."},
    {"cat": "Code-Switch", "id": "CS-06", "text": "Mujhe 20 pieces chaiye the, 200 nahi, please order correct karo."},
    {"cat": "Code-Switch", "id": "CS-07", "text": "Bhai Uber book kar li hai maine, 5 minutes me pickup point pe milte hai."},
    {"cat": "Code-Switch", "id": "CS-08", "text": "Mera laptop crash ho gaya and sara unsaved code delete ho gaya yaar."},
    {"cat": "Code-Switch", "id": "CS-09", "text": "Can you please merge my pull request? Staging pe test karna hai."},
    {"cat": "Code-Switch", "id": "CS-10", "text": "Aapka invoice email kar diya hai, payment Monday tak transfer kar dena."},
    {"cat": "Code-Switch", "id": "CS-11", "text": "mughe is me thode changes karne hai jaise ki jo normal wo li jaise side ek tool hai called sidebr extention hai us me ager me hinhish me bhi likhta hoon to uska translate mode like usko ek out me convert karta hai me uska ek example deta hoon waise hi out put hame apne normal mode me chaiye get it"},
    {"cat": "Code-Switch", "id": "CS-12", "text": "Sir kal jo meeting hai usme main shayad late aaunga because meri ek aur meeting hai."},
    {"cat": "Code-Switch", "id": "CS-13", "text": "Mujhe kal office jaana hai but I don't think main time pe pahunch paunga."},
    {"cat": "Code-Switch", "id": "CS-14", "text": "Yeh email client ko abhi mat bhejna, pehle director se approval lena hoga."},
    {"cat": "Code-Switch", "id": "CS-15", "text": "Database me 500 naye users add ho gaye hai today morning."},
    {"cat": "Code-Switch", "id": "CS-16", "text": "Mene PR review kar liya hai, looks good to me, deploy kar do."},
    {"cat": "Code-Switch", "id": "CS-17", "text": "Meeting link kaam nahi kar raha, kya aap naya Google Meet link bhej sakte ho?"},
    {"cat": "Code-Switch", "id": "CS-18", "text": "Vikram ko call karke bol do ki delivery boy aa chuka hai gate pe."},
    {"cat": "Code-Switch", "id": "CS-19", "text": "Hum logo ne budget 15% reduce kar diya hai for Q4 marketing."},
    {"cat": "Code-Switch", "id": "CS-20", "text": "Main ghar se work kar raha hu aaj, if anything urgent please ping me on Slack."},
    {"cat": "Code-Switch", "id": "CS-21", "text": "Auth token expire ho gaya tha isliye user login fail ho raha hai."},
    {"cat": "Code-Switch", "id": "CS-22", "text": "Traffic bahut zyada hai Silk Board pe, 20 minute late hounga."},
    {"cat": "Code-Switch", "id": "CS-23", "text": "Don't share the staging API credentials on public WhatsApp group."},
    {"cat": "Code-Switch", "id": "CS-24", "text": "Figma design ready hai but dev handoff meeting kal 11 AM ko hogi."},
    {"cat": "Code-Switch", "id": "CS-25", "text": "Payment gateway down chal raha hai Razorpay ka, customer error aa raha hai."},
    {"cat": "Code-Switch", "id": "CS-26", "text": "Maine tickets book kar li hai 12th December ki, check your email for confirmation."},
    {"cat": "Code-Switch", "id": "CS-27", "text": "Yeh feature release karna delay karna padega because backend ready nahi hai."},
    {"cat": "Code-Switch", "id": "CS-28", "text": "Screen share karo na please, mujhe error samajh nahi aa raha."},
    {"cat": "Code-Switch", "id": "CS-29", "text": "App store pe update live ho gaya hai, version 3.0 download kar lo."},
    {"cat": "Code-Switch", "id": "CS-30", "text": "Bhai ek cup chai peene chalte hai cafeteria me 5 minute me."},
    {"cat": "Code-Switch", "id": "CS-31", "text": "I will not be able to attend the standup today, doctor appointment hai 10 AM ko."},
    {"cat": "Code-Switch", "id": "CS-32", "text": "AWS bill is month 300 dollars zyada aaya hai, check karo kaunsa instance running hai."},
    {"cat": "Code-Switch", "id": "CS-33", "text": "Kya tum kal tak draft complete karke de sakte ho? Urgent hai."},
    {"cat": "Code-Switch", "id": "CS-34", "text": "Bina review ke merge mat karna koi bhi branch."},
    {"cat": "Code-Switch", "id": "CS-35", "text": "Meeting me sab agree kar gaye the ki launch next Wednesday ko hoga."}
]

async def run_single_test(sample: Dict[str, str], groq_key: str) -> Dict[str, Any]:
    raw_input = sample["text"]
    cat = sample["cat"]
    sid = sample["id"]

    record: Dict[str, Any] = {
        "id": sid,
        "category": cat,
        "input": raw_input,
        "script_normalized": False,
        "norm_latency_ms": 0.0,
        "normalized_text": raw_input,
        "raw_output": "",
        "raw_latency_ms": 0.0,
        "normal_output": "",
        "normal_latency_ms": 0.0,
        "pro_output": "",
        "pro_latency_ms": 0.0,
        "issues": []
    }

    # Step 1: Script Normalization if Indic/Urdu detected
    current_text = raw_input
    if contains_indic_or_perso_arabic(raw_input):
        t0 = time.perf_counter()
        norm_text, norm_lat = await romanize_indic_text(raw_input, groq_key)
        record["script_normalized"] = True
        record["norm_latency_ms"] = round(norm_lat, 1)
        record["normalized_text"] = norm_text
        current_text = norm_text

        # Verify Indic script was removed
        if contains_indic_or_perso_arabic(norm_text):
            record["issues"].append("Script normalization failed: Indic characters still present in normalized text")

    # Step 2: Test RAW Mode
    t_raw = time.perf_counter()
    raw_out, _ = await perform_rewrite_pipeline(current_text, "raw")
    record["raw_latency_ms"] = round((time.perf_counter() - t_raw) * 1000.0, 1)
    record["raw_output"] = raw_out

    # Step 3: Test NORMAL Mode
    t_norm = time.perf_counter()
    norm_out, _ = await perform_rewrite_pipeline(current_text, "normal")
    record["normal_latency_ms"] = round((time.perf_counter() - t_norm) * 1000.0, 1)
    record["normal_output"] = norm_out

    # Step 4: Test PROFESSIONAL Mode
    t_pro = time.perf_counter()
    pro_out, _ = await perform_rewrite_pipeline(current_text, "professional")
    record["pro_latency_ms"] = round((time.perf_counter() - t_pro) * 1000.0, 1)
    record["pro_output"] = pro_out

    # Step 5: Semantic & rule checks
    lower_norm = norm_out.lower()
    for jargon in ["suboptimal", "necessitating", "comprehensive implementation improvements"]:
        if jargon in lower_norm:
            record["issues"].append(f"Normal mode used forbidden corporate fluff: '{jargon}'")

    # Check for em-dashes
    if "—" in norm_out or "—" in pro_out or "—" in raw_out:
        record["issues"].append("Em-dash (—) found in output")

    # Check Raw mode translation leak (Raw should NOT translate Hinglish to pure English)
    if cat in ("Hinglish-Pure", "Indic-Script") and sid in ("HI-01", "HI-06", "HI-07"):
        lower_raw = raw_out.lower()
        if "i need to go" in lower_raw or "i have to go" in lower_raw or "i am in a rapido" in lower_raw:
            record["issues"].append("Raw mode translated to English instead of preserving Hinglish")

    return record

async def main():
    groq_key = os.environ.get("GROQ_API_KEY") or os.environ.get("GROQ_AUDIO_API_KEY") or GROQ_DEFAULT_KEY
    if not groq_key:
        print("ERROR: Groq API key is not configured in environment")
        sys.exit(1)

    print(f"Starting Keyflow Prototype 3 Real-World Evaluation on {len(TEST_SAMPLES)} samples...")
    print(f"Active Groq Key: {groq_key[:8]}...{groq_key[-4:]}")
    print("-" * 75)

    results = []
    start_all = time.perf_counter()

    # Process in sequential batches of 3 to avoid rate limits
    batch_size = 3
    for i in range(0, len(TEST_SAMPLES), batch_size):
        batch = TEST_SAMPLES[i:i+batch_size]
        tasks = [run_single_test(sample, groq_key) for sample in batch]
        batch_results = await asyncio.gather(*tasks)
        for res in batch_results:
            results.append(res)
            issues_str = f" [ISSUES: {len(res['issues'])}]" if res["issues"] else " [OK]"
            norm_tag = f" (Norm: {res['norm_latency_ms']}ms)" if res["script_normalized"] else ""
            print(f"[{res['id']}] {res['category']}{norm_tag} -> Raw: {res['raw_latency_ms']}ms | Norm: {res['normal_latency_ms']}ms | Pro: {res['pro_latency_ms']}ms{issues_str}")
            if res["issues"]:
                for iss in res["issues"]:
                    print(f"    ⚠️  {iss}")

        await asyncio.sleep(0.3)

    total_time = time.perf_counter() - start_all
    print("-" * 75)
    print(f"Evaluation complete in {total_time:.2f}s across {len(results)} samples.")

    output_path = os.path.join(os.path.dirname(__file__), "prototype3_eval_results.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)
    print(f"Detailed evaluation saved to {output_path}")

if __name__ == "__main__":
    asyncio.run(main())
