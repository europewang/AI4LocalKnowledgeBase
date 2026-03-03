
import requests
import json

API_BASE = "http://localhost:8083/api"
DATASET_IDS = [
    "5044f7d911f011f1bad5ce2d148aedaa",
    "27acc023031611f1a0acd65c412d585b"
]

def inspect_dataset():
    url = f"{API_BASE}/admin/datasets?page=1&page_size=100"
    try:
        res = requests.get(url)
        if res.status_code != 200:
            print(f"Error: {res.status_code} - {res.text}")
            return
            
        data = res.json()
        datasets = data.get('data', [])
        
        for ds_id in DATASET_IDS:
            target = next((d for d in datasets if d['id'] == ds_id), None)
            
            if target:
                print(f"\nDataset Configuration for {ds_id}:")
                print(json.dumps(target, indent=2, ensure_ascii=False))
            else:
                print(f"\nDataset {ds_id} not found.")
            
    except Exception as e:
        print(f"Exception: {e}")

if __name__ == "__main__":
    inspect_dataset()
