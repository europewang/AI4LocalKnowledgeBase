
import os
import httpx

# Clear proxies
os.environ.pop("http_proxy", None)
os.environ.pop("https_proxy", None)
os.environ.pop("HTTP_PROXY", None)
os.environ.pop("HTTPS_PROXY", None)
os.environ.pop("ALL_PROXY", None)
os.environ.pop("all_proxy", None)

print("Testing httpx connection to https://hf-mirror.com")
try:
    with httpx.Client() as client:
        r = client.head("https://hf-mirror.com")
        print(f"Status: {r.status_code}")
except Exception as e:
    print(f"Error: {e}")
