# 单元测试：验证文档分块数据的获取与解析
import requests
import json
import sys

API_BASE = "http://localhost:8083/api"
# API_BASE = "http://localhost:8086/api" # Backend

def test_chunks():
    # 1. Get a dataset and a document
    res = requests.get(f"{API_BASE}/admin/datasets?page=1&page_size=1")
    if res.status_code != 200:
        print(f"Failed to list datasets: {res.text}")
        return

    datasets = res.json().get('data', [])
    if not datasets:
        print("No datasets found.")
        return
        
    dataset_id = datasets[0]['id']
    print(f"Dataset ID: {dataset_id}")
    
    res = requests.get(f"{API_BASE}/admin/datasets/{dataset_id}/documents?page=1&page_size=1")
    if res.status_code != 200:
        print(f"Failed to list documents: {res.text}")
        return
        
    docs = res.json().get('data', {}).get('docs', [])
    if not docs:
        print("No documents found.")
        return
        
    doc = docs[0]
    print("--- Document JSON Structure ---")
    print(json.dumps(doc, indent=2))
    print("--- End Document JSON ---")
    
    doc_id = doc['id']
    print(f"Document ID: {doc_id}")
    print(f"Document Name: {doc['name']}")
    print(f"Run Status: {doc.get('run_status')}")
    print(f"Chunk Num: {doc.get('chunk_num')}")
    
    # 2. Try to fetch chunks via Backend API
    print(f"Fetching chunks for document {doc_id}...")
    try:
        # Using the new backend endpoint: /api/admin/datasets/{id}/documents/{docId}/chunks
        chunk_res = requests.get(f"{API_BASE}/admin/datasets/{dataset_id}/documents/{doc_id}/chunks?page=1&page_size=10")
        
        if chunk_res.status_code == 200:
            chunks_data = chunk_res.json()
            print("Successfully fetched chunks!")
            # RAGFlow returns { data: [...] } or { data: { chunks: [...] } } or just [...]
            # Let's print the structure
            print(json.dumps(chunks_data, indent=2))
            
            # Basic validation
            data = chunks_data.get('data')
            if isinstance(data, list):
                print(f"Found {len(data)} chunks directly in data.")
            elif isinstance(data, dict) and 'chunks' in data:
                print(f"Found {len(data['chunks'])} chunks in data.chunks.")
            else:
                print("Unexpected data format.")
        else:
            print(f"Failed to fetch chunks via backend: {chunk_res.status_code} {chunk_res.text}")
            
    except Exception as e:
        print(f"Error calling backend chunk API: {e}")


if __name__ == "__main__":
    test_chunks()
