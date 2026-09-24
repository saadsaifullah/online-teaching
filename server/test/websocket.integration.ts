import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { WebSocket } from 'ws';
import { proof } from '../src/relay.ts';
import type { Role } from '../src/relay.ts';
import { encodeFrame, CONFIG, KEY } from '../src/protocol.ts';

test('HTTP health, real WebSocket pairing, media and Stop', { timeout: 15000 }, async () => {
  const keys = { teacher: 't'.repeat(43), admin: 'a'.repeat(43) };
  const process = spawn(globalThis.process.execPath, ['--experimental-strip-types', 'src/index.ts'], {
    cwd: new URL('..', import.meta.url), env: { ...globalThis.process.env, PORT: '0', TEACHER_KEY: keys.teacher, ADMIN_KEY: keys.admin }
  });
  const sockets: WebSocket[] = [];
  try {
    const port = await new Promise<number>((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Relay startup timeout')), 5000);
      process.stdout.on('data', data => {
        const m = String(data).match(/listening on (\d+)/);
        if (m) { clearTimeout(timeout); resolve(Number(m[1])); }
      });
      process.once('error', reject);
    });
    const health = await fetch(`http://127.0.0.1:${port}/health`); assert.equal(health.status, 200);
    async function client(role: Role) {
      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`); sockets.push(ws);
      const messages: Record<string, unknown>[] = []; const binaries: Buffer[] = [];
      ws.on('message', (data, binary) => {
        if (binary) binaries.push(data as Buffer); else messages.push(JSON.parse(data.toString()));
      });
      const wait = async (type: string): Promise<Record<string, unknown>> => {
        const deadline = Date.now() + 4000;
        while (Date.now() < deadline) {
          const index = messages.findIndex(x => x.type === type);
          if (index >= 0) return messages.splice(index, 1)[0]!;
          await new Promise(resolve => setTimeout(resolve, 10));
        }
        throw new Error(`Missing ${type}`);
      };
      const send = (o: object) => ws.send(JSON.stringify(o));
      const challenge = await wait('challenge');
      send({ type: 'auth', role, proof: proof(keys[role], role, String(challenge.nonce), Number(challenge.expires)) });
      await wait('authenticated');
      return { ws, wait, send, binaries };
    }
    const t = await client('teacher'); const a = await client('admin');
    t.send({ type: 'status', state: 'ready' }); t.send({ type: 'create_pair' });
    const code = (await t.wait('pair_code')).code; a.send({ type: 'pair', code }); await a.wait('paired');
    a.send({ type: 'start_view' }); assert.equal((await a.wait('viewing')).active, true);
    t.ws.send(encodeFrame({ kind: 1, flags: CONFIG, sequence: 1, ptsUs: 0n, payload: Buffer.from(JSON.stringify({ mime: 'video/avc', width: 640, height: 480, rotation: 90, csd: ['AQID'] })) }));
    const frame = encodeFrame({ kind: 1, flags: KEY, sequence: 2, ptsUs: 2000n, payload: Buffer.from([0, 0, 0, 1, 0x65]) });
    t.ws.send(frame);
    const until = Date.now() + 3000;
    while (!a.binaries.some(b => b.equals(frame)) && Date.now() < until) await new Promise(resolve => setTimeout(resolve, 10));
    assert(a.binaries.some(b => b.equals(frame)));
    a.send({ type: 'end_view' }); assert.equal((await a.wait('viewing')).active, false);
    const count = a.binaries.length; t.ws.send(frame);
    await new Promise(resolve => setTimeout(resolve, 100)); assert.equal(a.binaries.length, count);
  } finally {
    for (const ws of sockets) ws.terminate();
    process.kill('SIGTERM'); await once(process, 'exit');
  }
});
