"""config.py — 配置加载与校验。

所有可调参数都来自 config.json，代码里不硬编码任何阈值。
"""
import json
import os
from typing import Any, Dict


# 默认配置：config.json 里缺失的键会回退到这里
DEFAULTS: Dict[str, Any] = {
    "adb_path": "",
    "device_serial": None,
    "base_url": "https://api.deepseek.com",
    "api_key": "",
    "api_key_env": ["DEEPSEEK_API_KEY", "OPENAI_API_KEY"],
    # 2026-09-10 起 deepseek-flash 就是最新的 V4.1-Flash（原生多模态）。
    # 旧的 deepseek-v4-flash-vision-exp 已退役，仅做兼容路由。
    "model": "deepseek-flash",
    # 官方视觉指南的评测参数就是 temperature=1.0，低温度反而会退化
    "temperature": 1.0,
    "reasoning_effort": "",
    "extra_body": {},
    "max_tokens": 1024,
    "request_timeout": 120,
    "max_steps": 30,
    "wait_after_action": 1.5,
    "stagnant_threshold": 3,
    "repeat_threshold": 3,
    "coordinate_mode": "pixel",
    # 服务端 detail 参数：original/high 保原图，low 降到 512x512。
    # 手机截图要读小字，必须用 original。
    "detail": "original",
    # 是否在发送前自己缩小。服务端本来就会把图归一到约 1300x1300，
    # 所以这不是「正确性」需要，纯粹是省上传流量和加速。
    "image_shrink": True,
    # 约等于服务端的 1300x1300 = 1.69M 像素目标，超过它再缩也没有额外信息量
    "image_pixel_budget": 1690000,
    "image_max_bytes": 4194304,
    "jpeg_quality": 85,
    "draw_grid_overlay": True,
    "grid_divisions": 10,
    "log_dir": "logs",
    "save_screenshots": True,
    "verbose": True,
}


class Config:
    """配置对象。用 cfg.model 这样访问，缺键时回退默认值。"""

    def __init__(self, data: Dict[str, Any], path: str = ""):
        self._data = dict(DEFAULTS)
        self._data.update(data or {})
        self.path = path

    def __getattr__(self, name: str) -> Any:
        if name.startswith("_"):
            raise AttributeError(name)
        try:
            return self._data[name]
        except KeyError:
            raise AttributeError("配置项不存在: %s" % name)

    def get(self, name: str, default: Any = None) -> Any:
        return self._data.get(name, default)

    @property
    def api_key(self) -> str:
        """优先用 config.json 里的 api_key；留空则回退读环境变量。

        这样 key 可以不落盘，避免误提交到 git。
        """
        key = (self._data.get("api_key") or "").strip()
        if key:
            return key
        for env_name in self._data.get("api_key_env") or []:
            val = os.environ.get(env_name, "").strip()
            if val:
                return val
        return ""

    @property
    def chat_url(self) -> str:
        """把 base_url 拼成 /chat/completions 的完整地址。

        官方文档里 base_url 写的是 https://api.deepseek.com，
        但 OpenAI SDK 会自动补 /v1，所以两种写法都要能工作：

          https://api.deepseek.com          -> https://api.deepseek.com/v1/chat/completions
          https://api.deepseek.com/v1       -> https://api.deepseek.com/v1/chat/completions
          https://x.com/v1/chat/completions -> 原样使用
        """
        base = (self._data.get("base_url") or "").rstrip("/")
        if base.endswith("/chat/completions"):
            return base
        if not base.endswith("/v1"):
            base = base + "/v1"
        return base + "/chat/completions"

    def masked_key(self) -> str:
        """日志里打印用的脱敏 key。"""
        key = self.api_key
        if not key:
            return "(未设置)"
        if len(key) <= 10:
            return key[:2] + "***"
        return key[:6] + "***" + key[-4:]

    def validate(self) -> None:
        """基础校验，失败时抛出带中文说明的异常。"""
        if self.coordinate_mode not in ("pixel", "normalized"):
            raise ValueError(
                "coordinate_mode 只能是 'pixel' 或 'normalized'，当前为 %r"
                % self.coordinate_mode
            )
        if self.max_steps < 1:
            raise ValueError("max_steps 必须 >= 1")
        if not (0.0 <= float(self.temperature) <= 2.0):
            raise ValueError("temperature 必须在 0 到 2 之间")
        if not self.model:
            raise ValueError("model 不能为空")


def load(path: str = "config.json") -> Config:
    """读取 config.json；文件不存在时用全默认值并在调用方提示。"""
    data: Dict[str, Any] = {}
    if os.path.exists(path):
        with open(path, "r", encoding="utf-8") as f:
            text = f.read().strip()
        if text:
            data = json.loads(text)
    cfg = Config(data, path=path)
    cfg.validate()
    return cfg
