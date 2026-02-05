import requests
import json
import sys

BASE_URL = "http://127.0.0.1:8085/v1"

def launch_model(model_uid, model_name, model_type, **kwargs):
    print(f"Launching {model_name} ({model_type}) as {model_uid}...")
    url = f"{BASE_URL}/models"
    payload = {
        "model_uid": model_uid,
        "model_name": model_name,
        "model_type": model_type,
    }
    payload.update(kwargs)
    
    try:
        resp = requests.post(url, json=payload)
        if resp.status_code == 200:
            print(f"Success: {resp.json()}")
        else:
            print(f"Failed: {resp.status_code} - {resp.text}")
            if "already exists" in resp.text:
                print("Model already exists.")
    except Exception as e:
        print(f"Error launching {model_name}: {e}")

if __name__ == "__main__":
    # Launch Embedding
    launch_model(
        model_uid="bge-m3",
        model_name="bge-m3",
        model_type="embedding"
    )
    
    # Launch Rerank
    launch_model(
        model_uid="bge-reranker-v2-m3",
        model_name="bge-reranker-v2-m3",
        model_type="rerank"
    )
    
    # Launch LLM
    # Using qwen2.5-instruct definition for 14B size support, but loading deepseek weights
    launch_model(
        model_uid="deepseek-r1-distill-qwen-14b",
        model_name="qwen2.5-instruct",
        model_type="LLM",
        model_format="pytorch",
        model_engine="transformers",
        model_size_in_billions=14,
        model_path="/models/deepseek-r1-distill-qwen-14b",
        load_in_4bit=True
    )
