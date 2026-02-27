# 单元测试：探索不同解析接口参数的兼容性
import requests
import json
import time

API_BASE = "http://localhost:8083/api"
# Need to use direct RAGFlow access to bypass backend if backend proxies incorrectly?
# But backend uses RAGFLOW_BASE_URL.
# Let's test via backend proxy first, but vary the endpoint.
# Backend exposes /admin/datasets/{id}/documents/run -> proxies to RAGFlow /api/v1/datasets/{id}/documents/run

def test_parsing_variants():
    print("Testing Parsing Variants...")
    
    # 1. Get Dataset and Doc
    res = requests.get(f"{API_BASE}/admin/datasets?page=1&page_size=10")
    if res.status_code != 200:
        print("Failed to list datasets")
        return
    dataset_id = res.json().get('data', [])[0]['id']
    
    res = requests.get(f"{API_BASE}/admin/datasets/{dataset_id}/documents?page=1&page_size=10")
    docs = res.json().get('data', {}).get('docs', [])
    if not docs:
        print("No docs found")
        return
    doc_id = docs[0]['id']
    print(f"Dataset: {dataset_id}, Doc: {doc_id}")
    
    # Payload
    payload = {"doc_ids": [doc_id], "run": 1}
    
    # Variant 1: POST /api/v1/document/run (via backend if possible? No, backend hardcodes path)
    # We must modify backend code to test different RAGFlow endpoints unless we port-forward RAGFlow.
    # RAGFlow is on port 8084 (mapped to 80 inside).
    # Let's try accessing RAGFlow directly if possible.
    # RAGFlow API Key is in docker-compose.
    
    RAGFLOW_URL = "http://localhost:8084"
    API_KEY = "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4"
    headers = {"Authorization": f"Bearer {API_KEY}"}
    
    print(f"\n--- Testing Direct RAGFlow Access ---")
    
    # V1: POST /api/v1/document/run
    print("Trying POST /api/v1/document/run...")
    try:
        r = requests.post(f"{RAGFLOW_URL}/api/v1/document/run", json=payload, headers=headers)
        print(f"Status: {r.status_code}, Body: {r.text}")
    except Exception as e:
        print(f"Error: {e}")

    # V2: POST /api/v1/datasets/{id}/documents/run (The one we use)
    print(f"Trying POST /api/v1/datasets/{dataset_id}/documents/run...")
    try:
        r = requests.post(f"{RAGFLOW_URL}/api/v1/datasets/{dataset_id}/documents/run", json=payload, headers=headers)
        print(f"Status: {r.status_code}, Body: {r.text}")
    except Exception as e:
        print(f"Error: {e}")
        
    # V3: PUT /api/v1/document/run
    print("Trying PUT /api/v1/document/run...")
    try:
        r = requests.put(f"{RAGFLOW_URL}/api/v1/document/run", json=payload, headers=headers)
        print(f"Status: {r.status_code}, Body: {r.text}")
    except Exception as e:
        print(f"Error: {e}")

    # V4: POST /api/v1/datasets/{id}/chunks (SDK Endpoint from experience doc)
    print(f"Trying POST /api/v1/datasets/{dataset_id}/chunks...")
    try:
        # Note: payload key is "document_ids" not "doc_ids"
        sdk_payload = {"document_ids": [doc_id]}
        r = requests.post(f"{RAGFLOW_URL}/api/v1/datasets/{dataset_id}/chunks", json=sdk_payload, headers=headers)
        print(f"Status: {r.status_code}, Body: {r.text}")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    test_parsing_variants()
