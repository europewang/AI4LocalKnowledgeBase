
import requests
import json
import os

# Assuming backend runs on 8083 (from application.yml)
BACKEND_URL = "http://localhost:8083"

def debug_chunks():
    # 1. List Datasets
    try:
        res = requests.get(f"{BACKEND_URL}/api/admin/datasets")
        if res.status_code != 200:
            print(f"Failed to list datasets: {res.status_code} {res.text}")
            return
        
        datasets_resp = res.json()
        print("Datasets Response:", json.dumps(datasets_resp, indent=2))
        
        datasets = datasets_resp.get("data", [])
        if not datasets:
            print("No datasets found.")
            return

        dataset_id = datasets[0]["id"]
        print(f"\nUsing Dataset ID: {dataset_id}")

        # 2. List Documents
        res = requests.get(f"{BACKEND_URL}/api/admin/datasets/{dataset_id}/documents")
        if res.status_code != 200:
            print(f"Failed to list documents: {res.status_code} {res.text}")
            return
            
        docs_resp = res.json()
        print("Documents Response:", json.dumps(docs_resp, indent=2))
        
        docs = docs_resp.get("data", {}).get("docs", []) # Check structure here
        if not docs:
            # Maybe data is just the list directly?
            if isinstance(docs_resp.get("data"), list):
                docs = docs_resp["data"]
            else:
                print("No documents found.")
                return

        doc_id = docs[0]["id"]
        print(f"\nUsing Document ID: {doc_id}")
        print(f"Document Run Status: {docs[0].get('run_status')} (run: {docs[0].get('run')})")

        # 3. List Chunks
        print(f"\nFetching chunks with page_size=10000...")
        res = requests.get(f"{BACKEND_URL}/api/admin/datasets/{dataset_id}/documents/{doc_id}/chunks?page=1&page_size=10000")
        if res.status_code != 200:
            print(f"Failed to list chunks: {res.status_code} {res.text}")
            return
            
        chunks_resp = res.json()
        # print("\nChunks Response:", json.dumps(chunks_resp, indent=2))
        
        chunks_data = chunks_resp.get("data", {})
        chunks = []
        if isinstance(chunks_data, list):
             chunks = chunks_data
        elif isinstance(chunks_data, dict):
             chunks = chunks_data.get("chunks", [])
        
        print(f"Chunks count: {len(chunks)}")
        if chunks:
            print("First chunk keys:", chunks[0].keys())
            print("First chunk content_with_weight preview:", chunks[0].get("content_with_weight")[:50] if chunks[0].get("content_with_weight") else "None")

    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    debug_chunks()
