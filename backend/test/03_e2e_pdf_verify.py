# 端到端测试：从创建知识库到上传PDF的全流程验证
import requests
import json
import time
import sys
import os

# 配置
BASE_URL = "http://localhost:8083/api"
RAGFLOW_API_URL = "http://localhost:8084/api/v1"
RAGFLOW_API_KEY = "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4"
USERNAME = "zhangsan"
PDF_PATH = "/home/ubutnu/code/AI4LocalKnowledgeBase/test/data/武自然资建规[2026]1号.pdf"
DATASET_NAME = "pdf-verify-" + time.strftime("%Y%m%d-%H%M%S")

def log(msg):
    print(f"[E2E Verify] {msg}")

def step_create_dataset():
    """1. 在 RAGFlow 创建 Dataset"""
    url = f"{RAGFLOW_API_URL}/datasets"
    headers = {"Authorization": f"Bearer {RAGFLOW_API_KEY}"}
    data = {"name": DATASET_NAME}
    
    log(f"Creating dataset '{DATASET_NAME}'...")
    resp = requests.post(url, json=data, headers=headers)
    if resp.status_code == 200:
        res_json = resp.json()
        if res_json.get("code") == 0:
            dataset_id = res_json["data"]["id"]
            log(f"✅ Dataset created. ID: {dataset_id}")
            return dataset_id
        else:
            log(f"❌ Failed to create dataset. RAGFlow Msg: {res_json}")
            sys.exit(1)
    else:
        log(f"❌ Failed to create dataset. Status: {resp.status_code}, Body: {resp.text}")
        sys.exit(1)

def step_upload_file(dataset_id):
    """2. 上传 PDF 文件到 Dataset"""
    url = f"{RAGFLOW_API_URL}/datasets/{dataset_id}/documents"
    headers = {"Authorization": f"Bearer {RAGFLOW_API_KEY}"}
    
    if not os.path.exists(PDF_PATH):
        log(f"❌ PDF file not found: {PDF_PATH}")
        sys.exit(1)

    log(f"Uploading file: {PDF_PATH} ...")
    files = {'file': open(PDF_PATH, 'rb')}
    resp = requests.post(url, headers=headers, files=files)
    
    if resp.status_code == 200:
        res_json = resp.json()
        if res_json.get("code") == 0:
            log(f"✅ File uploaded successfully.")
            # RAGFlow 上传后可能需要拿到 document_id 进行解析，或者它会自动解析？
            # 这里的接口返回值通常包含 document list
            return res_json.get("data", []) # List of created documents
        else:
            log(f"❌ Failed to upload file. RAGFlow Msg: {res_json}")
            sys.exit(1)
    else:
        log(f"❌ Failed to upload file. Status: {resp.status_code}, Body: {resp.text}")
        sys.exit(1)

