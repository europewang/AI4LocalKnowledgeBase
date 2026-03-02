import requests
import json
import sys
import time

BASE_URL = "http://127.0.0.1:8085/v1"

def register_custom_model():
    model_name = "deepseek-r1-distill-qwen-14b-custom"
    # Correct endpoint for model registration in Xinference v1.x/2.x
    url = f"{BASE_URL}/model_registrations/LLM"
    
    # JSON content as a string, matching the successful manual registration
    model_json = json.dumps({
      "version": 2,
      "model_name": "deepseek-r1-distill-qwen-14b-custom",
      "model_description": "Custom registration for DeepSeek-R1-Distill-Qwen-14B",
      "model_family": "qwen2.5-instruct",
      "model_type": "LLM",
      "model_lang": ["en", "zh"],
      "model_ability": ["chat", "tools"],
      "model_specs": [
        {
          "model_format": "pytorch",
          "model_size_in_billions": 14,
          "quantization": "4-bit",
          "model_id": "deepseek-ai/DeepSeek-R1-Distill-Qwen-14B",
          "model_uri": "file:///models/deepseek-r1-distill-qwen-14b"
        },
        {
          "model_format": "pytorch",
          "model_size_in_billions": 14,
          "quantization": "none",
          "model_id": "deepseek-ai/DeepSeek-R1-Distill-Qwen-14B",
          "model_uri": "file:///models/deepseek-r1-distill-qwen-14b"
        }
      ],
      "prompt_style": {
        "style_name": "qwen",
        "system_prompt": "",
        "roles": ["user", "assistant"],
        "intra_message_sep": "\\n\\n"
      }
    })

    payload = {
        "model": model_json,
        "persist": True
    }

    print(f"Registering custom model {model_name}...", flush=True)
    try:
        resp = requests.post(url, json=payload)
        if resp.status_code == 200:
            print("Registration successful.", flush=True)
        else:
            print(f"Registration failed: {resp.status_code} - {resp.text}", flush=True)
            # Continue anyway as it might be already registered
    except Exception as e:
        print(f"Error registering model: {e}", flush=True)

def launch_model(model_uid, model_name, model_type, **kwargs):
    print(f"Launching {model_name} ({model_type}) as {model_uid}...", flush=True)
    
    # Check if already running
    try:
        list_resp = requests.get(f"{BASE_URL}/models")
        if list_resp.status_code == 200:
            running_models = list_resp.json()
            if model_uid in running_models:
                print(f"Model {model_uid} is already running. Skipping launch.", flush=True)
                return
    except Exception as e:
        print(f"Error checking running models: {e}", flush=True)

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
            print(f"Success: {model_uid}", flush=True)
        else:
            print(f"Failed to launch {model_uid}: {resp.status_code} - {resp.text}", flush=True)
    except Exception as e:
        print(f"Error launching {model_name}: {e}", flush=True)

if __name__ == "__main__":
    print("Starting Xinference models launch sequence...", flush=True)
    
    # 1. Launch Embedding
    launch_model(
        model_uid="bge-m3",
        model_name="bge-m3",
        model_type="embedding"
    )
    
    # 2. Launch Rerank
    launch_model(
        model_uid="bge-reranker-v2-m3",
        model_name="bge-reranker-v2-m3",
        model_type="rerank"
    )
    
    # 3. Register and Launch LLM
    register_custom_model()
    
    launch_model(
        model_uid="deepseek-r1-distill-qwen-14b",
        model_name="deepseek-r1-distill-qwen-14b-custom",
        model_type="LLM",
        model_engine="transformers",
        model_format="pytorch",
        size_in_billions=14,
        quantization="4-bit",
        # Explicit config for bitsandbytes to prevent OOM
        quantization_config={"load_in_4bit": True, "bnb_4bit_compute_dtype": "float16"}
    )
    
    print("Launch sequence completed.", flush=True)
