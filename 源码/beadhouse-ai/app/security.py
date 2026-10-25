import re


INJECTION_PATTERNS = [
    re.compile(r"忽略.{0,12}(制度|规则|指令|提示)"),
    re.compile(r"(输出|告诉|显示|泄露).{0,12}(数据库密码|密码|api密钥|api key|提示词|token)", re.IGNORECASE),
    re.compile(r"^(?:请(?:你)?|麻烦(?:你)?|现在|立即|你)?\s*(?:把|将).{0,12}"
               r"(数据库(?:密码|口令)|(?:系统|开发者)?提示词|api\s*(?:密钥|key)|token)"
               r".{0,12}(输出|告诉|显示|展示|泄露|发给|给我)", re.IGNORECASE),
    re.compile(r"ignore.{0,20}(previous|all|system|instruction)", re.IGNORECASE),
    re.compile(r"system\s*prompt|developer\s*message|reveal\s*(the\s*)?(secret|token|key)", re.IGNORECASE),
]


def prompt_injection_detected(text: str) -> bool:
    normalized = " ".join(text.strip().split())
    return any(pattern.search(normalized) for pattern in INJECTION_PATTERNS)
