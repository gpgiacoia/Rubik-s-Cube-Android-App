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
        # Run cube_solver.py. Previously this server invoked with --solve-only which
        # may cause the solver to use a previously saved cube state instead of
        # performing a fresh camera capture. To ensure a fresh scan is performed
        # each time, call cube_solver.py without --solve-only by default.
        # If you need the old behavior for debugging, set environment variable
        # USE_SOLVE_ONLY=1 before launching this server.
        use_solve_only = False
        try:
            import os
            use_solve_only = os.environ.get('USE_SOLVE_ONLY', '0') == '1'
        except Exception:
            use_solve_only = False

        cmd = ["python3", "cube_solver.py"]
        if use_solve_only:
            cmd.append("--solve-only")

        # Optionally remove known cache files before running the solver to force a fresh capture.
        # You can configure additional filenames via the CLEAR_CACHE_FILES environment variable
        # (comma-separated). The default list is conservative.
        try:
            import os
            clear_list_env = os.environ.get('CLEAR_CACHE_FILES', '')
            default_clear = ['last_state.txt', 'saved_state.txt', 'state.txt', 'cached_state.txt', 'scan_cache.txt', 'last_scan.jpg', 'capture.jpg']
            clear_files = [p for p in (default_clear + [s.strip() for s in clear_list_env.split(',') if s.strip()])]
            for fname in clear_files:
                try:
                    if os.path.exists(fname):
                        os.remove(fname)
                        print(f"[+] Removed cache file to force fresh scan: {fname}")
                except Exception as e:
                    print(f"[!] Failed to remove cache file {fname}: {e}")
        except Exception:
            pass

        # Ask the solver to do a fresh capture via environment variable (solver can choose to honor this)
        env = None
        try:
            import os
            env = os.environ.copy()
            env['FORCE_FRESH_SCAN'] = '1'
        except Exception:
            env = None

        print(f"[+] Running cube solver: {' '.join(cmd)}")
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            env=env
        )

        # Log outputs for debugging
        print("[+] cube_solver stdout:\n" + (result.stdout or ""))
        print("[+] cube_solver stderr:\n" + (result.stderr or ""))

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
