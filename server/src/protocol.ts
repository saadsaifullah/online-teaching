export const MAX_PAYLOAD = 524288;
export const CONFIG = 1;
export const KEY = 2;
export type Frame = { kind: number; flags: number; sequence: number; ptsUs: bigint; payload: Buffer };
export function parseFrame(data: Buffer): Frame {
  if (data.length < 25 || data.length > MAX_PAYLOAD + 24) throw new Error('Frame length');
  if (data[0] !== 0x4f || data[1] !== 0x54 || data[2] !== 1 || ![1, 2].includes(data[3] ?? 0)) throw new Error('Header');
  const flags = data[4] ?? 255;
  if (flags > 3 || data[5] !== 0 || data[6] !== 0 || data[7] !== 0) throw new Error('Flags/reserved');
  const ptsUs = data.readBigInt64BE(12);
  if (ptsUs < 0n || data.readUInt32BE(20) !== data.length - 24) throw new Error('Length/timestamp');
  const frame = { kind: data[3]!, flags, sequence: data.readUInt32BE(8), ptsUs, payload: data.subarray(24) };
  if (flags & CONFIG) validateConfig(frame);
  return frame;
}
export function encodeFrame(f: Frame): Buffer {
  const b = Buffer.alloc(24 + f.payload.length);
  b[0] = 0x4f; b[1] = 0x54; b[2] = 1; b[3] = f.kind; b[4] = f.flags;
  b.writeUInt32BE(f.sequence, 8); b.writeBigInt64BE(f.ptsUs, 12); b.writeUInt32BE(f.payload.length, 20); f.payload.copy(b, 24);
  parseFrame(b); return b;
}
function validateConfig(f: Frame): void {
  if (f.payload.length > 16384) throw new Error('Configuration too large');
  const o = JSON.parse(f.payload.toString()) as Record<string, unknown>;
  if (!o || o.mime !== (f.kind === 1 ? 'video/avc' : 'audio/mp4a-latm')) throw new Error('Codec');
  if (!Array.isArray(o.csd) || o.csd.length < 1 || o.csd.length > 3 || o.csd.some(x => typeof x !== 'string' || x.length > 8192 || !/^[A-Za-z0-9+/]+={0,2}$/.test(x))) throw new Error('CSD');
  if (f.kind === 1) {
    if (![o.width, o.height].every(x => typeof x === 'number' && Number.isInteger(x) && x >= 16 && x <= 1920)) throw new Error('Dimensions');
    if (![0, 90, 180, 270].includes(Number(o.rotation))) throw new Error('Rotation');
  } else if (o.sampleRate !== 44100 || o.channels !== 1) throw new Error('Audio format');
}
