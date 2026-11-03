"""Run the three backend processes in one Render container; expose only Spring."""
import os
import signal
import subprocess
import sys
import time
import urllib.request

children = []
stopping = False

def stop(signum, frame):
    global stopping
    stopping = True
    for child in children:
        if child.poll() is None:
            child.terminate()

def wait_ready(url):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline and not stopping:
        if any(child.poll() is not None for child in children):
            raise RuntimeError("An internal backend exited during startup")
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except OSError:
            pass
        time.sleep(.5)
    raise RuntimeError("Internal backend startup did not complete")

def main():
    for name, expected in (("APP_ENV", "production"), ("DATA_PROVIDER", "reef-danawa"), ("AI_PROVIDER", "gemini"), ("FIREBASE_MODE", "cloud")):
        if os.environ.get(name) != expected:
            raise RuntimeError(f"Deployment requires {name}={expected}")
    for name in ("INTERNAL_API_KEY", "REEF_API_KEY", "GEMINI_API_KEY", "GEMINI_MODEL", "FIREBASE_PROJECT_ID", "GOOGLE_APPLICATION_CREDENTIALS", "CORS_ALLOWED_ORIGINS"):
        if not os.environ.get(name, "").strip():
            raise RuntimeError(f"Missing required environment variable: {name}")
    if len(os.environ["INTERNAL_API_KEY"]) < 32:
        raise RuntimeError("INTERNAL_API_KEY must contain at least 32 characters")
    if not os.path.isfile(os.environ["GOOGLE_APPLICATION_CREDENTIALS"]):
        raise RuntimeError("Firebase secret file is missing")
    if any(name in os.environ for name in ("FIREBASE_AUTH_EMULATOR_HOST", "FIRESTORE_EMULATOR_HOST")):
        raise RuntimeError("Remove Firebase emulator variables for deployment")
    for name in ("AI_URL", "COLLECTOR_URL"):
        expected = "http://127.0.0.1:" + ("8000" if name == "AI_URL" else "4001")
        if os.environ.get(name) != expected:
            raise RuntimeError(f"This container requires the bundled loopback {name}")
    children.append(subprocess.Popen(["node", "src/start.mjs"], cwd="/app/backend/collector"))
    children.append(subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000"], cwd="/app/backend/ai"))
    wait_ready("http://127.0.0.1:4001/health")
    wait_ready("http://127.0.0.1:8000/health")
    children.append(subprocess.Popen(["java", "-jar", "/app/api.jar"]))
    while not stopping:
        if any(child.poll() is not None for child in children):
            raise RuntimeError("A backend exited; stopping the container for restart")
        time.sleep(.5)

signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
exit_code = 0
try:
    main()
except Exception as exc:
    print(str(exc), file=sys.stderr, flush=True)
    exit_code = 1
finally:
    stop(None, None)
    deadline = time.monotonic() + 10
    for child in children:
        try:
            child.wait(timeout=max(.1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            child.kill()
            child.wait()
sys.exit(exit_code)
