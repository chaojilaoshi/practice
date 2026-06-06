"""Build a single-file executable with PyInstaller.

    python scripts/build_exe.py

Produces ``dist/cli-proxy-logger(.exe)`` — one self-contained file that bundles
the Python runtime, the cli_proxy_logger package, and the web UI (public/
index.html). Double-clicking it starts the proxy + config UI and opens the
browser to the visual settings page. config.json and logs/ are created next to
the executable on first run.

Cross-platform: on Windows it emits ``cli-proxy-logger.exe``; on macOS/Linux it
emits a native ``cli-proxy-logger`` binary. Build on the OS you want to target
(PyInstaller does not cross-compile)."""

import os
import sys
import PyInstaller.__main__

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENTRY = os.path.join(ROOT, "scripts", "entry.py")
INDEX = os.path.join(ROOT, "public", "index.html")

# PyInstaller's --add-data uses the host path separator (';' on Windows, ':' else).
SEP = ";" if sys.platform == "win32" else ":"

PyInstaller.__main__.run([
    ENTRY,
    "--name", "cli-proxy-logger",
    "--onefile",
    "--console",
    "--noconfirm",
    "--clean",
    "--distpath", os.path.join(ROOT, "dist"),
    "--workpath", os.path.join(ROOT, "build"),
    "--specpath", os.path.join(ROOT, "build"),
    "--add-data", f"{INDEX}{SEP}public",
    "--paths", ROOT,
])
