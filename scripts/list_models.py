import requests
import json

BASE_URL = "http://127.0.0.1:8085/v1"

def list_running_models():
    """List currently running model instances"""
    url = f"{BASE_URL}/models"
    print("--- Running Models ---")
    try:
        resp = requests.get(url)
        if resp.status_code == 200:
            data = resp.json()
            # Handle list structure ({"object": "list", "data": [...]}) or direct dict
            models = data.get("data", []) if isinstance(data, dict) else data
            
            if not models:
                print("No models currently running.")
            else:
                for model_info in models:
                    # Depending on API version, could be dict or tuple
                    if isinstance(model_info, dict):
                        uid = model_info.get("id", "Unknown")
                        print(f"UID: {uid}")
                        # Other fields if available
                        # print(f"  Type: {model_info.get('model_type', 'N/A')}")
                    elif isinstance(model_info, tuple):
                        uid = model_info[0]
                        print(f"UID: {uid}")
                    else:
                        print(f"UID: {model_info}")
                    print("-" * 20)
        else:
            print(f"Failed to list models: {resp.status_code} - {resp.text}")
    except Exception as e:
        print(f"Error connecting to Xinference: {e}")
        print("Is the Docker container running? (sudo docker ps)")

if __name__ == "__main__":
    list_running_models()
