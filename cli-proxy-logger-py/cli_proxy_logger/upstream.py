"""Decide which upstream a request should be forwarded to and which wire format
it uses, based on the request path. This is what lets a single proxy port serve
both Claude Code (Anthropic Messages) and Codex (OpenAI Responses/Chat).

    /v1/messages            -> anthropic  (Claude Code)
    /v1/responses           -> openai responses (Codex default, wire_api="responses")
    /v1/chat/completions    -> openai chat (Codex chat mode / OpenAI-compatible)
    anything else           -> guessed from headers, defaults to anthropic
"""


def resolve_upstream(config, req_path, headers):
    """headers: a case-insensitive mapping (lower-cased keys expected)."""
    p = (req_path or "").split("?")[0]

    if p.startswith("/v1/messages"):
        return {"baseUrl": config["upstream"]["anthropic"], "wire": "anthropic"}
    if p.startswith("/v1/responses"):
        return {"baseUrl": config["upstream"]["openai"], "wire": "responses"}
    if p.startswith("/v1/chat/completions"):
        return {"baseUrl": config["upstream"]["openai"], "wire": "chat"}
    if p.startswith("/v1/models") or p.startswith("/v1/complete"):
        # Model discovery / legacy completion: route by auth header style.
        if headers.get("x-api-key") or headers.get("anthropic-version"):
            return {"baseUrl": config["upstream"]["anthropic"], "wire": "anthropic"}
        return {"baseUrl": config["upstream"]["openai"], "wire": "chat"}

    # Fallback: Anthropic uses x-api-key + anthropic-version, OpenAI uses Bearer.
    if headers.get("x-api-key") or headers.get("anthropic-version"):
        return {"baseUrl": config["upstream"]["anthropic"], "wire": "anthropic"}
    return {"baseUrl": config["upstream"]["openai"], "wire": "chat"}
