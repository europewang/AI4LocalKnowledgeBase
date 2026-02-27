# 调试工具：直接查询RAGFlow以获取图片ID和元数据
import requests
import json
import os

BASE_URL = "http://localhost:8083/api"
# RAGFlow API for direct querying to find image_ids
RAGFLOW_URL = "http://localhost:8084" 
API_KEY = "ragflow-MTAyNDEzNDMyNDMxNTEwNDAw.U_uA3A.bK_t8N2h-jL7XNqR49tGj_y_c8o" # Hardcoded from application.yml or previous context if known. 
# Wait, I don't have the API Key handy in the context I just read. 
# I should read application.yml to get the key.

def get_api_key():
    # Attempt to read from application.yml
    # try:
    #     with open('backend/src/main/resources/application.yml', 'r') as f:
    #         for line in f:
    #             if 'api-key:' in line:
    #                 return line.split('api-key:')[1].strip()
    # except:
    #     pass
    return "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4" # Key from docker-compose-ragflow.yml

KEY = get_api_key()
HEADERS = {"Authorization": f"Bearer {KEY}"}

def find_image_id():
    print(f"Using API Key: {KEY[:10]}...")
    
    # 1. List Datasets
    url = f"{RAGFLOW_URL}/api/v1/datasets?page=1&page_size=100"
    try:
        resp = requests.get(url, headers=HEADERS)
        print(f"Datasets response status: {resp.status_code}")
        # print(f"Datasets response body: {resp.text[:500]}") # Debug
        
        resp.raise_for_status()
        json_resp = resp.json()
        
        # Check if response is successful
        if json_resp.get('code') != 0:
            print(f"Error listing datasets: {json_resp.get('message')}")
            return None

        data = json_resp.get('data', [])
        
        # Handle different response structures
        if isinstance(data, dict): 
            data = data.get('docs', []) or []
        elif not isinstance(data, list):
            print(f"Unexpected data type for datasets: {type(data)}")
            data = []
            
        print(f"Found {len(data)} datasets.")
        
        for ds in data:
            ds_id = ds['id']
            print(f"Checking dataset {ds_id} ({ds.get('name')})...")
            
            # 2. List Chunks (need to find how to list chunks via API)
            # RAGFlow API: GET /api/v1/datasets/{dataset_id}/documents
            doc_url = f"{RAGFLOW_URL}/api/v1/datasets/{ds_id}/documents?page=1&page_size=20"
            doc_resp = requests.get(doc_url, headers=HEADERS)
            if doc_resp.status_code != 200:
                print(f"  Failed to list docs: {doc_resp.status_code}")
                continue
                
            docs = doc_resp.json().get('data', [])
            if isinstance(docs, dict):
                 docs = docs.get('docs', [])
                 
            for doc in docs:
                doc_id = doc['id']
                
                # Check for thumbnail
                if 'thumbnail' in doc and doc['thumbnail']:
                    thumb = doc['thumbnail']
                    print(f"  FOUND THUMBNAIL: {thumb} in doc {doc_id}")
                    return thumb

                # 3. Get Chunks for document
                # API: GET /api/v1/datasets/{dataset_id}/documents/{document_id}/chunks
                chunk_url = f"{RAGFLOW_URL}/api/v1/datasets/{ds_id}/documents/{doc_id}/chunks?page=1&page_size=100"
                chunk_resp = requests.get(chunk_url, headers=HEADERS)
                if chunk_resp.status_code == 200:
                    chunks = chunk_resp.json().get('data', [])
                    if isinstance(chunks, dict): chunks = chunks.get('chunks', [])
                    
                    for chunk in chunks:
                        if 'img_id' in chunk and chunk['img_id']:
                            print(f"  FOUND IMAGE ID: {chunk['img_id']} in doc {doc_id}")
                            return chunk['img_id']
                        # Sometimes it might be in 'content_with_weight' or similar, but 'img_id' is standard
    except Exception as e:
        print(f"Error finding image: {e}")
    
    return None

def test_proxy(image_id):
    if not image_id:
        print("No image ID found to test.")
        return

    url = f"{BASE_URL}/document/image/{image_id}"
    print(f"Testing Proxy URL: {url}")
    try:
        resp = requests.get(url)
        print(f"Status: {resp.status_code}")
        print(f"Content-Type: {resp.headers.get('Content-Type')}")
        print(f"Size: {len(resp.content)} bytes")
        
        if resp.status_code == 200 and 'image' in resp.headers.get('Content-Type', ''):
            print("SUCCESS: Image Proxy works!")
        else:
            print("FAILURE: Image Proxy failed.")
            print(resp.text[:200])
    except Exception as e:
        print(f"Error testing proxy: {e}")

if __name__ == "__main__":
    img_id = find_image_id()
    if img_id:
        test_proxy(img_id)
    else:
        print("Could not find any image in the knowledge base to test.")
