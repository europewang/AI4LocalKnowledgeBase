
import requests
import json

API_BASE = "http://localhost:8083/api"
KEYWORD = "围护结构倾斜"

PAIRS = [
    {"dataset_id": "5044f7d911f011f1bad5ce2d148aedaa", "document_id": "504a9a9a11f011f19592ce2d148aedaa"},
    {"dataset_id": "27acc023031611f1a0acd65c412d585b", "document_id": "2c259cd4031611f1ae0ad65c412d585b"}
]

def search_chunks(dataset_id, doc_id):
    print(f"Searching in Dataset: {dataset_id}, Document: {doc_id}")
    page = 1
    page_size = 100 # Maximize to reduce calls
    found_count = 0
    
    while True:
        url = f"{API_BASE}/admin/datasets/{dataset_id}/documents/{doc_id}/chunks?page={page}&page_size={page_size}"
        try:
            res = requests.get(url)
            if res.status_code != 200:
                print(f"Error fetching chunks: {res.status_code} - {res.text}")
                break
                
            data = res.json()
            # Debug structure
            # print(f"Response keys: {data.keys()}")
            
            # Check if data has 'data' wrapper again or direct list
            if 'data' in data and isinstance(data['data'], dict):
                 # Format: { code: 0, data: { chunks: [...], ... } }
                 chunks = data['data'].get('chunks', [])
            elif 'data' in data and isinstance(data['data'], list):
                 # Format: { code: 0, data: [...] }
                 chunks = data['data']
            else:
                 chunks = []
                 
            if not chunks:
                print("No chunks found in response.")
                break
                
            for chunk in chunks:
                content = chunk.get('content_with_weight', chunk.get('content', ''))
                if KEYWORD in content:
                    print(f"\n[FOUND MATCH] Chunk ID: {chunk.get('id')}")
                    print(f"Content snippet: ...{content[max(0, content.find(KEYWORD)-50):min(len(content), content.find(KEYWORD)+100)]}...")
                    found_count += 1
            
            total = data.get('total', 0)
            if page * page_size >= total:
                break
                
            page += 1
            
        except Exception as e:
            print(f"Exception: {e}")
            break

    print(f"Finished searching {doc_id}. Found {found_count} matches.\n")

if __name__ == "__main__":
    for pair in PAIRS:
        search_chunks(pair['dataset_id'], pair['document_id'])