def step_parse_file(dataset_id, document_ids):
    """3. 触发解析 (如果不是自动的)"""
    # RAGFlow 的 /api/v1/datasets/{dataset_id}/documents 接口上传后，通常默认状态是 '1' (parsing) 
    # 或者需要手动调解析接口。假设这里需要等待解析完成。
    # 我们先检查文件状态。
    
    for doc_id in document_ids:
        log(f"Attempting to trigger parsing for {doc_id}...")
        headers = {"Authorization": f"Bearer {RAGFLOW_API_KEY}"}

        # Method 4: POST /api/v1/datasets/{dataset_id}/chunks (SDK Endpoint)
        # This is the correct endpoint for triggering parsing via API Key
        url4 = f"{RAGFLOW_API_URL}/datasets/{dataset_id}/chunks"
        log(f"Attempting Method 4 (SDK): {url4}")
        resp4 = requests.post(url4, headers=headers, json={"document_ids": [doc_id]})
        
        if resp4.status_code == 200 and resp4.json().get("code") == 0:
            log(f"✅ Method 4 success: Parsing triggered!")
            continue
        else:
            log(f"⚠️ Method 4 failed: {resp4.status_code} {resp4.text}")
            
        # If we reach here, all methods failed
        log(f"❌ Failed to trigger parsing for {doc_id} with all methods.")

    log("Waiting for parsing to complete (timeout 300s)...")
    for _ in range(60): # 5分钟超时
        all_ready = True
        
        # List documents to check status
        url = f"{RAGFLOW_API_URL}/datasets/{dataset_id}/documents?page=1&page_size=100"
        headers = {"Authorization": f"Bearer {RAGFLOW_API_KEY}"}
        resp = requests.get(url, headers=headers)
        if resp.status_code == 200:
            raw_data = resp.json().get("data", {})
            docs = []
            if isinstance(raw_data, dict) and "docs" in raw_data:
                docs = raw_data["docs"]
            elif isinstance(raw_data, list):
                docs = raw_data
            
            if not docs:
                log("⚠️ No documents found in dataset.")
                time.sleep(2)
                continue
                
            for doc in docs:
                # 修正：如果 doc 是 string，可能只是 id，或者其他异常。
                if not isinstance(doc, dict):
                    log(f"⚠️ Unexpected doc format: {doc}")
                    continue
                
                # run_status: '1'->parsing, '0'->fail? '2'->success? 
                # run='UNSTART' means it hasn't started yet.
                # status='1' usually means enabled/active.
                # If chunk_count > 0, it means parsing produced something.
                
                # Check if parsing is stuck at 0 progress
                if doc.get("chunk_count", 0) > 0:
                    continue # This one is ready
                
                # If run is UNSTART, we might need to re-trigger or wait
                # But we already triggered it. Maybe RAGFlow needs time to pick it up.
                if doc.get("run") == "UNSTART":
                    # Try to trigger parsing again for this doc
                    if _ % 5 == 0: # Every 25 seconds
                         log(f"Re-triggering parsing for doc {doc.get('id')}...")
                         requests.post(f"{RAGFLOW_API_URL}/datasets/{dataset_id}/documents/{doc.get('id')}/run", headers=headers, json={"run": 1})
                
                # Log status for debugging
                run_status = doc.get("run", "N/A") # 'run' field is the status enum usually? Or 'status'?
                # API response has 'run': 'UNSTART' or '1'
                progress = doc.get("progress", 0)
                msg = doc.get("progress_msg", "")
                if _ % 2 == 0: # Log every ~10s
                    log(f"Waiting for doc {doc.get('id')[-6:]}... Status: {run_status}, Progress: {progress}, Msg: {msg}")
                
                all_ready = False
                break
            
            if all_ready and len(docs) > 0:
                log(f"✅ All documents parsed. Total docs: {len(docs)}")
                return
        
        time.sleep(5)
    
    log("❌ Parsing timeout. Last doc status:")
    # Print status of unparsed docs
    for doc in docs:
         if doc.get("chunk_count", 0) == 0:
             log(f" - ID: {doc.get('id')}, Run: {doc.get('run')}, Progress: {doc.get('progress')}, Msg: {doc.get('progress_msg')}")

    sys.exit(1)
    
    log("❌ Parsing timeout.")
    sys.exit(1)

def step_grant_permission(dataset_id):
    """4. 授权给 zhangsan"""
    # 确保用户存在
    requests.post(f"{BASE_URL}/admin/user", json={"username": USERNAME, "role": "user"})
    
    grant_url = f"{BASE_URL}/admin/permission/grant"
    grant_data = {
        "username": USERNAME,
        "resource_type": "DATASET",
        "resource_id": dataset_id
    }
    log(f"Granting permission to {USERNAME}...")
    resp = requests.post(grant_url, json=grant_data)
    if resp.status_code == 200:
        log("✅ Permission granted.")
    else:
        log(f"❌ Grant permission failed: {resp.text}")
        sys.exit(1)

