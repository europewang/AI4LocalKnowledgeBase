import requests
import json
import sys

# Configuration
BACKEND_HOST = "http://localhost:8083"

def test_admin_datasets():
    print(f"\n[INFO] Testing Admin API: List Datasets...")
    try:
        resp = requests.get(f"{BACKEND_HOST}/api/admin/datasets")
        if resp.status_code == 200:
            data = resp.json().get("data", [])
            print(f"[SUCCESS] Retrieved {len(data)} datasets.")
            for ds in data:
                print(f"  - {ds['id']}: {ds['name']}")
            return data
        else:
            print(f"[FAIL] Failed to list datasets. Status: {resp.status_code}")
            return []
    except Exception as e:
        print(f"[FAIL] Connection error: {e}")
        return []

def test_create_user(username):
    print(f"\n[INFO] Testing Admin API: Create User '{username}'...")
    try:
        resp = requests.post(
            f"{BACKEND_HOST}/api/admin/user",
            json={"username": username, "role": "user"}
        )
        if resp.status_code == 200:
            print(f"[SUCCESS] User created: {resp.json()}")
        else:
            print(f"[WARN] Create user failed (maybe exists). Status: {resp.status_code}")
    except Exception as e:
        print(f"[FAIL] Create user error: {e}")

def test_grant_permission(username, dataset_id):
    print(f"\n[INFO] Testing Admin API: Grant Permission...")
    try:
        resp = requests.post(
            f"{BACKEND_HOST}/api/admin/permission/grant",
            json={
                "username": username,
                "resource_type": "DATASET",
                "resource_id": dataset_id
            }
        )
        if resp.status_code == 200:
            print(f"[SUCCESS] Permission granted: {resp.text}")
        else:
            print(f"[FAIL] Grant permission failed. Status: {resp.status_code}")
    except Exception as e:
        print(f"[FAIL] Grant permission error: {e}")

def main():
    datasets = test_admin_datasets()
    if not datasets:
        print("[WARN] No datasets found. Please create a KB in RAGFlow first (or use test_ragflow_e2e.py).")
        return

    test_user = "test_user_01"
    test_create_user(test_user)
    
    # Grant permission to the first dataset
    first_kb_id = datasets[0]['id']
    test_grant_permission(test_user, first_kb_id)

if __name__ == "__main__":
    main()
