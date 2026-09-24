import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Relay, proof } from '../src/relay.ts';
import type { Peer, Role } from '../src/relay.ts';
import { encodeFrame, parseFrame, CONFIG, KEY, MAX_PAYLOAD } from '../src/protocol.ts';

const keys = { teacher: 't'.repeat(43), admin: 'a'.repeat(43) };
class FakePeer implements Peer {
  sent: (string | Buffer)[] = []; closed = false; bufferedAmount = 0;
  send(d: string | Buffer) { this.sent.push(d); }
  close(_code: number, _reason: string) { this.closed = true; }
  messages() { return this.sent.filter((x): x is string => typeof x === 'string').map(x => JSON.parse(x)); }
}
function setup() {
  let now = 100000;
  const relay = new Relay(keys, () => now);
  function login(role: Role) {
    const peer = new FakePeer(); const client = relay.connect(peer)!;
    const challenge = peer.messages()[0];
    relay.receive(client, Buffer.from(JSON.stringify({ type: 'auth', role, proof: proof(keys[role], role, challenge.nonce, challenge.expires) })), false);
    return { peer, client };
  }
  function command(client: ReturnType<typeof login>['client'], value: object) { relay.receive(client, Buffer.from(JSON.stringify(value)), false); }
  function paired() {
    const t = login('teacher'); const a = login('admin');
    command(t.client, { type: 'status', state: 'ready' });
    command(t.client, { type: 'create_pair' });
    const code = t.peer.messages().find(x => x.type === 'pair_code').code;
    command(a.client, { type: 'pair', code });
    return { t, a, code };
  }
  return { relay, login, command, paired, advance: (ms: number) => { now += ms; } };
}
const config = () => encodeFrame({ kind: 1, flags: CONFIG, sequence: 1, ptsUs: 0n, payload: Buffer.from(JSON.stringify({ mime: 'video/avc', width: 854, height: 480, rotation: 90, csd: ['AQID'] })) });
const video = (flags = KEY) => encodeFrame({ kind: 1, flags, sequence: 2, ptsUs: 123456n, payload: Buffer.from([0, 0, 0, 1, 0x65]) });

