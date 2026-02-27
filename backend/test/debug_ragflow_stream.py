# 调试工具：测试与Xinference/RAGFlow的流式对话连接

import requests
import json
import sys

BASE_URL = "http://localhost:8084"
API_KEY = "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4"

def get_headers():
    return {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }

def list_datasets():
    url = f"{BASE_URL}/api/v1/datasets?page=1&page_size=10"
    resp = requests.get(url, headers=get_headers())
    if resp.status_code != 200:
        print(f"List datasets failed: {resp.text}")
        sys.exit(1)
    return resp.json().get('data', [])

import time

def create_conversation(dataset_ids):
    url = f"{BASE_URL}/api/v1/chats"
    data = {
        "name": f"debug_chat_{int(time.time())}",
        "dataset_ids": dataset_ids
    }
    resp = requests.post(url, headers=get_headers(), json=data)
    if resp.status_code != 200:
        print(f"Create conversation failed: {resp.text}")
        sys.exit(1)
    print(f"Create conversation resp: {resp.text}")
    return resp.json().get('data', {}).get('id')

def chat_stream(conversation_id, query):
    url = f"{BASE_URL}/api/v1/chats_openai/{conversation_id}/chat/completions"
    data = {
        "model": "deepseek-r1-distill-qwen-14b@Xinference", # Adjust if needed, but client uses this default
        "messages": [{"role": "user", "content": query}],
        "stream": True,
        "extra_body": {"reference": True}
    }
    print(f"Sending chat request to {url}...")
    resp = requests.post(url, headers=get_headers(), json=data, stream=True)
    
    if resp.status_code != 200:
        print(f"Chat failed: {resp.text}")
        sys.exit(1)
        
    print("Stream started. Chunks:")
    full_content = ""
    for line in resp.iter_lines():
        if line:
            line_str = line.decode('utf-8')
            # print(f"RAW: {line_str}")
            if line_str.startswith("data:"):
                json_str = line_str[5:].strip()
                if json_str == "[DONE]":
                    print("DONE")
                    break
                try:
                    chunk = json.loads(json_str)
                    # Check for reference
                    if "reference" in chunk:
                         print(f"FOUND REFERENCE (top-level): {type(chunk['reference'])}")
                    if "choices" in chunk and len(chunk["choices"]) > 0:
                        delta = chunk["choices"][0].get("delta", {})
                        if "reference" in delta:
                            ref = delta['reference']
                            print(f"FOUND REFERENCE (in delta): {type(ref)}")
                            if isinstance(ref, dict):
                                print(f"  chunks: {len(ref.get('chunks', []))}")
                            elif isinstance(ref, list):
                                print(f"  list len: {len(ref)}")
                        if "content" in delta:
                             # print(f"Content: {delta['content']}")
                             if delta['content']:
                                full_content += delta['content']
                except json.JSONDecodeError:
                    pass
    print("\n--- FULL ANSWER ---")
    print(full_content)
    print("-------------------")

def main():
    datasets = list_datasets()
    if not datasets:
        print("No datasets found.")
        sys.exit(1)
    
    # Pick a dataset with documents
    target_ds_id = None
    for ds in datasets:
        if ds.get('document_count', 0) > 0:
            target_ds_id = ds['id']
            print(f"Using dataset: {ds['name']} ({target_ds_id})")
            break
            
    if not target_ds_id:
        print("No dataset with documents found.")
        sys.exit(1)
        
    conv_id = create_conversation([target_ds_id])
    print(f"Created conversation: {conv_id}")
    
    chat_stream(conv_id, "围护结构倾斜的建筑空间的建筑面积规定是什么")

if __name__ == "__main__":
    main()
