# 单元测试：验证非空知识库的删除保护机制
import requests
import time

BASE_URL = "http://localhost:8083/api"

def test_delete_non_empty():
    print("Testing Delete Non-Empty Dataset...")
    
    # 1. Create Dataset
    print("1. Creating Dataset...")
    res = requests.post(f"{BASE_URL}/admin/datasets", json={"name": "test_delete_fail"})
    if res.status_code != 200:
        print(f"Failed to create: {res.text}")
        return
    
    dataset_id = res.json().get('data', {}).get('id')
    print(f"   Created dataset: {dataset_id}")
    
    # 2. Upload Document
    print("2. Uploading Document...")
    with open("test_doc.txt", "w") as f:
        f.write("This is a test document content.")
        
    with open("test_doc.txt", "rb") as f:
        files = {'file': ('test_doc.txt', f, 'text/plain')}
        res = requests.post(f"{BASE_URL}/admin/datasets/{dataset_id}/documents", files=files)
        
    if res.status_code != 200:
        print(f"Failed to upload: {res.text}")
    else:
        print("   Document uploaded.")
        
    # 3. Try to Delete Dataset directly
    print("3. Deleting Dataset (with document inside)...")
    res = requests.delete(f"{BASE_URL}/admin/datasets/{dataset_id}")
    print(f"   Delete response: {res.status_code} {res.text}")
    
    # 4. Verify if it still exists
    print("4. Verifying existence...")
    res = requests.get(f"{BASE_URL}/admin/datasets")
    datasets = res.json().get('data', [])
    found = any(d['id'] == dataset_id for d in datasets)
    
    if found:
        print("   FAILURE: Dataset still exists!")
    else:
        print("   SUCCESS: Dataset deleted.")

if __name__ == "__main__":
    test_delete_non_empty()
