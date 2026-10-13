const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('src/main/resources/nginx-1.18.0/html/citygo/login.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

async function check(succeed) {
  let resolveRequest, rejectRequest, tick, requests = 0, cleared = false;
  const pending = new Promise((resolve, reject) => { resolveRequest = resolve; rejectRequest = reject; });
  const context = {
    Vue: function(options) { Object.assign(this, options.data, options.methods); this.$message = { error() {} }; },
    axios: { post() { requests++; return pending; } },
    setInterval(callback) { tick = callback; return 1; },
    clearInterval() { cleared = true; }
  };
  vm.createContext(context);
  vm.runInContext(script, context);
  const app = vm.runInContext('app', context);
  app.form.phone = '13800000000';
  app.sendCode();
  app.sendCode();
  assert.equal(requests, 1, 'Sending must block duplicate clicks');
  assert.equal(tick, undefined, 'Countdown must wait for success');
  if (succeed) resolveRequest({ success: true }); else rejectRequest('发送失败');
  await new Promise(resolve => setImmediate(resolve));
  if (succeed) {
    assert.equal(app.codeBtnMsg, '60秒后可重发');
    for (let i = 0; i < 59; i++) tick();
    assert.equal(app.disabled, true);
    assert.equal(app.codeBtnMsg, '1秒后可重发');
    tick();
    assert.equal(cleared, true);
  } else assert.equal(tick, undefined);
  assert.equal(app.disabled, false);
  assert.equal(app.codeBtnMsg, '发送验证码');
}
(async () => {
  await check(true);
  await check(false);
  console.log('PASS: code sending success, failure, duplicate clicks and 60-second countdown');
})().catch(error => { console.error(error); process.exitCode = 1; });
