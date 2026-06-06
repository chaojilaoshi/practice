"""PyInstaller entry point. PyInstaller bundles a concrete script (not a module),
so this thin wrapper just calls the package's main()."""

from cli_proxy_logger.__main__ import main

if __name__ == "__main__":
    main()
