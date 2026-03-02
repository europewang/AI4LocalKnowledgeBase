import requests
import json
import time
import os
import sys

# Configuration
RAGFLOW_HOST = "http://localhost:8084"
API_KEY = "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4"  # Default from codebase
PDF_PATH = "/home/ubutnu/code/AI4LocalKnowledgeBase/test/data/武自然资建规[2026]1号.pdf"

def get_headers():
    return {
        "Authorization": f"Bearer {API_KEY}"
    }

def check_health():
    """Check if RAGFlow is reachable."""
    try:
        resp = requests.get(f"{RAGFLOW_HOST}/api/v1/version", timeout=2)
        # 404 is fine for version, at least it connects. But ideally 200.
        # Actually RAGFlow usually has /api/v1/version or just check root.
        print(f"Health check status: {resp.status_code}")
        return True
    except Exception as e:
        print(f"Health check failed: {e}")
        return False

def create_dataset(name):
    """Create a new dataset."""
    url = f"{RAGFLOW_HOST}/api/v1/datasets"
    data = {"name": name}
    print(f"Creating dataset '{name}'...")
    resp = requests.post(url, headers=get_headers(), json=data)
    if resp.status_code != 200:
        print(f"Failed to create dataset: {resp.text}")
        sys.exit(1)
    
    res_json = resp.json()
    if res_json.get("code") != 0:
        print(f"API Error creating dataset: {res_json}")
        sys.exit(1)
        
    ds_id = res_json["data"]["id"]
    print(f"Dataset created. ID: {ds_id}")
    
    # Check dataset details
    print(f"Dataset details: {res_json['data']}")
    
    return ds_id

def create_dataset_with_model(name, embedding_model):
    """Create a new dataset with specific embedding model."""
    url = f"{RAGFLOW_HOST}/api/v1/datasets"
    data = {
        "name": name,
        "embedding_model": embedding_model
    }
    print(f"Creating dataset '{name}' with model '{embedding_model}'...")
    resp = requests.post(url, headers=get_headers(), json=data)
    if resp.status_code != 200:
        print(f"Failed to create dataset: {resp.text}")
        sys.exit(1)
    
    res_json = resp.json()
    if res_json.get("code") != 0:
        print(f"API Error creating dataset: {res_json}")
        sys.exit(1)
        
    ds_id = res_json["data"]["id"]
    print(f"Dataset created. ID: {ds_id}")
    print(f"Dataset details: {res_json['data']}")
    return ds_id

def upload_document(dataset_id, file_path):
    """Upload a PDF document."""
    url = f"{RAGFLOW_HOST}/api/v1/datasets/{dataset_id}/documents"
    if not os.path.exists(file_path):
        print(f"File not found: {file_path}")
        sys.exit(1)
        
    print(f"Uploading file: {file_path}...")
    with open(file_path, 'rb') as f:
        files = {'file': f}
        resp = requests.post(url, headers=get_headers(), files=files)
        
    if resp.status_code != 200:
        print(f"Failed to upload file: {resp.text}")
        sys.exit(1)
        
    res_json = resp.json()
    if res_json.get("code") != 0:
        print(f"API Error uploading file: {res_json}")
        sys.exit(1)
        
    # The response usually contains a list of documents or the created document
    # It seems it returns {data: [...]} where ... are document objects
    data = res_json.get("data", [])
    if isinstance(data, list) and len(data) > 0:
        doc_id = data[0]["id"]
        print(f"File uploaded. Doc ID: {doc_id}")
        return doc_id
    elif isinstance(data, dict): # Single object
        doc_id = data.get("id")
        print(f"File uploaded. Doc ID: {doc_id}")
        return doc_id
    
    print(f"Could not find doc ID in response: {res_json}")
    # Fallback: list documents to find the latest one
    return None

def trigger_parsing(dataset_id, doc_ids):
    """Trigger parsing for documents."""
    # Based on RagFlowClient.java: POST /api/v1/datasets/{dataset_id}/chunks
    url = f"{RAGFLOW_HOST}/api/v1/datasets/{dataset_id}/chunks"
    data = {"document_ids": doc_ids}
    
    print(f"Triggering parsing for docs: {doc_ids}...")
    resp = requests.post(url, headers=get_headers(), json=data)
    
    if resp.status_code != 200:
        # Try fallback endpoint if this fails (some versions use different endpoints)
        print(f"Warning: /chunks endpoint failed ({resp.status_code}). Trying /run...")
        # Some older versions or different implementations might use /run
        url_fallback = f"{RAGFLOW_HOST}/api/v1/datasets/{dataset_id}/documents/run" 
        # But wait, RagFlowClient.java says /chunks is correct.
        # Let's check response.
        print(f"Response: {resp.text}")
    else:
        print("Parsing triggered successfully.")
        return True
    return False

