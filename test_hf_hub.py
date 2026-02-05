
import os

os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
# Ensure proxies are unset
os.environ.pop("http_proxy", None)
os.environ.pop("https_proxy", None)
os.environ.pop("HTTP_PROXY", None)
os.environ.pop("HTTPS_PROXY", None)
os.environ.pop("ALL_PROXY", None)
os.environ.pop("all_proxy", None)

import huggingface_hub
from huggingface_hub import list_repo_files

print(f"huggingface_hub version: {huggingface_hub.__version__}")

print("Listing files from deepseek-ai/DeepSeek-R1-Distill-Qwen-14B...")
try:
    files = list_repo_files(repo_id="deepseek-ai/DeepSeek-R1-Distill-Qwen-14B")
    print(f"Found {len(files)} files.")
    print(files[:5])
except Exception as e:
    print(f"Error: {e}")
