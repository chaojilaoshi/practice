"""Outbound proxy (出站代理) -- opt-in, zero-dependency.

Routes the proxy's UPSTREAM connections through an external HTTP/HTTPS or SOCKS5
proxy (common on locked-down intranets that only allow egress via a corporate
proxy). Configure with a single URL, e.g.::

    UPSTREAM_PROXY=http://user:pass@proxy.corp:8080
    UPSTREAM_PROXY=socks5://10.0.0.1:1080

When unset, connections are made directly (default, unchanged behavior).

Implementation: we establish the tunnel ourselves -- an HTTP CONNECT for
http/https proxies, or a SOCKS5 handshake for socks proxies -- then hand the
tunneled socket to a small ``http.client`` connection subclass (wrapping it in
TLS first for https upstreams). No third-party packages.
"""

import base64
import http.client
import socket
import ssl
from urllib.parse import urlparse


def parse_proxy_url(raw):
    """Parse a proxy URL into a normalized dict, or None when nothing/invalid.
    Supported schemes: http, https, socks/socks5/socks5h (all SOCKS5)."""
    if not isinstance(raw, str) or raw.strip() == "":
        return None
    try:
        u = urlparse(raw.strip())
    except (ValueError, TypeError):
        return None
    scheme = (u.scheme or "").lower()
    if scheme == "http":
        kind = "http"
    elif scheme == "https":
        kind = "https"
    elif scheme in ("socks", "socks5", "socks5h"):
        kind = "socks5"
    else:
        return None
    if not u.hostname:
        return None
    default_port = 80 if kind == "http" else 443 if kind == "https" else 1080
    return {
        "kind": kind,
        "hostname": u.hostname,
        "port": u.port or default_port,
        "username": u.username or "",
        "password": u.password or "",
    }


def _http_connect_tunnel(proxy, host, port, timeout):
    """Open a TCP socket to the target via an HTTP CONNECT proxy."""
    sock = socket.create_connection((proxy["hostname"], proxy["port"]), timeout)
    try:
        if proxy["kind"] == "https":
            ctx = ssl._create_unverified_context()
            sock = ctx.wrap_socket(sock, server_hostname=proxy["hostname"])
        lines = [f"CONNECT {host}:{port} HTTP/1.1", f"Host: {host}:{port}"]
        if proxy["username"] or proxy["password"]:
            token = base64.b64encode(f"{proxy['username']}:{proxy['password']}".encode()).decode()
            lines.append(f"Proxy-Authorization: Basic {token}")
        sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = sock.recv(4096)
            if not chunk:
                raise OSError("proxy closed connection during CONNECT")
            buf += chunk
        status_line = buf.split(b"\r\n", 1)[0].decode("latin1")
        parts = status_line.split(" ", 2)
        if len(parts) < 2 or parts[1] != "200":
            raise OSError(f"proxy CONNECT failed: {status_line}")
        return sock
    except Exception:
        sock.close()
        raise


def _socks5_tunnel(proxy, host, port, timeout):
    """Open a TCP socket to the target via a SOCKS5 proxy (with optional auth)."""
    sock = socket.create_connection((proxy["hostname"], proxy["port"]), timeout)
    try:
        use_auth = bool(proxy["username"] or proxy["password"])
        sock.sendall(bytes([0x05, 0x02, 0x00, 0x02]) if use_auth else bytes([0x05, 0x01, 0x00]))
        greet = _recv_exact(sock, 2)
        if greet[0] != 0x05:
            raise OSError("socks5: bad version in greeting")
        method = greet[1]
        if method == 0xFF:
            raise OSError("socks5: no acceptable auth method")
        if method == 0x02:
            u = proxy["username"].encode()
            p = proxy["password"].encode()
            sock.sendall(bytes([0x01, len(u)]) + u + bytes([len(p)]) + p)
            auth_res = _recv_exact(sock, 2)
            if auth_res[1] != 0x00:
                raise OSError("socks5: authentication failed")
        elif method != 0x00:
            raise OSError(f"socks5: unsupported auth method {method}")
        host_b = host.encode()
        req = bytes([0x05, 0x01, 0x00, 0x03, len(host_b)]) + host_b + bytes([(port >> 8) & 0xFF, port & 0xFF])
        sock.sendall(req)
        head = _recv_exact(sock, 4)
        if head[1] != 0x00:
            raise OSError(f"socks5: connect failed (reply {head[1]})")
        atyp = head[3]
        if atyp == 0x01:
            _recv_exact(sock, 4)
        elif atyp == 0x04:
            _recv_exact(sock, 16)
        elif atyp == 0x03:
            ln = _recv_exact(sock, 1)[0]
            _recv_exact(sock, ln)
        else:
            raise OSError("socks5: bad ATYP in reply")
        _recv_exact(sock, 2)  # BND.PORT
        return sock
    except Exception:
        sock.close()
        raise


def _recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise OSError("socks5: connection closed early")
        buf += chunk
    return buf


def _raw_connect(proxy, host, port, timeout):
    if proxy["kind"] == "socks5":
        return _socks5_tunnel(proxy, host, port, timeout)
    return _http_connect_tunnel(proxy, host, port, timeout)


def create_outbound(raw):
    """Build an outbound helper bound to a proxy URL, or None when unset/invalid.

    The returned object exposes ``connection(scheme, host, port, timeout)`` which
    yields an ``http.client`` connection whose socket is tunneled through the
    proxy (and TLS-wrapped for https upstreams)."""
    proxy = parse_proxy_url(raw)
    if not proxy:
        return None

    class _TunnelHTTPConnection(http.client.HTTPConnection):
        def connect(self):
            self.sock = _raw_connect(proxy, self.host, self.port, self.timeout)

    class _TunnelHTTPSConnection(http.client.HTTPSConnection):
        def connect(self):
            raw_sock = _raw_connect(proxy, self.host, self.port, self.timeout)
            ctx = self._context or ssl.create_default_context()
            self.sock = ctx.wrap_socket(raw_sock, server_hostname=self.host)

    def connection(scheme, host, port, timeout=600):
        if scheme == "https":
            return _TunnelHTTPSConnection(host, port, timeout=timeout)
        return _TunnelHTTPConnection(host, port, timeout=timeout)

    auth = " (auth)" if proxy["username"] else ""
    return {
        "proxy": proxy,
        "describe": f"{proxy['kind']}://{proxy['hostname']}:{proxy['port']}{auth}",
        "connection": connection,
    }