def step_chat_test():
    """5. 发起提问"""
    question = "围护结构倾斜的建筑空间的建筑面积规定是什么"
    url = f"{BASE_URL}/chat/completions"
    headers = {"X-User-Name": USERNAME}
    payload = {"question": question, "stream": True}
    
    log(f"Asking question: {question}")
    
    full_answer = ""
    try:
        with requests.post(url, json=payload, headers=headers, stream=True) as resp:
            if resp.status_code != 200:
                log(f"❌ Chat request failed. Status: {resp.status_code}")
                sys.exit(1)
            
            for line in resp.iter_lines():
                if line:
                    decoded = line.decode('utf-8')
                    if decoded.startswith("data:"):
                        data_str = decoded[5:].strip()
                        if data_str == "[DONE]":
                            break
                        try:
                            json_data = json.loads(data_str)
                            ans = json_data.get("answer", "")
                            full_answer += ans
                            # print(ans, end="", flush=True) 
                        except:
                            pass
    except Exception as e:
        log(f"❌ Exception in chat: {e}")
        sys.exit(1)

    log(f"✅ Chat finished.")
    log(f"Answer: {full_answer}")
    
    # 简单验证关键词
    if "1/2" in full_answer or "2.20m" in full_answer or "2.2" in full_answer:
        log("✅ Answer contains expected keywords (1/2, 2.20m, etc).")
    else:
        log("⚠️ Answer might not be accurate. Please check manually.")

if __name__ == "__main__":
    print("=== Starting End-to-End PDF Verification ===")
    
    # 1. Create Dataset
    ds_id = step_create_dataset()
    
    # 2. Upload PDF
    step_upload_file(ds_id)
    
    # 3. Wait for Parse
    # 注意：RAGFlow 上传后通常需要手动触发 parse，或者配置自动 parse。
    # 这里我们尝试调用 parse 接口，或者如果默认自动 parse 则只需等待。
    # 假设默认策略不自动 parse，我们需要调用: PUT /api/v1/datasets/{id}/documents 
    # 但 RAGFlow API 有点复杂，通常上传后默认状态是 '1' (parsing) 如果使用了某些配置。
    # 我们先观察等待，如果一直不解析，可能需要补充 parse 调用。
    # 补充：显式调用 parse
    parse_url = f"{RAGFLOW_API_URL}/datasets/{ds_id}/documents" # PUT to update/parse? 
    # 实际上 RAGFlow API: POST /datasets/{id}/documents 上传文件后，
    # 需要 POST /datasets/{id}/chunks? (No)
    # Let's try to assume it parses automatically or we trigger it.
    # Official API: POST /api/v1/datasets/{dataset_id}/documents (upload) -> returns document list.
    # Then PUT /api/v1/datasets/{dataset_id}/documents (update status/parse).
    # Body: {"ids": [doc_id], "run": 1}
    
    # 获取 doc id
    docs_resp = requests.get(f"{RAGFLOW_API_URL}/datasets/{ds_id}/documents?page=1&page_size=10", headers={"Authorization": f"Bearer {RAGFLOW_API_KEY}"})
    if docs_resp.status_code == 200:
        raw_data = docs_resp.json().get("data", {})
        # 修正：data 可能是 dict 包含 'docs' 列表
        if isinstance(raw_data, dict) and "docs" in raw_data:
             doc_list = raw_data["docs"]
        elif isinstance(raw_data, list):
             doc_list = raw_data
        else:
             doc_list = []

        if doc_list:
            log(f"Document list found: {len(doc_list)} docs")
            # 修正：检查 doc_list 元素类型
            doc_ids = []
            if len(doc_list) > 0:
                if isinstance(doc_list[0], dict):
                    doc_ids = [d["id"] for d in doc_list]
                elif isinstance(doc_list[0], str):
                    doc_ids = doc_list
            
            if doc_ids:
                log(f"Triggering parsing for docs: {doc_ids}")
                parse_payload = {"ids": doc_ids, "run": 1}
                # PUT 方法触发解析
                put_resp = requests.put(f"{RAGFLOW_API_URL}/datasets/{ds_id}/documents", json=parse_payload, headers={"Authorization": f"Bearer {RAGFLOW_API_KEY}"})
                if put_resp.status_code != 200:
                     log(f"⚠️ Trigger parse warning: {put_resp.text}")
    
    step_parse_file(ds_id, doc_ids)
    
    # 4. Grant Permission
    step_grant_permission(ds_id)
    
    # 5. Chat
    step_chat_test()
    
    print("=== Verification Completed ===")
