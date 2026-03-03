
import requests
import json

API_BASE = "http://localhost:8084/api"
API_KEY = "ragflow-rKofJZKLNgh_2Pv9A-0y_3sUbC9MIOkw9n99Cl5hvc4"

def test_create():
    url = f"{API_BASE}/v1/datasets"
    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json"
    }
    
    # Try with 'language'
    data = {
        "name": "test_chinese_ds",
        "language": "Chinese"
    }
    
    print("Creating dataset with language='Chinese'...")
    try:
        res = requests.post(url, headers=headers, json=data)
        print(f"Status: {res.status_code}")
        print(res.text)
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    test_create()
