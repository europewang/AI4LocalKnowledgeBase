from xinference.client import Client
import sys

endpoint = "http://127.0.0.1:8085"
client = Client(endpoint)

model_uid = "deepseek-r1-distill-qwen-14b"
model_name = "deepseek-r1-distill-qwen-14b-custom"

# 1. Register (Version 1)
json_content = """
{
  "version": 1,
  "model_name": "deepseek-r1-distill-qwen-14b-custom",
  "model_description": "Custom registration",
  "model_lang": ["en", "zh"],
  "model_ability": ["chat", "tools"],
  "model_specs": [
    {
      "model_format": "pytorch",
      "model_size_in_billions": 14,
      "quantizations": ["4-bit", "8-bit", "none"],
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

print("Registering model (V1)...")
try:
    client.register_model(model_type="LLM", model=json_content, persist=True)
    print("Registration successful.")
except Exception as e:
    print(f"Registration failed: {e}")

# 2. Launch
print("Launching model...")
try:
    model_ref = client.launch_model(
        model_name=model_name,
        model_uid=model_uid,
        model_engine="transformers",
        model_format="pytorch",
        size_in_billions=14,
        quantization="4-bit"
    )
    print(f"Model launched successfully. UID: {model_uid}")
except Exception as e:
    print(f"Launch failed: {e}")
