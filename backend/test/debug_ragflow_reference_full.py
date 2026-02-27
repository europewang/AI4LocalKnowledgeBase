# 调试工具：捕获并展示流式对话中的引用源数据
import requests
import json
import time

BASE_URL = "http://localhost:8083/api"
HEADERS = {"Content-Type": "application/json"}

def run_chat_debug():
    url = f"{BASE_URL}/chat/completions"
    headers = {
        "X-User-Name": "admin",
        "Content-Type": "application/json"
    }
    data = {
        "question": "围护结构倾斜的建筑空间的建筑面积规定是什么",
        "stream": True
    }

    print(f"Connecting to {url}...")
    try:
        response = requests.post(url, headers=headers, json=data, stream=True)
        if response.status_code != 200:
            print(f"Error: {response.status_code} - {response.text}")
            return

        print("Connected. Streaming response...")
        
        found_ref = False
        
        for line in response.iter_lines():
            if line:
                line = line.decode('utf-8')
                if line.startswith('data:'):
                    data_str = line[5:].strip()
                    if data_str == '[DONE]':
                        break
                    try:
                        chunk = json.loads(data_str)
                        
                        # Check for reference
                        if "reference" in chunk and chunk["reference"]:
                            print("\n[FOUND REFERENCE DATA!]")
                            print(json.dumps(chunk["reference"], indent=2, ensure_ascii=False))
                            found_ref = True
                            # We only need one sample to see the structure
                            break
                            
                    except json.JSONDecodeError:
                        pass

        if not found_ref:
            print("\n\nWARNING: No references received in the stream.")

    except Exception as e:
        print(f"Connection failed: {e}")

if __name__ == "__main__":
    run_chat_debug()
