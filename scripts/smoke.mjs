// 端到端冒烟测试：机器人进服 → 选势力被传送 → 村庄成长 → 袭击/势力战争 → 不崩。
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
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? ' :: ' + String(detail).slice(0, 200) : ''}`);
};
const countOf = (text) => {
  const m = String(text).match(/已知村庄\s*(\d+)\s*个/);
  return m ? Number(m[1]) : -1;
};
const raidsOf = (text) => {
  const all = [...String(text).matchAll(/被袭击=(\d+)/g)].map((m) => Number(m[1]));
  return all.length ? Math.max(...all) : -1;
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
bot.on('death', () => console.log('bot died (creative 应该不会)'));
await new Promise((resolve, reject) => {
  const t = setTimeout(() => reject(new Error('机器人 60 秒内没进服')), 60000);
  bot.once('spawn', () => { clearTimeout(t); resolve(); });
});
await sleep(5000);
const pos0 = bot.entity.position.clone();
console.log('bot spawn at', pos0.x.toFixed(1), pos0.y.toFixed(1), pos0.z.toFixed(1));
check('机器人成功进服', true, `${pos0.x.toFixed(0)},${pos0.y.toFixed(0)},${pos0.z.toFixed(0)}`);

/* ---------- 选势力：应该被传送到首都附近 ---------- */
await exec('execute as TestBot run villagewar join azure');
await sleep(3000);
const pos1 = bot.entity.position.clone();
const moved = Math.hypot(pos1.x - pos0.x, pos1.z - pos0.z);
check('选势力后离开出生点', moved > 800, `移动 ${moved.toFixed(0)} 格 → ${pos1.x.toFixed(0)},${pos1.z.toFixed(0)}`);
await exec('gamemode creative TestBot');
const facOut = await exec('execute as TestBot run villagewar faction');
check('势力被记录', /苍蓝/.test(facOut), facOut);

/* ---------- 掠夺者营地 ---------- */
const campOut = await exec('/villagewar camp');
check('掠夺者营地已生成', /战旗/.test(campOut) && /等级/.test(campOut), campOut);

/* ---------- 等首都长房子 ---------- */
await sleep(15000);
const baseStatus = await exec('/villagewar status');
const baseCount = countOf(baseStatus);
check('两个首都都登记成村子', baseCount >= 2, `count=${baseCount} :: ${baseStatus}`);

/* ---------- 手动 raid ---------- */
const raidMsg = await exec('execute at TestBot run villagewar raid');
check('raid 能开打', /袭击已开始/.test(raidMsg), raidMsg);
await sleep(30000);
const midStatus = await exec('/villagewar status');
check('袭击期间服务器仍响应', /已知村庄/.test(midStatus), midStatus);
check('袭击被记录', raidsOf(midStatus) >= 1, midStatus);

/* ---------- 等这场打完 ---------- */
console.log('等 170 秒让这场袭击收尾…');
await sleep(170000);

/* ---------- 势力战争：站自家村子，敌方来打 ---------- */
const beforeWar = await exec('/villagewar status');
const raidsBefore = raidsOf(beforeWar);
const warOut = await exec('execute at TestBot run villagewar wartick');
await sleep(3000);
await exec('execute at TestBot run villagewar wartick');
await sleep(3000);
const afterWar = await exec('/villagewar status');
check('势力战争能触发', raidsOf(afterWar) > raidsBefore, `${warOut} | 前=${raidsBefore} 后=${raidsOf(afterWar)}`);

const finalCount = countOf(afterWar);
check('村子没有被玩家走动拆散', finalCount === baseCount, `base=${baseCount} final=${finalCount}`);

const bad = results.filter((r) => !r.ok);
bot.quit();
await exec('/stop').catch(() => {});
sock.end();
if (bad.length) {
  console.error(`\n${bad.length} 项失败`);
  process.exit(1);
}
console.log('\n全部通过');
