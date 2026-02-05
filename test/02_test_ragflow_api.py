import requests
import json
import sys

# Configuration
RAGFLOW_HOST = "http://localhost:8084"

def test_ragflow_health():
    print(f"[INFO] Connecting to RAGFlow at {RAGFLOW_HOST}...")
    try:
        # Try to access version endpoint
        resp = requests.get(f"{RAGFLOW_HOST}/v1/api/version")
        if resp.status_code == 200:
            print(f"[SUCCESS] RAGFlow is reachable. Version: {resp.json().get('data', 'unknown')}")
        else:
            # Fallback check: RAGFlow root page usually returns HTML
            resp_root = requests.get(f"{RAGFLOW_HOST}/")
            if resp_root.status_code == 200:
                print(f"[SUCCESS] RAGFlow UI is reachable (Status 200).")
            else:
                print(f"[FAIL] RAGFlow health check failed. Status: {resp.status_code}")
                sys.exit(1)
    except Exception as e:
        print(f"[FAIL] Connection error: {e}")
        sys.exit(1)

def main():
    test_ragflow_health()

if __name__ == "__main__":
    main()
