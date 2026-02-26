#!/usr/bin/env python3
"""
Raspberry Pi TCP Server — Cube Scan Protocol

Protocol:
1️⃣ Handshake
   Client: RPI_HELLO
   Pi    : RPI_ACK

2️⃣ Start Scan
   Client: START_SCAN
   Pi    : READY → then scans cube → sends STATE:...
"""

import socket
import threading
import subprocess
import re

HOST = "0.0.0.0"
PORT = 9000

# Protocol messages (text form)
HANDSHAKE_TXT = "RPI_HELLO"
ACK_TXT       = "RPI_ACK\n"

CMD_SCAN_TXT  = "START_SCAN"
RESP_READY_TXT = "READY\n"

ALLOWED_CHARS = set("RGYBWO")

# =====================================================
# Client handler
# =====================================================

def handle_client(conn, addr):
    print(f"[+] Client connected: {addr}")

    # Use a text file wrapper for line-oriented protocol
    conn_file = conn.makefile(mode='r', encoding='utf-8', newline='\n')

    try:
        # --- Step 1: Handshake ---
        line = conn_file.readline()
        if not line:
            print(f"[!] No data from {addr} during handshake")
            conn.sendall(b"UNKNOWN_HANDSHAKE\n")
            return
        data = line.strip()
        if data != HANDSHAKE_TXT:
            conn.sendall(b"UNKNOWN_HANDSHAKE\n")
            print(f"[!] Invalid handshake from {addr}: {data}")
            return

        # Send ACK terminated with newline so clients using readLine() get a line.
        conn.sendall(ACK_TXT.encode())
        print(f"[+] Handshake complete with {addr}")

        # --- Step 2: Wait for START_SCAN (line-oriented) ---
        line = conn_file.readline()
        if not line:
            conn.sendall(b"UNKNOWN_COMMAND\n")
            print(f"[!] No command from {addr} after handshake")
            return
        cmd = line.strip()
        if cmd != CMD_SCAN_TXT:
            conn.sendall(b"UNKNOWN_COMMAND\n")
            print(f"[!] Expected START_SCAN from {addr}, got: {cmd}")
            return

        # Tell client we're ready
        conn.sendall(RESP_READY_TXT.encode())
        print(f"[+] Sending READY → starting cube scan for {addr}")

        # --- Step 3: Run cube_solver.py scan only ---
        result = subprocess.run(
            ["python3", "cube_solver.py", "--solve-only"],
            capture_output=True,
            text=True
        )

        # Combine stdout/stderr and search for a 54-character cube state consisting of the color letters
        combined = (result.stdout or "") + "\n" + (result.stderr or "")

        state_seq = None
        # Look for contiguous sequence of 54 letters that are only the allowed color letters
        m = re.search(r"([A-Za-z]{54})", combined)
        if m:
            candidate = m.group(1).upper()
            if all(c in ALLOWED_CHARS for c in candidate):
                state_seq = candidate

        # As a second attempt, look for any token made of allowed letters (may include separators)
        if not state_seq:
            tokens = re.findall(r"[A-Za-z]+", combined)
            for token in tokens:
                t = token.strip().upper()
                if len(t) == 54 and all(c in ALLOWED_CHARS for c in t):
                    state_seq = t
                    break

        if state_seq:
            state_line = f"STATE:{state_seq}\n"
        else:
            # fallback: return entire output for diagnostics
            state_line = f"STATE:FAILED\n{combined}\n"

        conn.sendall(state_line.encode())
        print(f"[+] Sent scan result to {addr}: {state_line}")

    except Exception as e:
        print("[-] Error:", e)
        try:
            conn.sendall(f"ERROR:{e}\n".encode())
        except Exception:
            pass

    finally:
        try:
            conn_file.close()
        except Exception:
            pass
        conn.close()
        print(f"[-] Client disconnected: {addr}")


# =====================================================
# Server loop
# =====================================================

def run_server(host, port):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((host, port))
    server.listen(5)
    print(f"✓ Raspberry Pi Server listening on {host}:{port}")

    try:
        while True:
            conn, addr = server.accept()
            threading.Thread(
                target=handle_client,
                args=(conn, addr),
                daemon=True
            ).start()

    except KeyboardInterrupt:
        print("\nShutting down server")

    finally:
        server.close()



# =====================================================
if __name__ == "__main__":
    run_server(HOST, PORT)
