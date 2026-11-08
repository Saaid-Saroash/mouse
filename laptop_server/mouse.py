# remote_mouse.py
# Python 3.13
# Single-file remote mouse + keyboard server using aiohttp + pynput

import sys
import asyncio
import os
import json
import socket
from aiohttp import web, WSMsgType
from pynput import mouse as pymouse
from pynput import keyboard as pykeyboard
from pynput.keyboard import Key

if getattr(sys, "frozen", False):
    BASE_DIR = sys._MEIPASS
else:
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))

HOST = "0.0.0.0"
# HTTP port is selected automatically: first free port from 8080-8090,
# then an OS-assigned ephemeral port as a fallback.
PORT_START = 8080
PORT_END = 8090
DISCOVERY_PORT = 39567
DISCOVERY_MESSAGE = b"REMOTE_MOUSE_DISCOVER"

MIN_SENS = 0.8
MAX_SENS = 3.0
SENSITIVITY = 1.05

mouse = pymouse.Controller()
keyboard = pykeyboard.Controller()

move_residual_x = 0.0
move_residual_y = 0.0
scroll_residual_y = 0.0

HTML = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,viewport-fit=cover" />
<title>Remote Mouse</title>
<style>
:root{
  --bg1:#000000;
  --bg2:#000000;
  --line:rgba(255,255,255,0.07);
  --line2:rgba(255,255,255,0.1);
  --text:#e8eef7;
  --accent:#14b8a6;
  --accent2:#22c1c3;
  --shadow:0 16px 40px rgba(0,0,0,0.35);
  --radius:22px;
}

*{
  box-sizing:border-box;
  -webkit-tap-highlight-color: transparent;
}

html,body{
  margin:0;
  width:100%;
  height:100%;
  overflow:hidden;
  background:#000000;
  font-family:Inter,system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;
  color:var(--text);
}

body{
  padding:
    max(8px, env(safe-area-inset-top))
    max(8px, env(safe-area-inset-right))
    max(8px, env(safe-area-inset-bottom))
    max(8px, env(safe-area-inset-left));
}

.app{
  width:100%;
  height:100%;
}

.shell{
  width:100%;
  height:100%;
  display:grid;
  grid-template-rows:auto 1fr auto;
  gap:8px;
  background:#030607;
  border:1px solid var(--line);
  border-radius:var(--radius);
  box-shadow:var(--shadow);
  padding:10px;
  overflow:hidden;
}

.topbar{
  display:flex;
  align-items:center;
  justify-content:space-between;
  gap:8px;
  min-height:42px;
}

.leftcluster,
.rightcluster{
  display:flex;
  align-items:center;
  gap:8px;
  min-width:0;
}

.status-pill{
  width:14px;
  height:14px;
  border-radius:50%;
  background:rgba(255,0,0,0.4);
  box-shadow:0 0 0 4px rgba(255,0,0,0.08);
}

