import requests
import json
import sys

# Configuration
BACKEND_HOST = "http://localhost:8083"
TEST_USER = "test_user_01"  # Must match the user created in 03_test_backend_admin.py

def test_chat_completions():
    print(f"\n[INFO] Testing Chat API as user '{TEST_USER}'...")
    try:
        url = f"{BACKEND_HOST}/api/chat/completions"
        headers = {
            "Content-Type": "application/json",
            "X-User-Name": TEST_USER
        }
        body = {
            "question": "Please summarize the knowledge base.",
            "stream": True
        }
        
        print(f"  POST {url}")
        resp = requests.post(url, headers=headers, json=body, stream=True)
        
        if resp.status_code == 200:
            print("[SUCCESS] Connection established. Receiving stream...")
            content_received = False
            for line in resp.iter_lines():
                if line:
                    decoded_line = line.decode('utf-8')
                    if decoded_line.startswith("data:"):
                        json_str = decoded_line[5:].strip()
                        if json_str == "[DONE]":
                            break
                        try:
                            data = json.loads(json_str)
                            answer = data.get("answer", "")
                            if answer:
                                sys.stdout.write(answer)
                                sys.stdout.flush()
                                content_received = True
                        except:
                            pass
            print("\n[SUCCESS] Stream finished.")
            if not content_received:
                print("[WARN] No content received in stream. (Check if KB has data/permissions)")
        else:
            print(f"[FAIL] Chat request failed. Status: {resp.status_code}, Body: {resp.text}")

    except Exception as e:
        print(f"[FAIL] Chat error: {e}")

def main():
    test_chat_completions()

if __name__ == "__main__":
    main()
