#!/usr/bin/env node
/**
 * Nav Plus convoy relay server
 * Rooms are ephemeral — no persistence, no storage.
 * Each message received from a member is broadcast to all other members of the same room.
 *
 * Deploy: node relay.js
 * Or with pm2: pm2 start relay.js --name nav-plus-relay
 *
 * Expects connections at ws://<host>:<PORT>/rooms/<CODE>
 */

const { WebSocketServer, WebSocket } = require('ws');
const { createServer } = require('http');
const { parse } = require('url');

const PORT = process.env.PORT || 8080;

// rooms: Map<roomCode, Set<WebSocket>>
const rooms = new Map();

const server = createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain' });
  res.end(`Nav Plus relay — ${rooms.size} active rooms\n`);
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws, req) => {
  const path = parse(req.url).pathname ?? '';
  const match = path.match(/^\/rooms\/([A-Z0-9]{1,16})$/i);
  if (!match) { ws.close(4000, 'Invalid room path'); return; }

  const code = match[1].toUpperCase();
  if (!rooms.has(code)) rooms.set(code, new Set());
  const room = rooms.get(code);
  room.add(ws);

  console.log(`[+] ${code} — ${room.size} member(s)`);

  ws.on('message', (data) => {
    // Broadcast to all other members
    for (const peer of room) {
      if (peer !== ws && peer.readyState === WebSocket.OPEN) {
        peer.send(data);
      }
    }
  });

  ws.on('close', () => {
    room.delete(ws);
    console.log(`[-] ${code} — ${room.size} member(s)`);
    if (room.size === 0) rooms.delete(code);
  });

  ws.on('error', () => { room.delete(ws); });
});

server.listen(PORT, () => {
  console.log(`Nav Plus relay listening on port ${PORT}`);
});
