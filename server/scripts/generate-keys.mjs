import { randomBytes } from 'node:crypto';
console.log('TEACHER_KEY=' + randomBytes(32).toString('base64url'));
console.log('ADMIN_KEY=' + randomBytes(32).toString('base64url'));
