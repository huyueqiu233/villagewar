// 极简 RCON 客户端 + 冒烟测试：验证 mod 在无界面服务端里能加载、村庄能被识别、袭击能开打。
// 用法：node scripts/rcon-test.mjs [host] [port] [password]
import net from 'node:net';

const HOST = process.argv[2] || '127.0.0.1';
const PORT = Number(process.argv[3] || 25575);
const PASS = process.argv[4] || 'test123';

let nextId = 1;
const pending = new Map();
let buf = Buffer.alloc(0);

const sock = net.connect(PORT, HOST);
const send = (type, body) => new Promise((resolve, reject) => {
  const id = nextId++;
  const payload = Buffer.from(body, 'utf8');
  const len = 4 + 4 + payload.length + 2;
  const pkt = Buffer.alloc(4 + len);
  pkt.writeInt32LE(len, 0);
  pkt.writeInt32LE(id, 4);
  pkt.writeInt32LE(type, 8);
  payload.copy(pkt, 12);
  pkt.writeInt16LE(0, 12 + payload.length);
  pending.set(id, { resolve, reject });
  sock.write(pkt);
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function pump() {
  while (buf.length >= 4) {
    const len = buf.readInt32LE(0);
    if (buf.length < 4 + len) return;
    const pkt = buf.subarray(4, 4 + len);
    buf = buf.subarray(4 + len);
    const id = pkt.readInt32LE(0);
    const body = pkt.subarray(8, pkt.length - 2).toString('utf8');
    const p = pending.get(id);
    if (p) { pending.delete(id); p.resolve(body); }
  }
}
sock.on('data', (d) => { buf = Buffer.concat([buf, d]); pump(); });
sock.on('error', (e) => { console.error('RCON 连接失败：', e.message); process.exit(1); });

async function exec(cmd) {
  return send(2, cmd);
}

const results = [];
function check(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' :: ' + detail.slice(0, 160) : ''}`);
}

await new Promise((r) => sock.once('connect', r));
await sleep(300);
await send(3, PASS);
await sleep(300);

const status0 = await exec('/villagewar status');
check('status 命令可用', /已知村庄/.test(status0), status0);

// 在出生点附近放 4 个村民（超平坦世界，y=-59 是地表）
const spots = [[5, -59, 5], [7, -59, 5], [5, -59, 7], [8, -59, 8]];
for (const [x, y, z] of spots) await exec(`/summon minecraft:villager ${x} ${y} ${z}`);
await exec('/time set day');
console.log('等 25 秒让成长系统识别村庄…');
await sleep(25000);

const status1 = await exec('/villagewar status');
const m = status1.match(/已知村庄\s*(\d+)\s*个/);
const count = m ? Number(m[1]) : -1;
check('村庄被识别', count >= 1, `count=${count} :: ${status1}`);

const raidMsg = await exec('/villagewar raid');
check('raid 命令能开打', /袭击已开始/.test(raidMsg), raidMsg);

console.log('等 45 秒看袭击有没有崩…');
await sleep(45000);

const status2 = await exec('/villagewar status');
check('袭击期间服务器仍响应', /已知村庄/.test(status2), status2);

const bad = results.filter((r) => !r.ok);
await exec('/stop').catch(() => {});
sock.end();

if (bad.length) {
  console.error(`\n${bad.length} 项失败`);
  process.exit(1);
}
console.log('\n全部通过');
