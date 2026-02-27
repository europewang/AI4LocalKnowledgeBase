# 调试工具：遍历打印所有知识库及其文档结构

import requests
import os
import json

BASE_URL = "http://localhost:8084"
API_KEY = "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4"  # From docker-compose
HEADERS = {"Authorization": f"Bearer {API_KEY}"}

def get_datasets():
    url = f"{BASE_URL}/api/v1/datasets?page=1&page_size=100"
    try:
        resp = requests.get(url, headers=HEADERS)
        resp.raise_for_status()
        data = resp.json().get('data', {})
        if isinstance(data, dict) and 'docs' in data:
            return data['docs']
        return data if isinstance(data, list) else []
    except Exception as e:
        print(f"Error getting datasets: {e}")
        return []

def get_documents(dataset_id):
    url = f"{BASE_URL}/api/v1/datasets/{dataset_id}/documents?page=1&page_size=100"
    try:
        resp = requests.get(url, headers=HEADERS)
        resp.raise_for_status()
        data = resp.json().get('data', {})
        if isinstance(data, dict) and 'docs' in data:
            return data['docs']
        return data if isinstance(data, list) else []
    except Exception as e:
        print(f"Error getting documents for dataset {dataset_id}: {e}")
        return []

def main():
    datasets = get_datasets()
    print(f"Found {len(datasets)} datasets")
    
    found_doc = False
    found_image = False
    
    for ds in datasets:
        print(f"Dataset: {ds['name']} (ID: {ds['id']})")
        docs = get_documents(ds['id'])
        print(f"  Found {len(docs)} documents")
        
        for doc in docs:
            print(f"    Doc Type: {type(doc)}")
            print(f"    Doc Content: {doc}")
            if isinstance(doc, dict):
                print(f"    Doc: {doc.get('name')} (ID: {doc.get('id')})")
                doc_id = doc.get('id')
            else:
                print("    Skipping non-dict doc")
                continue
            
            # Test PDF proxy
            # Note: doc['id'] is the document_id used in /v1/document/get/{docId}
            # RAGFlow API seems to use the same ID.
            
            print(f"    Testing PDF proxy for doc {doc['id']}...")
            try:
                # My backend proxy
                proxy_url = f"http://localhost:8083/api/document/get/{doc['id']}"
                # We need to authenticate with backend if it requires auth, 
                # but currently DocumentController is public? 
                # Wait, backend might have security filter chain. 
                # Let's check SecurityConfig.
                
                # Assuming public or I need to add headers if secured.
                # For now let's try without auth.
                
                resp = requests.get(proxy_url, timeout=5)
                print(f"    Proxy response: {resp.status_code}, Content-Type: {resp.headers.get('Content-Type')}, Size: {len(resp.content)} bytes")
                if resp.status_code == 200 and 'application/pdf' in resp.headers.get('Content-Type', ''):
                    print("    SUCCESS: PDF Proxy works!")
                    found_doc = True
            except Exception as e:
                print(f"    Proxy failed: {e}")

            # I don't know how to find image_id easily without parsing chunks.
            # But if I have a document, I can try to see if there are chunks with images.
            # Skipping image test for now unless I find an obvious way.
            
            if found_doc:
                break
        if found_doc:
            break

if __name__ == "__main__":
    main()
