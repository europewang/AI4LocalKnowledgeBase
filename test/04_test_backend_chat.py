import requests
import json
import sys
import time

# Configuration
BACKEND_HOST = "http://localhost:8083"
TEST_USER = "test_user_01"  # Must match the user created in 03_test_backend_admin.py

def test_chat_completions():
    print(f"\n[INFO] Testing Chat API as user '{TEST_USER}'...")
    url = f"{BACKEND_HOST}/api/chat/completions"
    headers = {
        "Content-Type": "application/json",
        "X-User-Name": TEST_USER
    }
    body = {
        "question": "请用一句话总结这个知识库",
        "stream": True
    }

    last_err = None
    for attempt in range(1, 6):
        try:
            print(f"  POST {url} (attempt {attempt}/5)")
            resp = requests.post(url, headers=headers, json=body, stream=True, timeout=20)

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
                return

            print(f"[FAIL] Chat request failed. Status: {resp.status_code}, Body: {resp.text}")
            return
        except Exception as e:
            last_err = e
            time.sleep(2)

    print(f"[FAIL] Chat error: {last_err}")

def main():
    test_chat_completions()

if __name__ == "__main__":
    main()
