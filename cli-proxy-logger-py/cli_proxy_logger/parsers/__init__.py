"""Wire-format parsers, keyed by wire name (matches ``resolve_upstream``)."""

from . import anthropic, openai_responses, openai_chat

PARSERS = {
    "anthropic": anthropic,
    "responses": openai_responses,
    "chat": openai_chat,
}
