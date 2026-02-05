import argparse
import json
import os
import secrets
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Optional
 
 
try:
    import pymysql
except Exception as e:
    raise SystemExit(
        "缺少依赖 pymysql。请在 ai4tender 环境执行：pip install pymysql\n"
        f"原始错误: {e}"
    )
 
 
@dataclass(frozen=True)
class MysqlConfig:
    # RAGFlow 使用的 MySQL 连接信息（docker-compose-ragflow.yml 中的映射端口为 3307）
    host: str
    port: int
    user: str
    password: str
    database: str
    charset: str = "utf8mb4"
 
 
def _mysql_connect(cfg: MysqlConfig):
    # 使用 DictCursor 便于按字段名读取 tenant_id / token 等
    return pymysql.connect(
        host=cfg.host,
        port=cfg.port,
        user=cfg.user,
        password=cfg.password,
        database=cfg.database,
        charset=cfg.charset,
        cursorclass=pymysql.cursors.DictCursor,
        autocommit=True,
    )
 
 
def _get_first_tenant_id(cfg: MysqlConfig) -> str:
    # 取最早创建的 tenant 作为默认 tenant（单机/单租户场景通常足够）
    conn = _mysql_connect(cfg)
    try:
        with conn.cursor() as cur:
            cur.execute("select id from tenant order by create_date asc limit 1")
            row = cur.fetchone()
            if not row or not row.get("id"):
                raise SystemExit("数据库 tenant 表为空，无法获取 tenant_id")
            return row["id"]
    finally:
        conn.close()
 
 
def _get_or_create_token(cfg: MysqlConfig, tenant_id: str) -> str:
    # API Token 是 RAGFlow SDK 接口鉴权凭据；脚本优先复用已有 token，不存在则自动写库创建
    conn = _mysql_connect(cfg)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "select token from api_token where tenant_id=%s order by create_date desc limit 1",
                (tenant_id,),
            )
            row = cur.fetchone()
            token = (row or {}).get("token") or ""
            if token:
                return token
 
            token = "ragflow-" + secrets.token_urlsafe(32)
            now = datetime.now()
            ms = int(time.time() * 1000)
            cur.execute(
                """
                insert into api_token (tenant_id, token, source, create_time, create_date, update_time, update_date)
                values (%s, %s, %s, %s, %s, %s, %s)
                """,
                (tenant_id, token, "manual", ms, now, ms, now),
            )
            return token
    finally:
        conn.close()
 
 
def _http_json(
    method: str,
    url: str,
    token: str,
    body: Optional[dict] = None,
    form: Optional[dict] = None,
    files: Optional[dict] = None,
    timeout: int = 120,
) -> dict:
    # RAGFlow API 请求封装：支持 application/json 与 multipart/form-data（上传文件）
    headers = {"Authorization": f"Bearer {token}"}
    data: Optional[bytes] = None
 
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
 
    if form is not None or files is not None:
        boundary = "----ragflowE2E" + str(int(time.time() * 1000))
        headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        parts: list[bytes] = []
 
        def add_part(
            name: str,
            value_bytes: bytes,
            filename: Optional[str] = None,
            content_type: Optional[str] = None,
        ):
            parts.append(f"--{boundary}\r\n".encode())
            if filename is None:
                parts.append(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode())
            else:
                parts.append(
                    f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode()
                )
                if content_type:
                    parts.append(f"Content-Type: {content_type}\r\n".encode())
                parts.append(b"\r\n")
            parts.append(value_bytes)
            parts.append(b"\r\n")
 
        if form:
            for k, v in form.items():
                add_part(k, str(v).encode("utf-8"))
        if files:
            for field, path in files.items():
                with open(path, "rb") as f:
                    b = f.read()
                add_part(
                    field,
                    b,
                    filename=os.path.basename(path),
                    content_type="application/octet-stream",
                )
 
        parts.append(f"--{boundary}--\r\n".encode())
        data = b"".join(parts)
 
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
 
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8", errors="replace")
    return json.loads(raw)
 
 
def _assert_ok(resp: dict, action: str) -> dict:
    # RAGFlow 大多数接口返回 {"code":0,"data":...}；OpenAI-like 接口可能不带 code，需要兼容
    if "code" not in resp:
        return resp
    if resp.get("code") == 0:
        return resp
    message = resp.get("message") or str(resp)
    raise SystemExit(f"{action} 失败: {message}")
 
 
def _extract(d: dict, path: str) -> Any:
    # 用 "a.b.c" 形式从 dict 中提取字段，避免到处写深层下标访问
    cur: Any = d
    for part in path.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return None
    return cur


