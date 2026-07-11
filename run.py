"""Convenience launcher: `python run.py` (defaults to 0.0.0.0:8765).

Override with env vars GM_HOST / GM_PORT. For TLS (wss://) supply
GM_SSL_CERT and GM_SSL_KEY, or run behind a TLS-terminating reverse proxy.
"""
import os

import uvicorn

from app import config

if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=config.HOST,
        port=config.PORT,
        ssl_certfile=os.environ.get("GM_SSL_CERT"),
        ssl_keyfile=os.environ.get("GM_SSL_KEY"),
    )
