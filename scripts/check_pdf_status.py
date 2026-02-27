
import requests
import json
import os
import sys

# RAGFlow API Configuration
RAGFLOW_API_URL = "http://localhost:8084/api/v1"
RAGFLOW_API_KEY = "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4"

def check_status():
    print("=== Checking RAGFlow PDF Parsing Status ===")
    
    # 1. List Datasets
    headers = {"Authorization": f"Bearer {RAGFLOW_API_KEY}"}
    try:
        resp = requests.get(f"{RAGFLOW_API_URL}/datasets?page=1&page_size=100", headers=headers)
        if resp.status_code != 200:
            print(f"❌ Failed to list datasets: {resp.status_code} {resp.text}")
            return
            
        data = resp.json().get("data", [])
        if isinstance(data, dict):
            datasets = data.get("data", []) # Some versions wrap differently
        else:
            datasets = data
            
        # Filter for pdf-verify datasets
        target_datasets = [d for d in datasets if d.get("name", "").startswith("pdf-verify-")]
        
        if not target_datasets:
            print("⚠️ No datasets found starting with 'pdf-verify-'")
            return
            
        print(f"Found {len(target_datasets)} 'pdf-verify' datasets. Checking the latest 5...")
        
        # Sort by create_time desc
        target_datasets.sort(key=lambda x: x.get("create_time", 0), reverse=True)
        
        for ds in target_datasets[:5]:
            ds_id = ds.get("id")
            ds_name = ds.get("name")
            print(f"\n📂 Dataset: {ds_name} (ID: {ds_id})")
            
            # 2. Get Documents for this dataset
            doc_resp = requests.get(f"{RAGFLOW_API_URL}/datasets/{ds_id}/documents?page=1&page_size=10", headers=headers)
            if doc_resp.status_code == 200:
                doc_data = doc_resp.json().get("data", {})
                docs = doc_data.get("docs", []) if isinstance(doc_data, dict) else doc_data
                
                if not docs:
                    print("   (No documents found)")
                
                for doc in docs:
                    doc_name = doc.get("name")
                    run_status = doc.get("run") # 'UNSTART', '1', 'DONE' etc
                    chunk_count = doc.get("chunk_count", 0)
                    progress = doc.get("progress", 0.0)
                    msg = doc.get("progress_msg", "").strip().replace("\n", " ")
                    if len(msg) > 50: msg = msg[:50] + "..."
                    
                    status_icon = "❓"
                    if run_status == "DONE" or chunk_count > 0:
                        status_icon = "✅ (Parsed)"
                    elif run_status == "1":
                        status_icon = "🔄 (Parsing)"
                    elif run_status == "UNSTART" or run_status == "0":
                        status_icon = "⏸️ (Not Started)"
                    else:
                        status_icon = f"⚠️ ({run_status})"
                        
                    print(f"   📄 Document: {doc_name}")
                    print(f"      Status: {status_icon}")
                    print(f"      Chunks: {chunk_count}")
                    print(f"      Progress: {progress}")
                    if msg:
                        print(f"      Msg: {msg}")
            else:
                print(f"   ❌ Failed to get docs: {doc_resp.status_code}")
                
    except Exception as e:
        print(f"❌ Error: {e}")

if __name__ == "__main__":
    check_status()
