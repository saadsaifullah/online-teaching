import { createHmac, randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { CONFIG, KEY, parseFrame } from './protocol.ts';

export type Role = 'teacher' | 'admin';
export interface Peer {
  send(data: string | Buffer): void;
  close(code: number, reason: string): void;
  bufferedAmount: number;
}
type Client = {
  peer: Peer; nonce: string; expires: number; role?: Role; authenticatedAt?: number;
  window: number; count: number; mediaWindow: number; mediaBytes: number;
};
export function proof(key: string, role: Role, nonce: string, expires: number): string {
  return createHmac('sha256', key).update(`${role}:${nonce}:${expires}`).digest('base64');
}
function equal(a: string, b: string): boolean {
  const x = Buffer.from(a); const y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}
export class Relay {
  private clients = new Set<Client>();
  private teacher?: Client;
  private admin?: Client;
  private paired = false;
  private code = '';
  private codeExpires = 0;
  private guesses = 0;
  private viewing = false;
  private state = 'offline';
  private waitingKey = true;
  private lastRequest = 0;
  private configs = new Map<number, Buffer>();
  private acceptsAt = 0;
  private accepts = 0;
  constructor(privateKeys: Record<Role, string>, now: () => number = Date.now) {
    this.keys = privateKeys; this.now = now;
    for (const key of Object.values(privateKeys)) if (!/^[A-Za-z0-9_-]{43}$/.test(key)) throw new Error('Generate two 32-byte keys using npm run keys');
    if (privateKeys.admin === privateKeys.teacher) throw new Error('Device keys must differ');
  }
  private keys: Record<Role, string>;
  private now: () => number;
  connect(peer: Peer): Client | undefined {
    const now = this.now();
    if (now - this.acceptsAt >= 60000) { this.acceptsAt = now; this.accepts = 0; }
    if (++this.accepts > 60 || this.clients.size >= 12) { peer.close(1013, 'Connection limit'); return; }
    const c: Client = { peer, nonce: randomBytes(32).toString('base64url'), expires: now + 15000, window: now, count: 0, mediaWindow: now, mediaBytes: 0 };
    this.clients.add(c);
    this.send(c, { type: 'challenge', nonce: c.nonce, expires: c.expires });
    return c;
  }
  receive(c: Client, data: Buffer, binary: boolean): void {
    if (!this.clients.has(c)) return;
    try {
      if (binary) { this.media(c, data); return; }
      if (data.length > 4096) throw new Error('Control size');
      const now = this.now();
      if (now - c.window >= 1000) { c.window = now; c.count = 0; }
      if (++c.count > 20) throw new Error('Rate limit');
      const o = JSON.parse(data.toString()) as Record<string, unknown>;
      if (!o || typeof o.type !== 'string' || Array.isArray(o)) throw new Error('Invalid control');
      if (!c.role) { this.authenticate(c, o); return; }
      if (o.type === 'ping') {
        if (!Number.isSafeInteger(o.at) || Number(o.at) < 0) throw new Error('Ping');
        this.send(c, { type: 'pong', at: o.at }); return;
      }
      if (c.role === 'teacher') this.teacherCommand(c, o); else this.adminCommand(c, o);
    } catch { this.reject(c, 'Invalid or unauthorized message'); }
  }
  private authenticate(c: Client, o: Record<string, unknown>): void {
    if (o.type !== 'auth' || (o.role !== 'teacher' && o.role !== 'admin') || typeof o.proof !== 'string' || this.now() > c.expires) throw new Error('Authentication');
    const role = o.role;
    const expected = proof(this.keys[role], role, c.nonce, c.expires);
    c.nonce = ''; // consume the challenge even if verification fails
    if (!equal(expected, o.proof)) throw new Error('Authentication');
    if ((role === 'teacher' && this.teacher) || (role === 'admin' && this.admin)) {
      this.send(c, { type: 'error', message: 'This device role is already connected; disconnect it first.' });
      this.reject(c, 'Duplicate connection'); return;
    }
    c.role = role; c.authenticatedAt = this.now();
    if (role === 'teacher') { this.teacher = c; this.state = 'connecting'; }
    else this.admin = c;
    this.send(c, { type: 'authenticated', sessionId: randomBytes(24).toString('base64url'), expires: this.now() + 600000 });
    this.status();
  }
  private teacherCommand(c: Client, o: Record<string, unknown>): void {
    switch (o.type) {
      case 'status':
        if (!['ready', 'connecting', 'error'].includes(String(o.state))) throw new Error('State');
        this.state = String(o.state);
        if (this.state !== 'ready') this.end();
        this.status(); break;
      case 'create_pair':
        if (this.paired) { this.send(c, { type: 'error', message: 'Already paired. Reset pairing on this Teacher phone first.' }); return; }
        this.code = randomInt(100000, 1000000).toString(); this.codeExpires = this.now() + 120000; this.guesses = 0;
        this.send(c, { type: 'pair_code', code: this.code, expires: this.codeExpires }); break;
      case 'reset_pair':
        this.paired = false; this.code = ''; this.end();
        this.send(c, { type: 'reset_done' }); if (this.admin) this.send(this.admin, { type: 'reset_done' });
        this.status(); break;
      default: throw new Error('Teacher control');
    }
  }
  private adminCommand(c: Client, o: Record<string, unknown>): void {
    if (o.type === 'pair') {
      if (this.paired || !this.teacher || !this.code || this.now() > this.codeExpires || ++this.guesses > 5 || typeof o.code !== 'string' || !equal(this.code, o.code)) {
        if (this.guesses >= 5) this.code = '';
        this.send(c, { type: 'error', message: 'Pairing failed. Request a fresh code on the Teacher phone.' }); return;
      }
      this.paired = true; this.code = '';
      this.send(c, { type: 'paired' }); this.send(this.teacher, { type: 'paired' }); this.status(); return;
    }
    if (!this.paired) throw new Error('Pair first');
    if (o.type === 'end_view') { this.end(); this.status(); return; }
    if (!this.teacher || this.state !== 'ready') {
      this.send(c, { type: 'error', message: 'Teacher phone offline or unavailable — local activation may be required.' }); return;
    }
    if (o.type === 'start_view') {
      if (this.viewing) return;
      this.viewing = true; this.waitingKey = true; this.configs.clear();
      this.send(c, { type: 'viewing', active: true });
      this.send(this.teacher, { type: 'viewer', active: true }); this.status(); return;
    }
    if (this.viewing && ['switch_camera', 'pause_video', 'resume_video', 'keyframe', 'reduce_bitrate'].includes(String(o.type))) {
      if (o.type === 'switch_camera' || o.type === 'resume_video') this.waitingKey = true;
      this.send(this.teacher, { type: o.type }); return;
    }
    throw new Error('Admin control');
  }
  private media(c: Client, data: Buffer): void {
    if (c !== this.teacher || !c.role) throw new Error('Media role');
    const f = parseFrame(data);
    if (!this.paired || !this.viewing || !this.admin) return;
    const now = this.now();
    if (now - c.mediaWindow >= 1000) { c.mediaWindow = now; c.mediaBytes = 0; }
    if ((c.mediaBytes += data.length) > 2000000) throw new Error('Media rate');
    if (f.flags & CONFIG) this.configs.set(f.kind, Buffer.from(data));
    if (this.admin.peer.bufferedAmount > 256000) {
      this.waitingKey = true;
      if (now - this.lastRequest > 1000) { this.lastRequest = now; this.send(c, { type: 'reduce_bitrate' }); this.send(c, { type: 'keyframe' }); }
      return;
    }
    if (f.kind === 1 && !(f.flags & CONFIG) && this.waitingKey) {
      if (!(f.flags & KEY) || !this.configs.has(1)) return;
      for (const config of this.configs.values()) this.admin.peer.send(config);
      this.waitingKey = false;
    }
    this.admin.peer.send(data);
  }
  private end(): void {
    this.viewing = false; this.waitingKey = true; this.configs.clear();
    if (this.teacher) this.send(this.teacher, { type: 'viewer', active: false });
    if (this.admin) this.send(this.admin, { type: 'viewing', active: false });
  }
  private status(): void {
    if (this.admin) this.send(this.admin, { type: 'status', state: this.viewing ? 'viewing' : this.state, paired: this.paired });
  }
  private send(c: Client, data: Record<string, unknown>): void { c.peer.send(JSON.stringify(data)); }
  private reject(c: Client, reason: string): void { this.disconnect(c); c.peer.close(1008, reason); }
  disconnect(c: Client): void {
    if (!this.clients.delete(c)) return;
    if (c === this.teacher) {
      this.teacher = undefined; this.state = 'offline'; this.code = ''; this.end(); this.status();
    }
    if (c === this.admin) { this.admin = undefined; this.end(); }
  }
  tick(): void {
    const now = this.now();
    for (const c of this.clients) {
      if (!c.role && now > c.expires) this.reject(c, 'Authentication timeout');
      else if (c.authenticatedAt !== undefined && now - c.authenticatedAt > 600000) this.reject(c, 'Session expired; reconnect');
    }
    if (now > this.codeExpires) this.code = '';
  }
}
