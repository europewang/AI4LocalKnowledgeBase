from xinference.client import Client
import sys

endpoint = "http://127.0.0.1:8085"
client = Client(endpoint)

model_uid = "deepseek-r1-distill-qwen-14b"
model_name = "deepseek-r1-distill-qwen-14b-custom"

# 1. Register
json_content = """
{
  "version": 2,
  "model_name": "deepseek-r1-distill-qwen-14b-custom",
  "model_description": "Custom registration",
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
}
"""

print("Registering model...", flush=True)
try:
    client.register_model(model_type="LLM", model=json_content, persist=True)
    print("Registration successful.", flush=True)
except Exception as e:
    print(f"Registration failed: {e}", flush=True)
    # Don't exit, try launching anyway if it was already registered

# 2. Launch
print(f"Checking if model {model_uid} is already running...", flush=True)
try:
    client.terminate_model(model_uid)
    print(f"Terminated existing model {model_uid}", flush=True)
except Exception:
    pass

print(f"Launching model {model_name} as {model_uid}...", flush=True)
try:
    model_ref = client.launch_model(
        model_name=model_name,
        model_uid=model_uid,
        model_engine="transformers",
        model_format="pytorch",
        size_in_billions=14,
        quantization="4-bit",
        # explicit config for bitsandbytes
        quantization_config={"load_in_4bit": True, "bnb_4bit_compute_dtype": "float16"}
    )
    print(f"Model launched successfully. UID: {model_uid}", flush=True)
except Exception as e:
    print(f"Launch failed: {e}", flush=True)
