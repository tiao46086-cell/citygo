// Node 内置断言和 VM，无需安装前端依赖。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const context = {
  axios: { defaults: {}, interceptors: { request: { use() {} }, response: { use() {} } } },
  sessionStorage: { getItem() { return null; } },
  window: { location: { search: '' } }, URLSearchParams
};
vm.createContext(context);
vm.runInContext(fs.readFileSync('src/main/resources/nginx-1.18.0/html/hmdp/js/common.js', 'utf8'), context);
for (const keyword of ['咖啡&茶', 'A+B #/店', '50%折扣', '普通搜索']) {
  const query = context.axios.defaults.paramsSerializer({ name: keyword, current: 0, active: false, absent: null });
  const params = new URLSearchParams(query);
  assert.equal(params.get('name'), keyword);
  assert.equal(params.get('current'), '0');
  assert.equal(params.get('active'), 'false');
  assert.equal(params.has('absent'), false);
  context.window.location.search = '?' + query;
  assert.equal(vm.runInContext('util.getUrlParam("name")', context), keyword);
}
context.window.location.search = '?name=A+B';
assert.equal(vm.runInContext('util.getUrlParam("name")', context), 'A B');
console.log('PASS: query encoding and decoding (5 cases)');