def _extract_docs_list(resp: dict) -> list[dict]:
    # 兼容 documents 列表两种返回结构：data=[...] 或 data={"docs":[...], "total":...}
    data = resp.get("data")
    if isinstance(data, list):
        return [x for x in data if isinstance(x, dict)]
    if isinstance(data, dict):
        docs = data.get("docs")
        if isinstance(docs, list):
            return [x for x in docs if isinstance(x, dict)]
    return []
 
 
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ragflow", default="http://127.0.0.1:8084")
    parser.add_argument("--mysql-host", default="127.0.0.1")
    parser.add_argument("--mysql-port", type=int, default=3307)
    parser.add_argument("--mysql-user", default="root")
    parser.add_argument("--mysql-password", default="infini_rag_flow")
    parser.add_argument("--mysql-db", default="rag_flow")
    parser.add_argument("--tenant-id", default="")
    parser.add_argument("--file", default="/home/ubutnu/code/AI4LocalKnowledgeBase/programDoc/01_Project_Plan.md")
    parser.add_argument("--chunk-method", default="naive")
    parser.add_argument("--parse-timeout-sec", type=int, default=180)
    parser.add_argument(
        "--question",
        default="请根据知识库内容，概括本项目的整体架构分层（用 4 层描述）。",
    )
    args = parser.parse_args()
 
    mysql_cfg = MysqlConfig(
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=args.mysql_password,
        database=args.mysql_db,
    )
 
    tenant_id = args.tenant_id.strip() or _get_first_tenant_id(mysql_cfg)
    token = _get_or_create_token(mysql_cfg, tenant_id)
 
    api_base = args.ragflow.rstrip("/") + "/api/v1"
    dataset_name = f"e2e-verify-{time.strftime('%Y%m%d-%H%M%S')}"
 
    print("step=token_ready ok=1")
    print(f"token_len={len(token)} tenant_id={tenant_id}")
 
    ds = _assert_ok(
        _http_json(
            "POST",
            f"{api_base}/datasets",
            token=token,
            body={"name": dataset_name, "chunk_method": args.chunk_method},
        ),
        "create_dataset",
    )
    dataset_id = _extract(ds, "data.id")
    if not dataset_id:
        raise SystemExit("create_dataset 返回缺少 data.id")
    print(f"step=create_dataset ok=1 dataset_id={dataset_id}")
 
    file_path = os.path.abspath(args.file)
    up = _assert_ok(
        _http_json(
            "POST",
            f"{api_base}/datasets/{dataset_id}/documents",
            token=token,
            form={},
            files={"file": file_path},
        ),
        "upload_document",
    )
    doc_id = None
    try:
        doc_id = up["data"][0]["id"]
    except Exception:
        doc_id = None
    if not doc_id:
        raise SystemExit("upload_document 返回缺少 doc_id")
    print(f"step=upload_document ok=1 doc_id={doc_id}")
 
    _assert_ok(
        _http_json(
            "POST",
            f"{api_base}/datasets/{dataset_id}/chunks",
            token=token,
            body={"document_ids": [doc_id]},
        ),
        "start_parsing",
    )
    print("step=start_parsing ok=1")
 
    deadline = time.time() + args.parse_timeout_sec
    while True:
        q = urllib.parse.urlencode({"id": doc_id})
        lst = _assert_ok(
            _http_json("GET", f"{api_base}/datasets/{dataset_id}/documents?{q}", token=token),
            "poll_parsing",
        )
        docs = _extract_docs_list(lst)
        if not docs:
            raise SystemExit("poll_parsing 返回空 documents 列表")
        d0 = next((d for d in docs if d.get("id") == doc_id), docs[0]) or {}
        progress = float(d0.get("progress") or 0)
        chunk_count = int(d0.get("chunk_count", d0.get("chunk_num") or 0) or 0)
        if progress >= 1.0 and chunk_count > 0:
            print(f"step=parse_done ok=1 progress={progress} chunk_count={chunk_count}")
            break
        if time.time() > deadline:
            raise SystemExit(
                f"解析超时: progress={progress} chunk_count={chunk_count} timeout={args.parse_timeout_sec}s"
            )
        time.sleep(2)
 
    chat = _assert_ok(
        _http_json(
            "POST",
            f"{api_base}/chats",
            token=token,
            body={"name": f"e2e-chat-{dataset_name}", "dataset_ids": [dataset_id]},
        ),
        "create_chat",
    )
    chat_id = _extract(chat, "data.id")
    if not chat_id:
        raise SystemExit("create_chat 返回缺少 data.id")
    print(f"step=create_chat ok=1 chat_id={chat_id}")
 
    comp = _assert_ok(
        _http_json(
            "POST",
            f"{api_base}/chats_openai/{chat_id}/chat/completions",
            token=token,
            body={
                "model": "deepseek-r1-distill-qwen-14b",
                "messages": [{"role": "user", "content": args.question}],
                "stream": False,
                "extra_body": {"reference": True},
            },
            timeout=300,
        ),
        "chat_completion_openai_like",
    )
 
    choices = comp.get("choices") or []
    msg = (choices[0].get("message") if choices else {}) or {}
    content = (msg.get("content") or "").strip()
    ref = msg.get("reference")
    ref_count = len(ref) if isinstance(ref, list) else (0 if ref is None else 1)
    preview = content.replace("\n", "\\n")[:160]
    print(f"step=ask ok=1 reference_count={ref_count} answer_preview={preview}")
 
 
if __name__ == "__main__":
    main()
