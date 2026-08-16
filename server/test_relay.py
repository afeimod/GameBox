#!/usr/bin/env python3
"""快速测试 TCP 中继握手协议（本地验证用）"""
import json, socket, threading, time

TCP_HOST, TCP_PORT = "127.0.0.1", 9090

def client(name, token, room_id, results):
    try:
        s = socket.create_connection((TCP_HOST, TCP_PORT), timeout=5)
        hello = json.dumps({"type": "hello", "token": token, "roomId": room_id}) + "\n"
        s.sendall(hello.encode())
        # 读 start 消息（双方到齐后）
        buf = b""
        deadline = time.time() + 8
        got = []
        while time.time() < deadline:
            s.settimeout(1)
            try:
                d = s.recv(4096)
            except socket.timeout:
                continue
            if not d:
                break
            buf += d
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                if line.strip():
                    msg = json.loads(line)
                    got.append(msg)
                    print(f"[{name}] recv: {msg}")
                    if msg.get("type") == "hello":
                        results[name] = msg.get("msg")
                    if msg.get("type") == "start":
                        results[name + "_start"] = msg.get("msg")
                        return
        if name not in results:
            results[name] = "TIMEOUT"
    except Exception as e:
        results[name] = f"ERROR: {e}"

def main():
    # 1. 注册两个用户拿 token
    import urllib.request
    def reg(u):
        req = urllib.request.Request(
            "http://127.0.0.1:8080/api/auth/register",
            data=json.dumps({"username": u, "password": "123456"}).encode(),
            headers={"Content-Type": "application/json"})
        return json.load(urllib.request.urlopen(req))["token"]
    tok_a = reg("p1_" + str(int(time.time())))
    tok_b = reg("p2_" + str(int(time.time())))
    # 2. 创建房间
    req = urllib.request.Request(
        "http://127.0.0.1:8080/api/rooms",
        data=json.dumps({"gameId": "kof97"}).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + tok_a})
    room = json.load(urllib.request.urlopen(req))["room"]
    room_id = room["id"]
    print(f"room={room_id}")
    # 3. 两个客户端同时连接 TCP
    results = {}
    t1 = threading.Thread(target=client, args=("A", tok_a, room_id, results))
    t2 = threading.Thread(target=client, args=("B", tok_b, room_id, results))
    t1.start(); t2.start(); t1.join(); t2.join()
    print("RESULT:", results)
    # 断言：A 收到 hello=host，B 收到 hello=guest，双方收到 start
    assert results.get("A") == "host", results
    assert results.get("B") == "guest", results
    assert "A_start" in results and "B_start" in results, results
    print("TCP RELAY TEST PASSED")

if __name__ == "__main__":
    main()
