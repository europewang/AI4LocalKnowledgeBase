# 单元测试：文档解析触发接口与状态轮询
import requests
import json
import time

API_BASE = "http://localhost:8083/api"

def test_parsing():
    print("Testing Parsing...")
    
    # 1. List Datasets
    res = requests.get(f"{API_BASE}/admin/datasets?page=1&page_size=10")
    if res.status_code != 200:
        print(f"Failed to list datasets: {res.text}")
        return
    
    datasets = res.json().get('data', [])
    if not datasets:
        print("No datasets found.")
        return
        
    dataset_id = datasets[0]['id']
    print(f"Using dataset: {dataset_id}")
    
    # 2. List Documents
    res = requests.get(f"{API_BASE}/admin/datasets/{dataset_id}/documents?page=1&page_size=10")
    if res.status_code != 200:
        print(f"Failed to list documents: {res.text}")
        return
        
    docs = res.json().get('data', {}).get('docs', [])
    if not docs:
        print("No documents found in dataset.")
        return
        
    print(f"Doc structure: {docs[0]}")
    doc_id = docs[0]['id']
    doc_name = docs[0]['name']
    run_status = docs[0].get('run', 'UNKNOWN')
    print(f"Using document: {doc_id} ({doc_name}), status: {run_status}")
    
    # 3. Run Parsing
    print(f"Triggering parsing for {doc_id}...")
    payload = {"doc_ids": [doc_id]}
    res = requests.post(f"{API_BASE}/admin/datasets/{dataset_id}/documents/run", json=payload)
    
    print(f"Run response code: {res.status_code}")
    print(f"Run response body: {res.text}")
    
    if res.status_code == 200:
        # 4. Check status again
        time.sleep(2)
        res = requests.get(f"{API_BASE}/admin/datasets/{dataset_id}/documents?page=1&page_size=10")
        docs = res.json().get('data', {}).get('docs', [])
        for d in docs:
            if d['id'] == doc_id:
                print(f"Document status after run: {d.get('run', 'UNKNOWN')}")
                break

if __name__ == "__main__":
    test_parsing()
