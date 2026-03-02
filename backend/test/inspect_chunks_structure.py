
import requests
import json
import os

# Configuration
API_BASE = "http://localhost:9380"  # Assuming backend is running locally on this port
API_KEY = "ragflow-your-api-key" # You might need to set a valid API key if auth is required, or rely on internal logic if running in dev mode without auth for some endpoints.
# However, the frontend uses /api/admin/..., let's check if we can access it.
# If auth is required, we might need to login first or use a known token. 
# For now, let's try to list datasets first to get an ID.

def get_first_dataset_and_doc():
    # 1. List Datasets
    print("Fetching datasets...")
    try:
        res = requests.get(f"{API_BASE}/api/v1/datasets?page=1&page_size=10", headers={"Authorization": f"Bearer {API_KEY}"})
        # If /api/v1 needs auth, maybe /api/admin/datasets doesn't or we can simulate frontend call?
        # The frontend calls `${API_BASE}/admin/datasets/...`. Let's try that.
        res = requests.get(f"{API_BASE}/api/admin/datasets?page=1&page_size=10")
        
        if res.status_code != 200:
            print(f"Failed to list datasets: {res.status_code} {res.text}")
            return None, None
            
        data = res.json()
        datasets = data.get('data', [])
        if not datasets:
            print("No datasets found.")
            return None, None
            
        dataset = datasets[0]
        print(f"Found dataset: {dataset['name']} ({dataset['id']})")
        
        # 2. List Documents
        res = requests.get(f"{API_BASE}/api/admin/datasets/{dataset['id']}/documents?page=1&page_size=10")
        if res.status_code != 200:
            print(f"Failed to list documents: {res.status_code} {res.text}")
            return None, None
            
        docs = res.json().get('data', [])
        if not docs:
            print("No documents found in dataset.")
            return None, None
            
        # Find a PDF if possible
        pdf_doc = next((d for d in docs if d['name'].lower().endswith('.pdf')), docs[0])
        print(f"Found document: {pdf_doc['name']} ({pdf_doc['id']})")
        
        return dataset['id'], pdf_doc['id']

    except Exception as e:
        print(f"Error: {e}")
        return None, None

def inspect_chunks(dataset_id, doc_id):
    print(f"Fetching chunks for dataset {dataset_id}, doc {doc_id}...")
    try:
        # Frontend uses: /admin/datasets/${datasetId}/documents/${docId}/chunks
        res = requests.get(f"{API_BASE}/api/admin/datasets/{dataset_id}/documents/{doc_id}/chunks?page=1&page_size=10")
        
        if res.status_code != 200:
            print(f"Failed to fetch chunks: {res.status_code} {res.text}")
            return

        data = res.json()
        chunks = data.get('data', [])
        
        if not chunks:
            print("No chunks found.")
            return

        print(f"Found {len(chunks)} chunks (showing first one detailed):")
        first_chunk = chunks[0]
        print(json.dumps(first_chunk, indent=2, ensure_ascii=False))
        
        # Check for position/bbox data
        keys = first_chunk.keys()
        print("\nAvailable keys:", list(keys))
        
        possible_pos_keys = ['position_int', 'positions', 'bbox', 'rect', 'location']
        found_pos = [k for k in keys if k in possible_pos_keys]
        if found_pos:
            print(f"Potential position keys found: {found_pos}")
        else:
            print("No obvious position keys found.")

    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    ds_id, doc_id = get_first_dataset_and_doc()
    if ds_id and doc_id:
        inspect_chunks(ds_id, doc_id)