.status-pill.ok{
  background:linear-gradient(90deg,#5eead4,#34d399);
  box-shadow:0 0 0 4px rgba(16,185,129,0.1);
}

.btn{
  appearance:none;
  border:none;
  outline:none;
  cursor:pointer;
  user-select:none;
  touch-action:manipulation;
  border-radius:14px;
  width:42px;
  height:42px;
  display:flex;
  align-items:center;
  justify-content:center;
  font-weight:800;
  font-size:18px;
  color:var(--text);
  background:rgba(255,255,255,0.03);
  border:1px solid var(--line);
  transition:transform 180ms ease, background 180ms ease, border-color 180ms ease;
}

.btn:active,
.big-btn:active,
.scroll-btn:active{
  transform:scale(0.97);
  background:rgba(255,255,255,0.06);
  border-color:var(--line2);
}

.sens-box{
  display:flex;
  align-items:center;
  gap:8px;
  padding:4px 6px;
  border-radius:16px;
  background:rgba(255,255,255,0.02);
  border:1px solid var(--line);
}

.sens-val{
  min-width:60px;
  text-align:center;
  padding:8px 10px;
  border-radius:12px;
  background:rgba(255,255,255,0.03);
  border:1px solid var(--line);
  font-weight:800;
  color:var(--text);
}

.main{
  min-height:0;
  display:grid;
  grid-template-columns:minmax(0,1fr) 58px;
  gap:8px;
}

.mousepad{
  min-height:0;
  border-radius:20px;
  border:1px solid var(--line);
  background:
    radial-gradient(circle at 20% 20%, rgba(20,184,166,0.07), transparent 24%),
    radial-gradient(circle at 80% 80%, rgba(34,193,195,0.05), transparent 26%),
    rgba(255,255,255,0.015);
  position:relative;
  overflow:hidden;
  touch-action:none;
}

.scroll-rail{
  display:grid;
  grid-template-rows:42px 1fr 42px;
  gap:6px;
  min-height:0;
}

.scroll-btn{
  display:flex;
  align-items:center;
  justify-content:center;
  border-radius:16px;
  border:1px solid var(--line);
  background:rgba(255,255,255,0.03);
  color:var(--text);
  font-size:18px;
  font-weight:900;
  touch-action:manipulation;
  user-select:none;
  transition:transform 180ms ease, background 180ms ease, border-color 180ms ease;
}

.scroll-track{
  position:relative;
  border-radius:18px;
  border:1px solid var(--line);
  background:rgba(255,255,255,0.02);
  overflow:hidden;
  touch-action:none;
  min-height:0;
  transform: scaleY(-1);
}

.scroll-track::before{
  content:"";
  position:absolute;
  left:50%;
  top:8px;
  bottom:8px;
  width:3px;
  transform:translateX(-50%);
  border-radius:999px;
  background:rgba(255,255,255,0.12);
}

.bottom{
  display:grid;
  grid-template-columns:1fr 1fr;
  gap:8px;
}

.big-btn{
  width:100%;
  padding:16px 14px;
  border-radius:18px;
  border:1px solid var(--line);
  background:rgba(255,255,255,0.03);
  color:var(--text);
  font-weight:800;
  font-size:16px;
  display:flex;
  align-items:center;
  justify-content:center;
  touch-action:manipulation;
  user-select:none;
  transition:transform 180ms ease, background 180ms ease, border-color 180ms ease;
}

.hidden-input{
  position:fixed;
  left:-9999px;
  top:-9999px;
  opacity:0;
  width:1px;
  height:1px;
}

@media (max-width:520px){
  .shell{
    padding:8px;
    gap:8px;
    border-radius:20px;
  }

  .topbar{
    min-height:40px;
  }

  .btn{
    width:40px;
    height:40px;
    border-radius:13px;
  }

  .main{
    grid-template-columns:minmax(0,1fr) 54px;
    gap:7px;
  }

  .scroll-rail{
    grid-template-rows:40px 1fr 40px;
    gap:6px;
  }

  .big-btn{
    padding:15px 12px;
    font-size:15px;
    border-radius:16px;
  }
}
</style>
</head>
<body>
<div class="app">
  <div class="shell" role="application" aria-label="Remote control">
    <div class="topbar">
      <div class="leftcluster">
        <div id="dot" class="status-pill"></div>
        <button id="kbdBtn" class="btn" type="button" aria-label="keyboard">⌨️</button>
      </div>

      <div class="rightcluster">
        <div class="sens-box" aria-label="sensitivity controls">
          <button id="sensDec" class="btn" type="button" aria-label="decrease sensitivity">−</button>
          <div id="sensVal" class="sens-val">1.1×</div>
          <button id="sensInc" class="btn" type="button" aria-label="increase sensitivity">+</button>
        </div>
      </div>
    </div>

    <div class="main">
      <div id="mousepad" class="mousepad"></div>

      <div class="scroll-rail" aria-label="scroll controls">
        <button id="scrollUp" class="scroll-btn" type="button" aria-label="scroll up">▲</button>
        <div id="track" class="scroll-track" aria-label="scroll drag area"></div>
        <button id="scrollDown" class="scroll-btn" type="button" aria-label="scroll down">▼</button>
      </div>
    </div>

    <div class="bottom">
      <button id="leftBtn" class="big-btn" type="button" aria-label="left click">Left</button>
      <button id="rightBtn" class="big-btn" type="button" aria-label="right click">Right</button>
    </div>

    <input id="hiddenInput" class="hidden-input" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" />
  </div>
</div>

<script>
(() => {
  const STORAGE_KEY = "remote_mouse_sensitivity";
  const MIN_SENS = 0.8;
  const MAX_SENS = 3.0;
  const STEP = 0.1;

  const CLIP = (v, min, max) => Math.max(min, Math.min(max, v));

  let sensitivity = (() => {
    const saved = parseFloat(localStorage.getItem(STORAGE_KEY));
    return Number.isFinite(saved) ? CLIP(+saved.toFixed(2), MIN_SENS, MAX_SENS) : 1.1;
  })();

  const wsUrl = `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`;
  let ws = null;

  const dot = document.getElementById("dot");
  const sensInc = document.getElementById("sensInc");
  const sensDec = document.getElementById("sensDec");
  const sensVal = document.getElementById("sensVal");
  const mousepad = document.getElementById("mousepad");
  const hiddenInput = document.getElementById("hiddenInput");
  const kbdBtn = document.getElementById("kbdBtn");
  const leftBtn = document.getElementById("leftBtn");
  const rightBtn = document.getElementById("rightBtn");
  const track = document.getElementById("track");
  const scrollUp = document.getElementById("scrollUp");
  const scrollDown = document.getElementById("scrollDown");

  let keyboardOpen = false;
  let ignoreNextTap = false;

  function send(obj){
    if(!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify(obj));
  }

  function connect(){
    ws = new WebSocket(wsUrl);
    ws.onopen = () => {
      dot.classList.add("ok");
      send({type:"set_sensitivity", value:sensitivity});
    };
    ws.onclose = () => dot.classList.remove("ok");
    ws.onerror = () => dot.classList.remove("ok");
  }

  connect();

  function updateSensDisplay(){
    sensVal.textContent = sensitivity.toFixed(1) + "×";
    localStorage.setItem(STORAGE_KEY, String(sensitivity));
  }

  function updateSensitivity(delta){
    sensitivity = CLIP(+(sensitivity + delta).toFixed(2), MIN_SENS, MAX_SENS);
    updateSensDisplay();
    send({type:"set_sensitivity", value:sensitivity});
  }

  updateSensDisplay();

  sensInc.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    e.stopPropagation();
    closeKeyboard();
    updateSensitivity(STEP);
  });

  sensDec.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    e.stopPropagation();
    closeKeyboard();
    updateSensitivity(-STEP);
  });

  function openKeyboard(){
    ignoreNextTap = true;
    keyboardOpen = true;
    hiddenInput.focus({preventScroll:true});
    setTimeout(() => { ignoreNextTap = false; }, 200);
  }

  function closeKeyboard(){
    keyboardOpen = false;
    hiddenInput.blur();
  }

  kbdBtn.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    e.stopPropagation();
    openKeyboard();
  });

  hiddenInput.addEventListener("focus", () => {
    keyboardOpen = true;
  });

  hiddenInput.addEventListener("blur", () => {
    keyboardOpen = false;
  });

  hiddenInput.addEventListener("input", () => {
    const v = hiddenInput.value;
    if(v.length > 0){
      send({type:"text", text:v});
      hiddenInput.value = "";
    }
  });

  hiddenInput.addEventListener("keydown", (e) => {
    if(e.key === "Backspace"){
      send({type:"key", key:"BACKSPACE"});
      e.preventDefault();
    } else if(e.key === "Enter"){
      send({type:"key", key:"ENTER"});
      e.preventDefault();
    }
  });

  let last = {x:0, y:0, down:false};
  let activePointerId = null;
  let moveQueue = [];
  let downX = 0, downY = 0, downTime = 0;

  mousepad.addEventListener("pointerdown", (e) => {
    if (ignoreNextTap) return;
    e.preventDefault();
    e.stopPropagation();
    try { mousepad.setPointerCapture(e.pointerId); } catch(_) {}
    closeKeyboard();
    last.down = true;
    last.x = e.clientX;
    last.y = e.clientY;
    downX = e.clientX;
    downY = e.clientY;
    downTime = performance.now();
    activePointerId = e.pointerId;
    moveQueue = [];
  });

  mousepad.addEventListener("pointermove", (e) => {
    if(!last.down || e.pointerId !== activePointerId) return;
    e.preventDefault();
    const dx = e.clientX - last.x;
    const dy = e.clientY - last.y;
    last.x = e.clientX;
    last.y = e.clientY;
    moveQueue.push({dx, dy});
  });

  mousepad.addEventListener("pointerup", (e) => {
    if(e.pointerId !== activePointerId) return;
    e.preventDefault();
    try { mousepad.releasePointerCapture(e.pointerId); } catch(_) {}
    last.down = false;
    activePointerId = null;

    const duration = performance.now() - downTime;
    const moveDist = Math.hypot(e.clientX - downX, e.clientY - downY);

    if(!ignoreNextTap && duration < 250 && moveDist < 10 && moveQueue.length <= 6){
      send({type:"click", button:"left"});
    }

    moveQueue = [];
  });

  mousepad.addEventListener("pointercancel", () => {
    last.down = false;
    activePointerId = null;
    moveQueue = [];
  });

  let smoothMoveX = 0;
  let smoothMoveY = 0;
  const MOVE_SMOOTHING = 0.28;

  function flushMoves(){
    if(moveQueue.length){
      let sumX = 0;
      let sumY = 0;
      for(const m of moveQueue){
        sumX += m.dx;
        sumY += m.dy;
      }

      smoothMoveX += (sumX - smoothMoveX) * MOVE_SMOOTHING;
      smoothMoveY += (sumY - smoothMoveY) * MOVE_SMOOTHING;

      send({type:"move", dx: smoothMoveX, dy: smoothMoveY});
      moveQueue = [];
    }
    requestAnimationFrame(flushMoves);
  }
  requestAnimationFrame(flushMoves);

  function bindActionButton(el, fn){
    el.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      e.stopPropagation();
      closeKeyboard();
      fn();
    });
  }

  bindActionButton(leftBtn, () => send({type:"click", button:"left"}));
  bindActionButton(rightBtn, () => send({type:"click", button:"right"}));

  mousepad.addEventListener("touchstart", (e) => {
    if(e.touches.length === 2){
      e.preventDefault();
      closeKeyboard();
      send({type:"click", button:"right"});
    }
  }, {passive:false});

  let scrollStartY = null;
  let scrollQueue = 0;
  let smoothScroll = 0;
  const SCROLL_SMOOTHING = 0.18;

  function sendScroll(amount){
    send({type:"scroll", dy: amount});
  }

  function flushScroll(){
    if(Math.abs(scrollQueue) > 0.01){
      smoothScroll += (scrollQueue - smoothScroll) * SCROLL_SMOOTHING;
      sendScroll(smoothScroll);
      scrollQueue = 0;
    }
    requestAnimationFrame(flushScroll);
  }
  requestAnimationFrame(flushScroll);

  function bindHoldButton(el, direction){
    el.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      e.stopPropagation();
      closeKeyboard();
      sendScroll(direction);
      try { el.setPointerCapture(e.pointerId); } catch(_) {}
    });
  }

  // Fixed:
  // Up button now scrolls up
  // Down button now scrolls down
  // Reduced speed a little
  bindHoldButton(scrollUp, 1.0);
  bindHoldButton(scrollDown, -1.0);

  track.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    e.stopPropagation();
    closeKeyboard();
    try { track.setPointerCapture(e.pointerId); } catch(_) {}
    scrollStartY = e.clientY;
  });

  track.addEventListener("pointermove", (e) => {
    if(scrollStartY === null) return;
    e.preventDefault();
    const dy = e.clientY - scrollStartY;
    scrollStartY = e.clientY;
    scrollQueue += (dy * 0.14);
  });

  track.addEventListener("pointerup", (e) => {
    e.preventDefault();
    try { track.releasePointerCapture(e.pointerId); } catch(_) {}
    scrollStartY = null;
  });

  track.addEventListener("pointercancel", () => {
    scrollStartY = null;
  });

  setInterval(() => {
    if(ws && ws.readyState === WebSocket.OPEN){
      ws.send(JSON.stringify({type:"ping"}));
    } else {
      connect();
    }
  }, 2500);

  document.addEventListener("gesturestart", (e) => e.preventDefault());

  let lastWindowHeight = window.innerHeight;
  window.addEventListener("resize", () => {
    const diff = Math.abs(window.innerHeight - lastWindowHeight);
    if(diff > 80){
      ignoreNextTap = true;
      setTimeout(() => { ignoreNextTap = false; }, 250);
    }
    lastWindowHeight = window.innerHeight;
  });
})();
</script>
</body>
</html>
"""

async def index(request):
    return web.Response(text=HTML, content_type="text/html")

async def get_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()

async def websocket_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                except json.JSONDecodeError:
                    continue
                await handle_client_message(data)
    finally:
        pass

    return ws

async def handle_client_message(msg):
    global SENSITIVITY, move_residual_x, move_residual_y, scroll_residual_y

    t = msg.get("type")

    if t == "move":
        dx = float(msg.get("dx", 0))
        dy = float(msg.get("dy", 0))
        try:
            raw_x = (dx * SENSITIVITY) + move_residual_x
            raw_y = (dy * SENSITIVITY) + move_residual_y

            send_x = int(round(raw_x))
            send_y = int(round(raw_y))

            move_residual_x = raw_x - send_x
            move_residual_y = raw_y - send_y

            if send_x or send_y:
                mouse.move(send_x, send_y)
        except Exception:
            pass

    elif t == "click":
        b = msg.get("button", "left")
        try:
            if b == "left":
                mouse.click(pymouse.Button.left, 1)
            else:
                mouse.click(pymouse.Button.right, 1)
        except Exception:
            pass

    elif t == "scroll":
        dy = float(msg.get("dy", 0))
        try:
            scroll_residual_y += dy
            step = int(scroll_residual_y)
            if step != 0:
                mouse.scroll(0, step)
                scroll_residual_y -= step
        except Exception:
            pass

    elif t == "text":
        text = msg.get("text", "")
        if text:
            try:
                keyboard.type(text)
            except Exception:
                pass

    elif t == "key":
        key = msg.get("key")
        try:
            if key == "BACKSPACE":
                keyboard.press(Key.backspace)
                keyboard.release(Key.backspace)
            elif key == "ENTER":
                keyboard.press(Key.enter)
                keyboard.release(Key.enter)
        except Exception:
            pass

    elif t == "set_sensitivity":
        try:
            val = float(msg.get("value", SENSITIVITY))
            SENSITIVITY = round(max(MIN_SENS, min(MAX_SENS, val)), 2)
        except Exception:
            pass

class DiscoveryProtocol(asyncio.DatagramProtocol):
    """Answers phone-app broadcasts with the server's selected TCP port."""
    def __init__(self, http_port):
        self.http_port = http_port
        self.transport = None

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if data.strip() != DISCOVERY_MESSAGE or self.transport is None:
            return
        payload = json.dumps({"service": "remote-mouse", "port": self.http_port}).encode("utf-8")
        self.transport.sendto(payload, addr)


