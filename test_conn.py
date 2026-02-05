
import os
import sys
import time
import requests

print("Testing connection to hf-mirror.com...")
try:
    # Unset proxies for this test
    os.environ.pop("http_proxy", None)
    os.environ.pop("https_proxy", None)
    os.environ.pop("HTTP_PROXY", None)
    os.environ.pop("HTTPS_PROXY", None)
    os.environ.pop("ALL_PROXY", None)
    os.environ.pop("all_proxy", None)

    start = time.time()
    response = requests.head("https://hf-mirror.com", timeout=10)
    print(f"Status Code: {response.status_code}")
    print(f"Time taken: {time.time() - start:.2f}s")
    print("Connection successful!")
except Exception as e:
    print(f"Connection failed: {e}")
