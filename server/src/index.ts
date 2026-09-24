import { createServer } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { Relay } from './relay.ts';
import { MAX_PAYLOAD } from './protocol.ts';

const relay = new Relay({ teacher: process.env.TEACHER_KEY ?? '', admin: process.env.ADMIN_KEY ?? '' });
const server = createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') { res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); res.end('{"ok":true}'); }
  else { res.writeHead(404); res.end(); }
});
server.headersTimeout = 10000; server.requestTimeout = 15000;
const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD + 24, perMessageDeflate: false, clientTracking: true });
server.on('upgrade', (req, socket, head) => {
  if (req.url !== '/ws' || req.headers.origin || wss.clients.size >= 12) { socket.destroy(); return; }
  // Android clients do not send Origin. Reject browser-based cross-site access.
  wss.handleUpgrade(req, socket, head, ws => wss.emit('connection', ws, req));
});
const alive = new WeakMap<WebSocket, boolean>();
wss.on('connection', ws => {
  alive.set(ws, true);
  const client = relay.connect({
    send: data => { if (ws.readyState === WebSocket.OPEN) ws.send(data); },
    close: (code, reason) => ws.close(code, reason),
    get bufferedAmount() { return ws.bufferedAmount; }
  });
  if (!client) return;
  ws.on('pong', () => alive.set(ws, true));
  ws.on('message', (raw, binary) => {
    const data = Buffer.isBuffer(raw) ? raw : raw instanceof ArrayBuffer ? Buffer.from(raw) : Buffer.concat(raw);
    relay.receive(client, data, binary);
  });
  ws.on('close', () => relay.disconnect(client));
  ws.on('error', () => { relay.disconnect(client); ws.terminate(); });
});
const sessions = setInterval(() => relay.tick(), 1000);
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!alive.get(ws)) { ws.terminate(); continue; }
    alive.set(ws, false); ws.ping();
  }
}, 20000);
server.listen(Number(process.env.PORT ?? 8000), '0.0.0.0', () => { const address = server.address(); console.log(`Online Teachers relay listening on ${typeof address === 'object' ? address?.port : address}`); });
function shutdown(): void {
  clearInterval(sessions); clearInterval(heartbeat);
  for (const ws of wss.clients) ws.close(1001, 'Server restarting');
  server.close(); setTimeout(() => process.exit(0), 1500).unref();
}
process.on('SIGTERM', shutdown); process.on('SIGINT', shutdown);
