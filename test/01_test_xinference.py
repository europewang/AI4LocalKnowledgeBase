import requests
import json
import sys

# Configuration
XINFERENCE_HOST = "http://localhost:8085"

def test_list_models():
    print(f"[INFO] Connecting to Xinference at {XINFERENCE_HOST}...")
    try:
        resp = requests.get(f"{XINFERENCE_HOST}/v1/models")
        if resp.status_code == 200:
            models = resp.json().get("data", [])
            print(f"[SUCCESS] Xinference is reachable. Active models: {len(models)}")
            for m in models:
                print(f"  - {m['id']} (Type: {m.get('model_type', 'unknown')})")
            return models
        else:
            print(f"[FAIL] Failed to list models. Status: {resp.status_code}")
            sys.exit(1)
    except Exception as e:
        print(f"[FAIL] Connection error: {e}")
        sys.exit(1)

def test_embedding(model_id):
    print(f"\n[INFO] Testing Embedding model: {model_id}...")
    try:
        resp = requests.post(
            f"{XINFERENCE_HOST}/v1/embeddings",
            json={
                "model": model_id,
                "input": "This is a test sentence."
            }
        )
        if resp.status_code == 200:
            data = resp.json()
            emb = data["data"][0]["embedding"]
            print(f"[SUCCESS] Embedding generated. Dimension: {len(emb)}")
        else:
            print(f"[FAIL] Embedding failed. Status: {resp.status_code}, Body: {resp.text}")
    except Exception as e:
        print(f"[FAIL] Embedding error: {e}")

def test_rerank(model_id):
    print(f"\n[INFO] Testing Rerank model: {model_id}...")
    try:
        resp = requests.post(
            f"{XINFERENCE_HOST}/v1/rerank",
            json={
                "model": model_id,
                "query": "What is Deep Learning?",
                "documents": ["Deep learning is a subset of machine learning.", "Apples are fruits."]
            }
        )
        if resp.status_code == 200:
            data = resp.json()
            results = data.get("results", [])
            print(f"[SUCCESS] Rerank successful. Top score: {results[0]['relevance_score']}")
        else:
            print(f"[FAIL] Rerank failed. Status: {resp.status_code}, Body: {resp.text}")
    except Exception as e:
        print(f"[FAIL] Rerank error: {e}")

def main():
    models = test_list_models()
    
    # Identify models by ID (assuming standard names from project plan)
    embedding_model = next((m['id'] for m in models if 'bge-m3' in m['id']), None)
    rerank_model = next((m['id'] for m in models if 'reranker' in m['id']), None)
    
    if embedding_model:
        test_embedding(embedding_model)
    else:
        print("[WARN] No embedding model (bge-m3) found.")

    if rerank_model:
        test_rerank(rerank_model)
    else:
        print("[WARN] No rerank model (bge-reranker) found.")

if __name__ == "__main__":
    main()
