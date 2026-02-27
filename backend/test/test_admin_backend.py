# 单元测试：管理后台核心接口（创建知识库、用户管理）
import requests
import json
import time

BASE_URL = "http://localhost:8083/api"

def test_admin_endpoints():
    print("Testing Admin Endpoints...")

    # 1. Create Dataset
    print("\n1. Creating Dataset 'test_admin_ds'...")
    try:
        res = requests.post(f"{BASE_URL}/admin/datasets", json={"name": "test_admin_ds"})
        if res.status_code == 200:
            data = res.json()
            if data.get('code') == 0:
                dataset_id = data['data']['id']
                print(f"Success: Created dataset {dataset_id}")
            else:
                print(f"Failed: {data}")
                return
        else:
            print(f"Failed: HTTP {res.status_code} {res.text}")
            return
    except Exception as e:
        print(f"Error creating dataset: {e}")
        return

    # 2. List Datasets
    print("\n2. Listing Datasets...")
    try:
        res = requests.get(f"{BASE_URL}/admin/datasets")
        if res.status_code == 200:
            print(f"     DEBUG: List Datasets response: {res.text[:200]}...") # Print first 200 chars
            datasets = res.json().get('data', [])
            found = any(d['id'] == dataset_id for d in datasets)
            if found:
                print(f"Success: Found dataset {dataset_id} in list")
            else:
                print(f"Failed: Dataset {dataset_id} not found in list")
        else:
            print(f"Failed: HTTP {res.status_code}")
    except Exception as e:
        print(f"Error listing datasets: {e}")

    # 3. List Users
    print("\n3. Listing Users...")
    try:
        res = requests.get(f"{BASE_URL}/admin/users")
        if res.status_code == 200:
            users = res.json()
            if len(users) > 0:
                username = users[0]['username']
                print(f"Success: Found {len(users)} users. Testing with user '{username}'")
            else:
                print("Warning: No users found to test permissions")
                username = None
        else:
            print(f"Failed: HTTP {res.status_code}")
            username = None
    except Exception as e:
        print(f"Error listing users: {e}")
        username = None

    # 4. Sync Permissions (if user exists)
    if username:
        print(f"\n4. Syncing Permissions for {username}...")
        try:
            # Grant permission to the new dataset
            res = requests.post(f"{BASE_URL}/admin/permission/sync", json={
                "username": username,
                "dataset_ids": [dataset_id]
            })
            if res.status_code == 200:
                print("Success: Permissions synced")
            else:
                print(f"Failed: HTTP {res.status_code} {res.text}")

            # Verify permissions
            res = requests.get(f"{BASE_URL}/admin/permission/{username}")
            if res.status_code == 200:
                perms = res.json()
                has_perm = any(p['resourceId'] == dataset_id for p in perms)
                if has_perm:
                    print(f"Success: User {username} has permission for {dataset_id}")
                else:
                    print(f"Failed: User {username} missing permission for {dataset_id}")
            else:
                print(f"Failed: HTTP {res.status_code}")
        except Exception as e:
            print(f"Error syncing permissions: {e}")

    # 4.5 Document Operations
    print("\n4.5 Testing Document Operations...")
    try:
        # Create a dummy file
        with open("test_doc.txt", "w") as f:
            f.write("This is a test document content for upload verification.")
            
        # Upload Document
        print("   - Uploading document...")
        with open("test_doc.txt", "rb") as f:
            files = {'file': ('test_doc.txt', f, 'text/plain')}
            res = requests.post(f"{BASE_URL}/admin/datasets/{dataset_id}/documents", files=files)
            
        if res.status_code == 200:
            print("     Success: Document uploaded")
        else:
            print(f"     Failed to upload: {res.status_code} {res.text}")
            
        # List Documents
        print("   - Listing documents...")
        time.sleep(2) # Wait for processing
        res = requests.get(f"{BASE_URL}/admin/datasets/{dataset_id}/documents")
        doc_id = None
        if res.status_code == 200:
            print(f"     DEBUG: List response: {res.text}")
            docs = res.json().get('data', {}).get('docs', [])
            # Fallback if docs is directly in data
            if not docs and isinstance(res.json().get('data'), list):
                 docs = res.json().get('data')
            if len(docs) > 0:
                doc_id = docs[0]['id']
                print(f"     Success: Found {len(docs)} documents. First ID: {doc_id}")
                
                # Test Run Document
                print(f"   - Running document {doc_id}...")
                run_res = requests.post(f"{BASE_URL}/admin/datasets/{dataset_id}/documents/run", 
                                      json={"doc_ids": [doc_id]})
                if run_res.status_code == 200:
                    print("     Success: Document run triggered")
                else:
                    print(f"     Failed to run document: {run_res.status_code} {run_res.text}")
                    
                # Test Get Document File
                print(f"   - Getting document file {doc_id}...")
                file_res = requests.get(f"{BASE_URL}/admin/datasets/{dataset_id}/documents/{doc_id}/file")
                if file_res.status_code == 200:
                    print(f"     Success: Document file retrieved (size: {len(file_res.content)} bytes)")
                else:
                    print(f"     Failed to get document file: {file_res.status_code} {file_res.text}")

            else:
                print("     Warning: No documents found after upload")
        else:
             print(f"     Failed to list: {res.status_code} {res.text}")
             
        # Delete Document
        if doc_id:
            print(f"   - Deleting document {doc_id}...")
            res = requests.delete(f"{BASE_URL}/admin/datasets/{dataset_id}/documents", json={"ids": [doc_id]})
            if res.status_code == 200:
                 print("     Success: Document deleted")
            else:
                 print(f"     Failed to delete document: {res.status_code} {res.text}")

    except Exception as e:
        print(f"Error in document operations: {e}")

    # 5. Delete Dataset
    print(f"\n5. Deleting Dataset {dataset_id}...")
    try:
        res = requests.delete(f"{BASE_URL}/admin/datasets/{dataset_id}")
        if res.status_code == 200:
            print("Success: Dataset deleted")
        else:
            print(f"Failed: HTTP {res.status_code} {res.text}")
    except Exception as e:
        print(f"Error deleting dataset: {e}")

if __name__ == "__main__":
    test_admin_endpoints()