test('protocol preserves timestamps, flags, and payload', () => {
  const bytes = video(); const f = parseFrame(bytes);
  assert.equal(f.ptsUs, 123456n); assert.equal(f.flags, KEY); assert.deepEqual(encodeFrame(f), bytes);
});
test('protocol rejects truncated, oversized, reserved and malformed config', () => {
  assert.throws(() => parseFrame(Buffer.alloc(23)));
  assert.throws(() => parseFrame(Buffer.alloc(MAX_PAYLOAD + 25)));
  for (const offset of [0, 2, 3, 4, 5, 6, 7, 20]) { const b = video(); b[offset] = 255; assert.throws(() => parseFrame(b)); }
  assert.throws(() => encodeFrame({ kind: 1, flags: CONFIG, sequence: 1, ptsUs: 0n, payload: Buffer.from('{}') }));
});
test('valid role credential authenticates; invalid proof rejected', () => {
  const s = setup(); assert.equal(s.login('teacher').peer.closed, false);
  const p = new FakePeer(); const c = s.relay.connect(p)!;
  s.command(c, { type: 'auth', role: 'admin', proof: 'bad' }); assert.equal(p.closed, true);
});
test('nonce replay on another connection fails', () => {
  const s = setup(); const p = new FakePeer(); const c = s.relay.connect(p)!;
  const challenge = p.messages()[0];
  const p2 = new FakePeer(); const c2 = s.relay.connect(p2)!;
  s.command(c2, { type: 'auth', role: 'admin', proof: proof(keys.admin, 'admin', challenge.nonce, challenge.expires) });
  assert.equal(p2.closed, true); s.relay.disconnect(c);
});
test('duplicate authenticated admin is rejected without evicting existing admin', () => {
  const s = setup(); const first = s.login('admin'); const second = s.login('admin');
  assert.equal(first.peer.closed, false); assert.equal(second.peer.closed, true);
});
test('unpaired admin cannot start capture', () => {
  const s = setup(); const a = s.login('admin'); s.command(a.client, { type: 'start_view' }); assert.equal(a.peer.closed, true);
});
test('paired live media relay and role separation', () => {
  const s = setup(); const { t, a } = s.paired();
  s.command(a.client, { type: 'start_view' });
  s.relay.receive(t.client, config(), true); s.relay.receive(t.client, video(), true);
  assert(a.peer.sent.some(x => Buffer.isBuffer(x) && x.equals(video())));
  s.relay.receive(a.client, video(), true); assert.equal(a.peer.closed, true);
});
test('pairing code expires and has a global five-guess limit', () => {
  const s = setup(); const t = s.login('teacher'); const a = s.login('admin');
  s.command(t.client, { type: 'create_pair' });
  const code = t.peer.messages().find(x => x.type === 'pair_code').code;
  for (let i = 0; i < 5; i++) s.command(a.client, { type: 'pair', code: '000000' });
  s.command(a.client, { type: 'pair', code }); assert(!a.peer.messages().some(x => x.type === 'paired'));
  s.command(t.client, { type: 'create_pair' }); s.advance(120001);
  const fresh = t.peer.messages().filter(x => x.type === 'pair_code').at(-1).code;
  s.command(a.client, { type: 'pair', code: fresh }); assert(!a.peer.messages().some(x => x.type === 'paired'));
});
test('backpressure drops delta video until config and keyframe recovery', () => {
  const s = setup(); const { t, a } = s.paired(); s.command(a.client, { type: 'start_view' });
  s.relay.receive(t.client, config(), true); a.peer.bufferedAmount = 300000;
  s.relay.receive(t.client, video(), true); a.peer.bufferedAmount = 0;
  const before = a.peer.sent.length; s.relay.receive(t.client, video(0), true); assert.equal(a.peer.sent.length, before);
  s.relay.receive(t.client, video(), true); assert(a.peer.sent.length > before);
});
test('local reset immediately revokes viewing', () => {
  const s = setup(); const { t, a } = s.paired(); s.command(a.client, { type: 'start_view' });
  s.command(t.client, { type: 'reset_pair' }); const n = a.peer.sent.length;
  s.relay.receive(t.client, video(), true); assert.equal(a.peer.sent.length, n);
  assert(a.peer.messages().some(x => x.type === 'reset_done'));
});
test('teacher disconnect marks offline and ends stream', () => {
  const s = setup(); const { t, a } = s.paired(); s.command(a.client, { type: 'start_view' }); s.relay.disconnect(t.client);
  assert(a.peer.messages().some(x => x.type === 'status' && x.state === 'offline'));
  s.command(a.client, { type: 'start_view' }); assert(a.peer.messages().some(x => x.type === 'error'));
});
test('sessions expire and unauthenticated sockets time out', () => {
  const s = setup(); const a = s.login('admin'); const p = new FakePeer(); s.relay.connect(p);
  s.advance(15001); s.relay.tick(); assert(p.closed); assert(!a.peer.closed);
  s.advance(600000); s.relay.tick(); assert(a.peer.closed);
});
test('oversized control and control flooding are rejected', () => {
  const s = setup(); const a = s.login('admin'); s.relay.receive(a.client, Buffer.alloc(4097), false); assert(a.peer.closed);
  const b = s.login('admin'); for (let i = 0; i < 25; i++) s.command(b.client, { type: 'ping', at: i }); assert(b.peer.closed);
});
test('admin cannot reset pairing or publish teacher status', () => {
  for (const type of ['reset_pair', 'status']) {
    const s = setup(); const { a } = s.paired(); s.command(a.client, { type, state: 'ready' }); assert(a.peer.closed);
  }
});
