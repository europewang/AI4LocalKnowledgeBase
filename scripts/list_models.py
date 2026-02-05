import requests
import json

def list_registrations():
    url = "http://127.0.0.1:8085/v1/model_registrations/LLM/qwen2.5-instruct"
    try:
        resp = requests.get(url)
        if resp.status_code == 200:
            print(json.dumps(resp.json(), indent=2))
        else:
            print(f"Failed: {resp.status_code}")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    list_registrations()