def wait_for_parsing(dataset_id, doc_id, timeout=60):
    """Poll document status until parsed."""
    url = f"{RAGFLOW_HOST}/api/v1/datasets/{dataset_id}/documents?page=1&page_size=100"
    print(f"Waiting for parsing to complete for doc {doc_id}...")
    
    start_time = time.time()
    while time.time() - start_time < timeout:
        resp = requests.get(url, headers=get_headers())
        if resp.status_code == 200:
            data = resp.json().get("data", {}).get("docs", [])
            found = False
            for doc in data:
                # Print doc ID for debugging
                print(f"Checking doc: {doc['id']} (Status: {doc.get('run_status')})")
                
                if doc["id"] == doc_id:
                    found = True
                    status = str(doc.get("run_status", "0"))
                    progress = doc.get("run_progress", 0)
                    error = doc.get("run_error", "")
                    print(f"  -> Match! Status: {status}, Progress: {progress}%, Error: {error}")
                    
                    if status == "1": # Parsed successfully
                        print("Document parsed successfully!")
                        return True
            
            if not found:
                print(f"Document {doc_id} not found in list yet.")
        else:
            print(f"Error fetching documents: {resp.status_code}")
            
        time.sleep(2)
        
    print("Timeout waiting for parsing.")
    return False

def create_conversation(dataset_id):
    """Create a chat/conversation."""
    url = f"{RAGFLOW_HOST}/api/v1/chats"
    data = {
        "name": f"test_chat_{int(time.time())}",
        "dataset_ids": [dataset_id]
    }
    print("Creating conversation...")
    resp = requests.post(url, headers=get_headers(), json=data)
    if resp.status_code != 200:
        print(f"Failed to create conversation: {resp.text}")
        sys.exit(1)
        
    res_json = resp.json()
    conv_id = res_json["data"]["id"]
    print(f"Conversation created. ID: {conv_id}")
    return conv_id

def chat_stream(conversation_id, question):
    """Send a question and stream the response."""
    url = f"{RAGFLOW_HOST}/api/v1/chats/{conversation_id}/completions"
    data = {
        "question": question,
        "stream": True
    }
    print(f"\nAsking: {question}")
    print("-" * 40)
    
    try:
        resp = requests.post(url, headers=get_headers(), json=data, stream=True)
        if resp.status_code != 200:
            print(f"Chat failed with status {resp.status_code}: {resp.text}")
            return

        for line in resp.iter_lines():
            if line:
                line_str = line.decode('utf-8')
                if line_str.startswith("data:"):
                    json_str = line_str[5:].strip()
                    if json_str == "[DONE]":
                        break
                    try:
                        chunk = json.loads(json_str)
                        if chunk.get("code") != 0:
                            print(f"\nError in chunk: {chunk}")
                            continue
                        
                        answer = chunk.get("data", {}).get("answer", "")
                        print(answer, end="", flush=True)
                        
                        # Check for reference
                        refs = chunk.get("data", {}).get("reference", {})
                        if refs:
                            print(f"\n\n[References Found]: {len(refs)} chunks")
                    except json.JSONDecodeError:
                        pass
    except Exception as e:
        print(f"\nException during chat: {e}")
    print("\n" + "-" * 40)

def main():
    if not check_health():
        print("RAGFlow service is not reachable. Exiting.")
        sys.exit(1)

    # 1. Create Dataset
    # Try using the correct embedding model name. 
    # Based on previous experience or typical Xinference integration, it might be "bge-m3" or "bge-m3@Xinference"
    # Let's try "bge-m3" first, as that's what we launched in Xinference.
    ds_id = create_dataset_with_model("Debug_Dataset_" + str(int(time.time())), "bge-m3")
    
    # 2. Upload Document
    doc_id = upload_document(ds_id, PDF_PATH)
    if not doc_id:
        print("Failed to get document ID.")
        sys.exit(1)
        
    # 3. Trigger Parsing
    trigger_parsing(ds_id, [doc_id])
    
    # 4. Wait for Parsing
    if not wait_for_parsing(ds_id, doc_id, timeout=120): # Give it 2 mins
        print("Parsing failed or timed out. Cannot proceed to chat.")
        sys.exit(1)
        
    # 5. Create Conversation
    conv_id = create_conversation(ds_id)
    
    # 6. Chat
    chat_stream(conv_id, "围护结构倾斜的建筑空间的建筑面积规定是什么")

if __name__ == "__main__":
    main()
