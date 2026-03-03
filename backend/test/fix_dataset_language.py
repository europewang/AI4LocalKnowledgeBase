
import requests
import json

# Use localhost:8084 directly as RAGFlow server is exposed on 8084
API_BASE = "http://localhost:8084/api"
API_KEY = "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4"

DATASET_IDS = [
    "5044f7d911f011f1bad5ce2d148aedaa",
    "27acc023031611f1a0acd65c412d585b"
]

def update_dataset_language(dataset_id):
    # Try PUT /api/v1/datasets (plural) - often used for update
    url = f"{API_BASE}/v1/datasets"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }
    data = {
        "id": dataset_id,
        "language": "Chinese"
    }
    
    print(f"Updating dataset {dataset_id} language to Chinese...")
    
    # Attempt 1: PUT /api/v1/datasets
    print(f"Attempt 1: PUT {url} ...")
    try:
        res = requests.put(url, headers=headers, json=data)
        if res.status_code == 200 and res.json().get('code') == 0:
            print("Success (PUT /datasets).")
            print(json.dumps(res.json(), indent=2, ensure_ascii=False))
            return
        else:
            print(f"Failed: {res.status_code} - {res.text}")
    except Exception as e:
        print(f"Exception: {e}")

    # Attempt 2: PUT /api/v1/datasets/{id}
    url2 = f"{API_BASE}/v1/datasets/{dataset_id}"
    print(f"Attempt 2: PUT {url2} ...")
    try:
        res = requests.put(url2, headers=headers, json={"language": "Chinese", "name": "updated_name"}) # Try adding name
        if res.status_code == 200 and res.json().get('code') == 0:
            print("Success (PUT /datasets/{id}).")
            print(json.dumps(res.json(), indent=2, ensure_ascii=False))
            return
        else:
            print(f"Failed: {res.status_code} - {res.text}")
    except Exception as e:
        print(f"Exception: {e}")
        
    # Attempt 3: POST /api/v1/dataset/save (Retry just in case)
    url3 = f"{API_BASE}/v1/dataset/save"
    print(f"Attempt 3: POST {url3} ...")
    try:
        res = requests.post(url3, headers=headers, json=data)
        if res.status_code == 200 and res.json().get('code') == 0:
            print("Success (POST /dataset/save).")
            print(json.dumps(res.json(), indent=2, ensure_ascii=False))
            return
        else:
            print(f"Failed: {res.status_code} - {res.text}")
    except Exception as e:
        print(f"Exception: {e}")

    # Attempt 4: POST /api/v1/datasets/save (Maybe plural?)
    url4 = f"{API_BASE}/v1/datasets/save"
    print(f"Attempt 4: POST {url4} ...")
    try:
        res = requests.post(url4, headers=headers, json=data)
        if res.status_code == 200 and res.json().get('code') == 0:
            print("Success (POST /datasets/save).")
            print(json.dumps(res.json(), indent=2, ensure_ascii=False))
            return
        else:
            print(f"Failed: {res.status_code} - {res.text}")
    except Exception as e:
        print(f"Exception: {e}")

def trigger_reparsing(dataset_id, doc_ids):
    # Retrieve document IDs if not provided
    if not doc_ids:
        # List documents to get IDs
        list_url = f"{API_BASE}/v1/datasets/{dataset_id}/documents?page=1&page_size=100"
        headers = {"Authorization": f"Bearer {API_KEY}"}
        res = requests.get(list_url, headers=headers)
        if res.status_code == 200:
            try:
                # Debug response
                # print(f"List docs response: {res.text[:200]}...")
                data = res.json()
                docs = []
                if isinstance(data, dict):
                    if 'data' in data:
                        inner_data = data['data']
                        if isinstance(inner_data, list):
                            docs = inner_data
                        elif isinstance(inner_data, dict) and 'docs' in inner_data:
                            docs = inner_data['docs']
                
                if docs:
                    doc_ids = [d['id'] for d in docs if isinstance(d, dict) and 'id' in d]
                else:
                    print("No docs found or unexpected format")
            except Exception as e:
                print(f"Error parsing list docs response: {e}")
                return
        else:
            print(f"Failed to list documents: {res.status_code}")
            return

    if not doc_ids:
        print("No documents to re-parse.")
        return

    url = f"{API_BASE}/v1/datasets/{dataset_id}/chunks"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }
    data = {
        "document_ids": doc_ids
    }
    
    print(f"Triggering re-parsing for {len(doc_ids)} documents in {dataset_id}...")
    try:
        res = requests.post(url, headers=headers, json=data)
        if res.status_code != 200:
            print(f"Failed to trigger parsing: {res.status_code} - {res.text}")
        else:
            print("Re-parsing triggered successfully.")
            print(json.dumps(res.json(), indent=2, ensure_ascii=False))
            
    except Exception as e:
        print(f"Exception: {e}")

if __name__ == "__main__":
    for ds_id in DATASET_IDS:
        update_dataset_language(ds_id)
        trigger_reparsing(ds_id, [])
