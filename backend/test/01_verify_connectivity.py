# 验证后端与RAGFlow服务的连通性及基础API功能
import requests
import json
import sys

BASE_URL = "http://localhost:8083/api"

def log(msg):
    print(f"[Health Check] {msg}")

def verify_ragflow_connectivity():
    """
    验证后端是否能成功连接到 RAGFlow 并获取知识库列表。
    后端接口: GET /api/admin/datasets
    """
    url = f"{BASE_URL}/admin/datasets"
    try:
        log(f"Requesting {url} ...")
        resp = requests.get(url)
        if resp.status_code == 200:
            data = resp.json()
            if "data" in data and isinstance(data["data"], list):
                log("✅ RAGFlow connectivity verified. Datasets list retrieved.")
                datasets = data["data"]
                log(f"Found {len(datasets)} datasets.")
                for ds in datasets[:3]:
                    log(f" - ID: {ds.get('id')}, Name: {ds.get('name')}")
                return datasets
            else:
                log("❌ Response format unexpected: " + str(data))
                sys.exit(1)
        else:
            log(f"❌ Failed to connect. Status: {resp.status_code}, Body: {resp.text}")
            sys.exit(1)
    except Exception as e:
        log(f"❌ Exception occurred: {e}")
        sys.exit(1)

def verify_user_and_grant_permission(datasets):
    """
    验证用户是否存在，并为测试用户 'zhangsan' 授权第一个可用的知识库。
    后端接口: 
    1. POST /api/admin/user (创建/确认用户)
    2. POST /api/admin/permission/grant (授权)
    """
    username = "zhangsan"
    
    # 1. Ensure user exists
    user_url = f"{BASE_URL}/admin/user"
    user_data = {"username": username, "role": "user"}
    try:
        log(f"Ensuring user '{username}' exists...")
        resp = requests.post(user_url, json=user_data)
        if resp.status_code == 200:
            log(f"✅ User '{username}' confirmed/created.")
        else:
            log(f"❌ Failed to create user. Status: {resp.status_code}")
            sys.exit(1)
    except Exception as e:
        log(f"❌ Exception ensuring user: {e}")
        sys.exit(1)

    # 2. Grant permission if datasets exist
    if not datasets:
        log("⚠️ No datasets found in RAGFlow. Skipping permission grant. Chat test may fail.")
        return

    target_dataset = datasets[0]["id"]
    grant_url = f"{BASE_URL}/admin/permission/grant"
    grant_data = {
        "username": username,
        "resource_type": "DATASET",
        "resource_id": target_dataset
    }
    
    try:
        log(f"Granting permission for dataset '{target_dataset}' to '{username}'...")
        resp = requests.post(grant_url, json=grant_data)
        if resp.status_code == 200 and resp.text == "ok":
            log(f"✅ Permission granted successfully.")
        else:
            log(f"⚠️ Permission grant response: {resp.text} (Might be already granted or failed)")
    except Exception as e:
        log(f"❌ Exception granting permission: {e}")
        sys.exit(1)

if __name__ == "__main__":
    print("=== Starting Backend Health & Connectivity Test ===")
    datasets = verify_ragflow_connectivity()
    verify_user_and_grant_permission(datasets)
    print("=== Test Completed Successfully ===")
