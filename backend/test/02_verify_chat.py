# 验证SSE流式对话接口及用户鉴权流程
import requests
import json
import sys

BASE_URL = "http://localhost:8083/api"
USERNAME = "zhangsan"

def log(msg):
    print(f"[Chat Test] {msg}")

def verify_chat_sse():
    """
    验证 SSE 流式对话接口是否可用。
    后端接口: POST /api/chat/completions
    Header: X-User-Name: zhangsan
    Body: { "question": "你好", "stream": true }
    """
    url = f"{BASE_URL}/chat/completions"
    headers = {"X-User-Name": USERNAME}
    payload = {"question": "你好", "stream": True}
    
    log(f"Starting chat test with user '{USERNAME}'...")
    log(f"POST {url}")
    
    try:
        with requests.post(url, json=payload, headers=headers, stream=True) as resp:
            if resp.status_code != 200:
                log(f"❌ Chat request failed. Status: {resp.status_code}, Body: {resp.text}")
                sys.exit(1)
            
            log("✅ Connection established. Receiving SSE stream...")
            
            received_content = False
            for line in resp.iter_lines():
                if line:
                    decoded_line = line.decode('utf-8')
                    if decoded_line.startswith("data:"):
                        data_str = decoded_line[5:].strip()
                        if data_str == "[DONE]":
                            log("✅ Received [DONE] signal.")
                            break
                        try:
                            data_json = json.loads(data_str)
                            # RAGFlow returns 'answer' or 'content' depending on version, 
                            # but our mock processor returns 'answer'.
                            # Real RAGFlow returns structure like { "data": { "answer": "..." } } or just answer fields.
                            # Let's just print a snippet.
                            log(f"Received chunk: {str(data_json)[:100]}...")
                            received_content = True
                        except json.JSONDecodeError:
                            log(f"Received non-JSON data: {data_str}")
            
            if received_content:
                log("✅ Chat test passed. Content received.")
            else:
                log("⚠️ No content received or empty stream.")
                
    except Exception as e:
        log(f"❌ Exception during chat test: {e}")
        sys.exit(1)

if __name__ == "__main__":
    print("=== Starting Backend Chat Availability Test ===")
    verify_chat_sse()
    print("=== Test Completed Successfully ===")
