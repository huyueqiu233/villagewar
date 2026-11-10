// 端到端冒烟测试：真的连一个机器人进服（成长系统只对"有玩家的维度"生效），
// 放村民 → 等村庄被识别 → 控制台触发袭击 → 检查服务器没崩。
// 用法：node scripts/smoke.mjs
import net from 'node:net';
import mineflayer from 'mineflayer';

const HOST = '127.0.0.1';
const PORT = 25575;
const PASS = 'test123';

/* ---------- 极简 RCON ---------- */
let nextId = 1;
const pending = new Map();
let buf = Buffer.alloc(0);
const sock = net.connect(PORT, HOST);
const send = (type, body) => new Promise((resolve) => {
  const id = nextId++;
  const payload = Buffer.from(body, 'utf8');
  const len = 4 + 4 + payload.length + 2;
  const pkt = Buffer.alloc(4 + len);
  pkt.writeInt32LE(len, 0);
  pkt.writeInt32LE(id, 4);
  pkt.writeInt32LE(type, 8);
  payload.copy(pkt, 12);
  pkt.writeInt16LE(0, 12 + payload.length);
  pending.set(id, resolve);
  sock.write(pkt);
});
sock.on('data', (d) => {
  buf = Buffer.concat([buf, d]);
  while (buf.length >= 4) {
    const len = buf.readInt32LE(0);
    if (buf.length < 4 + len) break;
    const pkt = buf.subarray(4, 4 + len);
    buf = buf.subarray(4 + len);
    const id = pkt.readInt32LE(0);
    const r = pending.get(id);
    if (r) { pending.delete(id); r(pkt.subarray(8, pkt.length - 2).toString('utf8')); }
  }
});
const exec = (cmd) => send(2, cmd);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' :: ' + String(detail).slice(0, 180) : ''}`);
};

await new Promise((r) => sock.once('connect', r));
await sleep(300);
await send(3, PASS);
await sleep(300);

const status0 = await exec('/villagewar status');
check('status 命令可用', /已知村庄/.test(status0), status0);

/* ---------- 机器人进服 ---------- */
const bot = mineflayer.createBot({ host: HOST, port: 25565, username: 'TestBot', auth: 'offline', version: '1.21.1' });
bot.on('kicked', (r) => console.log('bot kicked:', JSON.stringify(r).slice(0, 200)));
bot.on('error', (e) => console.log('bot error:', e.message));

await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('机器人 60 秒内没进服')), 60000);
  bot.once('spawn', () => { clearTimeout(t); resolve(); });
});
await sleep(5000);
const pos = bot.entity.position;
console.log('bot spawn at', pos.x.toFixed(1), pos.y.toFixed(1), pos.z.toFixed(1));
check('机器人成功进服', true, `${pos.x.toFixed(0)},${pos.y.toFixed(0)},${pos.z.toFixed(0)}`);

/* ---------- 选势力：应该被传送到首都附近 ---------- */
const joinOut = await exec('execute as TestBot run villagewar join azure');
await sleep(3000);
const posAfter = bot.entity.position;
const moved = Math.hypot(posAfter.x - pos.x, posAfter.z - pos.z);
check('选势力后离开出生点', moved > 800, `移动 ${moved.toFixed(0)} 格 → ${posAfter.x.toFixed(0)},${posAfter.z.toFixed(0)}`);
const facOut = await exec('execute as TestBot run villagewar faction');
check('势力被记录', /苍蓝/.test(facOut), `join=${joinOut} | ${facOut}`);

/* ---------- 在首都旁边放 4 个村民，等成长系统识别 ---------- */
const bx = Math.floor(posAfter.x);
const by = Math.floor(posAfter.y);
const bz = Math.floor(posAfter.z);
const spots = [[0, 0], [2, 0], [0, 2], [2, 2]];
for (const [dx, dz] of spots) {
  await exec(`/summon minecraft:villager ${bx + dx} ${by} ${bz + dz}`);
}
await exec('/time set day');

let count = 0;
let last = '';
for (let i = 0; i < 18; i += 1) {           // 最多等 90 秒
  await sleep(5000);
  last = await exec('/villagewar status');
  const m = last.match(/已知村庄\s*(\d+)\s*个/);
  count = m ? Number(m[1]) : -1;
  if (count >= 1) break;
}
check('村庄被识别', count >= 1, `count=${count} :: ${last}`);

const raidMsg = await exec('/villagewar raid');
check('raid 能开打', /袭击已开始/.test(raidMsg), raidMsg);

console.log('等 40 秒观察袭击…');
await sleep(40000);
const after = await exec('/villagewar status');
check('袭击期间服务器仍响应', /已知村庄/.test(after), after);
const mc = after.match(/已知村庄\s*(\d+)\s*个/);
check('村民走动不会把村子拆开', mc && Number(mc[1]) === 1, 'count=' + (mc ? mc[1] : '?'));
check('袭击已记录', /被袭击=1/.test(after) || /被袭击=[1-9]/.test(after), after);

const bad = results.filter((r) => !r.ok);
bot.quit();
await exec('/stop').catch(() => {});
sock.end();
if (bad.length) {
  console.error(`\n${bad.length} 项失败`);
  process.exit(1);
}
console.log('\n全部通过');