async def get_bound_http_port(site):
    server = getattr(site, "_server", None)
    if server is None or not server.sockets:
        raise RuntimeError("Could not read the HTTP server's bound port")
    return int(server.sockets[0].getsockname()[1])


async def run_server():
    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_get("/ws", websocket_handler)

    runner = web.AppRunner(app)
    discovery_transport = None
    await runner.setup()
    site = None
    chosen_port = None
    try:
        # Prefer the familiar 8080 port when free, but choose another one
        # automatically when it is already occupied.
        for candidate in range(PORT_START, PORT_END + 1):
            candidate_site = web.TCPSite(runner, HOST, candidate)
            try:
                await candidate_site.start()
                site = candidate_site
                chosen_port = candidate
                break
            except OSError:
                continue

        if site is None:
            site = web.TCPSite(runner, HOST, 0)
            await site.start()
            chosen_port = await get_bound_http_port(site)

        loop = asyncio.get_running_loop()
        udp_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        try:
            udp_socket.bind((HOST, DISCOVERY_PORT))
            discovery_transport, _ = await loop.create_datagram_endpoint(
                lambda: DiscoveryProtocol(chosen_port), sock=udp_socket
            )
        except OSError as exc:
            udp_socket.close()
            raise RuntimeError(
                f"Could not open UDP discovery port {DISCOVERY_PORT}. "
                "Close the other Remote Mouse server instance or allow Python through Windows Firewall."
            ) from exc

        ip = await get_local_ip()
        print("=== Remote Mouse Server ===")
        print(f"Server: http://{ip}:{chosen_port}")
        print(f"WebSocket: ws://{ip}:{chosen_port}/ws")
        print(f"Auto-discovery: UDP {DISCOVERY_PORT}")
        print("Open Remote Mouse on your phone and select Wi-Fi; IP and port are discovered automatically.")
        print("Keep this window running. Allow Python on Private networks if Windows Firewall asks.")
        print(f"Sensitivity: {SENSITIVITY}")
        print("===========================")
        await asyncio.Event().wait()
    finally:
        if discovery_transport is not None:
            discovery_transport.close()
        await runner.cleanup()


def main():
    asyncio.run(run_server())

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(0)