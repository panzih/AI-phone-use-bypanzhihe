"""llm.py — OpenAI 兼容的多模态接口客户端（仅用标准库 urllib）。

为什么要自己写而不依赖 openai / httpx 包：
本项目定位是「用户下载就能跑」，而 openai SDK 版本迭代快、接口时有变动。
/chat/completions 的请求体很稳定，用标准库反而更可靠、零安装成本。

兼容性策略：
  - base_url 支持三种写法（裸域名 / 带 /v1 / 完整端点）
  - 自动探测服务端是否支持 response_format，不支持就自动关掉重试
  - extra_body 允许透传厂商私有参数（如 enable_thinking），换模型不用改代码
"""
import base64
import json
import socket
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

from config import Config
from image import PreparedImage


class LlmError(RuntimeError):
    """模型接口错误，消息为面向用户的中文提示。"""


class LlmClient:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.url = cfg.chat_url
        self._supports_json_mode: Optional[bool] = None  # 首次调用后确定

    # ------------------------------------------------------------------
    # 请求构造
    # ------------------------------------------------------------------
    def _build_payload(
        self,
        messages: List[Dict[str, Any]],
        use_json_mode: bool,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": self.cfg.model,
            "messages": messages,
            "temperature": float(self.cfg.temperature),
            "max_tokens": int(self.cfg.max_tokens),
        }

        # 思考强度：不同厂商参数名不同，配了才带
        effort = (self.cfg.get("reasoning_effort") or "").strip()
        if effort:
            payload["reasoning_effort"] = effort

        if use_json_mode:
            payload["response_format"] = {"type": "json_object"}

        # 透传厂商私有参数（如 {"enable_thinking": true}）
        extra = self.cfg.get("extra_body") or {}
        if isinstance(extra, dict):
            for k, v in extra.items():
                payload[k] = v

        return payload

    def _post(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """发一次 POST，返回解析后的 JSON。"""
        api_key = self.cfg.api_key
        if not api_key:
            raise LlmError(
                "没有配置 API Key。两种方式任选：\n"
                "  1. 在 config.json 里填 api_key 字段\n"
                "  2. 设置环境变量后重开终端：\n"
                "     macOS/Linux : export DEEPSEEK_API_KEY=\"sk-xxxx\"\n"
                "     Windows CMD : set DEEPSEEK_API_KEY=sk-xxxx\n"
                "     Windows 永久: setx DEEPSEEK_API_KEY \"sk-xxxx\"\n"
                "Key 从 https://platform.deepseek.com 获取。"
            )

        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            self.url,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer %s" % api_key,
                # 部分中转服务会校验 UA，带上更稳
                "User-Agent": "ai-phone-agent/0.1",
                "Accept": "application/json",
            },
        )

        timeout = float(self.cfg.request_timeout)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            raise LlmError(self._explain_http_error(e.code, detail))
        except urllib.error.URLError as e:
            raise LlmError(
                "无法连接模型服务：%s\n"
                "请检查：\n"
                "  1. 网络是否正常（能否打开网页）\n"
                "  2. config.json 里的 base_url 是否正确：%s\n"
                "  3. 如果用了代理/VPN，确认终端也走了代理\n"
                "  4. 公司网络可能拦截了该域名" % (e.reason, self.cfg.base_url)
            )
        except socket.timeout:
            raise LlmError(
                "模型请求超时（%.0f 秒）。可以调大 config.json 里的 request_timeout，"
                "或改用更快的模型。" % timeout
            )

        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            raise LlmError(
                "模型返回的不是合法 JSON，前 300 字符：\n%s\n"
                "通常说明 base_url 指向的不是标准的 /chat/completions 接口。" % raw[:300]
            )

    def _explain_http_error(self, code: int, detail: str) -> str:
        """把 HTTP 错误翻译成用户能看懂的中文提示。"""
        low = detail.lower()
        head = "模型接口返回 HTTP %d。\n" % code

        if code == 401:
            return head + (
                "鉴权失败：API Key 无效或已过期。\n"
                "请检查 config.json 的 api_key，或环境变量 DEEPSEEK_API_KEY。\n"
                "Key 从 https://platform.deepseek.com 获取。\n"
                "服务端返回：%s" % detail[:300]
            )
        if code == 402 or "insufficient" in low or "balance" in low:
            return head + (
                "账户余额不足。请到模型服务商控制台充值。\n"
                "服务端返回：%s" % detail[:300]
            )
        if code == 404:
            return head + (
                "接口地址不存在。请检查 config.json 的 base_url：%s\n"
                "当前实际请求地址：%s\n"
                "常见正确写法：https://api.deepseek.com 或 https://api.openai.com/v1\n"
                "服务端返回：%s" % (self.cfg.base_url, self.url, detail[:300])
            )
        if code == 429:
            return head + (
                "请求过于频繁或达到配额上限。等几秒重试，或降低操作频率。\n"
                "服务端返回：%s" % detail[:300]
            )
        if code in (400, 422):
            if "system" in low or "assistant" in low:
                return head + (
                    "图片只能放在 user 消息里。官方文档明确：图片出现在 system 或\n"
                    "assistant 消息中会返回 400。\n"
                    "服务端返回：%s" % detail[:400]
                )
            if "image" in low or "vision" in low or "modal" in low:
                return head + (
                    "该模型不接受图片输入。\n"
                    "当前模型：%s\n"
                    "请换用支持图片的模型：\n"
                    "  deepseek-flash   （V4.1-Flash，原生多模态，官方当前推荐）\n"
                    "注意 deepseek-v4-flash 和 deepseek-v4-pro 是纯文本模型，不能看图。\n"
                    "服务端返回：%s" % (self.cfg.model, detail[:400])
                )
            if "response_format" in low or "json" in low:
                return head + (
                    "服务端不支持 JSON 输出模式（response_format）。\n"
                    "本程序会自动关闭该参数重试；若反复出现请反馈。\n"
                    "服务端返回：%s" % detail[:300]
                )
            return head + (
                "请求参数有问题。\n"
                "服务端返回：%s\n"
                "提示：如果 extra_body 里配了厂商私有参数，请确认拼写正确。" % detail[:400]
            )
        if code >= 500:
            return head + (
                "模型服务端错误，通常是临时故障。等一会儿重试。\n"
                "服务端返回：%s" % detail[:300]
            )
        return head + "服务端返回：%s" % detail[:400]

    # ------------------------------------------------------------------
    # 对话
    # ------------------------------------------------------------------
    def chat_with_image(
        self,
        system_prompt: str,
        user_text: str,
        image: Optional[PreparedImage] = None,
    ) -> str:
        """发一轮对话（可带图），返回模型输出的纯文本。"""
        if image is not None:
            b64 = base64.b64encode(image.data).decode("ascii")
            image_url: Dict[str, Any] = {
                "url": "data:%s;base64,%s" % (image.media_type, b64)
            }
            # detail 控制服务端的图像处理方式：
            #   low      -> 降到 512x512，快且便宜
            #   original -> 保留原图（手机截图要读小字，必须用这个）
            #   high / auto -> 官方文档说明等价于 original
            detail = (self.cfg.get("detail") or "").strip()
            if detail:
                image_url["detail"] = detail

            content: Any = [
                {"type": "text", "text": user_text},
                {"type": "image_url", "image_url": image_url},
            ]
        else:
            content = user_text

        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": content},
        ]

        use_json = self._supports_json_mode is not False
        payload = self._build_payload(messages, use_json)

        try:
            resp = self._post(payload)
        except LlmError as e:
            # 首次遇到 response_format 不被支持 → 关掉它自动重试一次
            if use_json and "response_format" in str(e):
                self._supports_json_mode = False
                payload = self._build_payload(messages, False)
                resp = self._post(payload)
            else:
                raise

        if self._supports_json_mode is None:
            self._supports_json_mode = use_json

        return self._extract_text(resp)

    def _extract_text(self, resp: Dict[str, Any]) -> str:
        """从响应里取出正文，并处理常见的异常结构。"""
        if "error" in resp and resp["error"]:
            err = resp["error"]
            msg = err.get("message") if isinstance(err, dict) else str(err)
            raise LlmError("模型接口返回错误：%s" % msg)

        choices = resp.get("choices")
        if not choices:
            raise LlmError(
                "模型响应里没有 choices 字段，完整响应：\n%s"
                % json.dumps(resp, ensure_ascii=False)[:600]
            )

        msg = choices[0].get("message") or {}
        content = msg.get("content")

        # 有些推理模型把正文放在 content，思维链放在 reasoning_content
        if not content:
            reasoning = msg.get("reasoning_content")
            if reasoning:
                # 正文为空但有思维链，说明被 max_tokens 截断了
                raise LlmError(
                    "模型只输出了思维链，没有输出正文。通常是 max_tokens 太小被截断。\n"
                    "请把 config.json 里的 max_tokens 调大（建议 1024 以上）。"
                )
            raise LlmError(
                "模型返回了空内容。finish_reason=%s" % choices[0].get("finish_reason")
            )

        if isinstance(content, list):
            # 部分服务会把 content 也做成数组结构
            parts = []
            for p in content:
                if isinstance(p, dict) and p.get("type") == "text":
                    parts.append(p.get("text") or "")
                elif isinstance(p, str):
                    parts.append(p)
            content = "".join(parts)

        return str(content).strip()

    # ------------------------------------------------------------------
    # 自检
    # ------------------------------------------------------------------
    def self_check(self, verbose: bool = True) -> Dict[str, Any]:
        """连通性自检：分两步验证「能连上」和「能收图」。

        不做这一步，用户很容易卡在莫名其妙的报错上：
        最常见的是选了个纯文本模型，然后每一步都失败。
        """
        result: Dict[str, Any] = {"text_ok": False, "image_ok": False, "detail": ""}
        if verbose:
            print("  [1/2] 测试文本接口 ...", end="", flush=True)

        try:
            messages = [
                {"role": "system", "content": "你是一个测试助手，只回复 OK。"},
                {"role": "user", "content": "回复 OK"},
            ]
            payload = {
                "model": self.cfg.model,
                "messages": messages,
                "max_tokens": 16,
                "temperature": 0,
            }
            resp = self._post(payload)
            txt = self._extract_text(resp)
            result["text_ok"] = True
            if verbose:
                print(" 通过（模型回复：%s）" % txt[:40].replace("\n", " "))
        except LlmError as e:
            result["detail"] = str(e)
            if verbose:
                print(" 失败")
                print("\n" + str(e))
            return result

        if verbose:
            print("  [2/2] 测试图片输入 ...", end="", flush=True)
        try:
            img = _make_test_image()
            detail = (self.cfg.get("detail") or "").strip()
            test_url: Dict[str, Any] = {
                "url": "data:image/png;base64,%s"
                % base64.b64encode(img).decode("ascii")
            }
            if detail:
                test_url["detail"] = detail
            messages = [
                {"role": "system", "content": "你是一个测试助手。"},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": "这张图是什么颜色？只回答颜色名。"},
                        {"type": "image_url", "image_url": test_url},
                    ],
                },
            ]
            payload = {
                "model": self.cfg.model,
                "messages": messages,
                "max_tokens": 32,
                "temperature": 0,
            }
            resp = self._post(payload)
            txt = self._extract_text(resp)
            result["image_ok"] = True
            if verbose:
                print(" 通过（模型回复：%s）" % txt[:40].replace("\n", " "))
        except LlmError as e:
            result["detail"] = str(e)
            if verbose:
                print(" 失败")
                print("\n" + str(e))

        return result


def _make_test_image() -> bytes:
    """造一张 64x64 的纯红色 PNG，用于验证服务端确实接受图片。

    只用手写 PNG 结构，不依赖 Pillow。
    """
    import struct
    import zlib

    w = h = 64
    raw = b""
    for _y in range(h):
        raw += b"\x00" + bytes([220, 40, 40] * w)

    def chunk(tag: bytes, data: bytes) -> bytes:
        c = tag + data
        return (
            struct.pack(">I", len(data))
            + c
            + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
