// 验证真实公共拦截器和个人页脚本的跳转配合，无需浏览器依赖。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const root = 'src/main/resources/nginx-1.18.0/html/citygo/';
const common = fs.readFileSync(root + 'js/common.js', 'utf8');
const info = fs.readFileSync(root + 'info.html', 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];

async function check(status, onLogin = false) {
  const history = ['/', '/info.html'];
  let redirects = 0;
  let savedToken = 'expired-token';
  let rejectResponse;
  const messages = [];
  const location = {
    pathname: onLogin ? '/login.html' : '/info.html',
    replace(url) { history[history.length - 1] = url; redirects++; }
  };
  const context = {
    axios: {
      defaults: {},
      interceptors: { request: { use() {} }, response: { use(ok, fail) { rejectResponse = fail; } } },
      get() { return rejectResponse({ response: { status } }); }
    },
    sessionStorage: { getItem() { return savedToken; }, removeItem() { savedToken = null; } },
    window: { location }, location, URLSearchParams,
    console: { log() {} },
    Vue: function(options) {
      Object.assign(this, options.data, options.methods);
      this.$message = { error(message) { messages.push(message); } };
      this.queryUserInfo = this.queryBlogs = () => { throw new Error('Unauthorized user data requested'); };
      options.created.call(this);
    }
  };
  vm.createContext(context);
  vm.runInContext(common, context);
  vm.runInContext(info, context);
  await new Promise(resolve => setImmediate(resolve));
  if (status === 401) {
    assert.equal(savedToken, null);
    assert.equal(redirects, onLogin ? 0 : 1);
    await rejectResponse({ response: { status: 401 } }).catch(() => {});
    assert.equal(redirects, onLogin ? 0 : 1, 'Repeated 401 must not redirect twice');
    if (!onLogin) {
      assert.deepEqual(history, ['/', '/login.html']);
      history.pop();
      assert.equal(history.at(-1), '/', 'One back navigation returns home');
    }
  } else {
    assert.equal(redirects, 0, 'Server errors must not send the user to login');
    assert.equal(savedToken, 'expired-token');
    assert.equal(messages[0], '服务器异常');
  }
}

(async () => {
  await check(401);
  await check(401, true);
  await check(500);
  console.log('PASS: login navigation (401, repeated 401, login page, server error)');
})().catch(error => { console.error(error); process.exitCode = 1; });
