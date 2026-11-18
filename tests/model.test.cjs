const test = require('node:test');
const assert = require('node:assert/strict');
const model = require('../app/src/main/assets/frontend/shared/model.js');
const parser = require('./helpers/portal-parser.cjs');

test('单双周、离散周与范围合并', () => {
  assert.deepEqual(model.range('1-8单周,11'), [1, 3, 5, 7, 11]);
  assert.deepEqual(model.range('2-8双周'), [2, 4, 6, 8]);
  assert.throws(() => model.range('0-90'));
});
test('开学周按本地周一计算，周日不提前跨周', () => {
  assert.equal(model.currentWeek('2026-09-02', new Date(2026, 8, 6, 23)), 1);
  assert.equal(model.currentWeek('2026-09-02', new Date(2026, 8, 7)), 2);
  assert.equal(model.currentWeek('', new Date()), null);
});
test('无效日期不会被 JavaScript 自动进位后接受', () => {
  assert.equal(model.dateOnly('2026-02-30'), null);
  assert.ok(model.dateOnly('2024-02-29'));
});
test('冬夏作息支持跨年日期区间', () => {
  assert.equal(model.season({ summerFrom: '11-01', summerTo: '03-01' }, new Date(2026, 0, 2)), 'summer');
  assert.equal(model.season({ summerFrom: '11-01', summerTo: '03-01' }, new Date(2026, 5, 2)), 'winter');
  assert.deepEqual(model.periodTimes({}), []);
  assert.throws(() => model.periodTimes({ summerTimes: '09:30-09:00' }, new Date(2026, 5, 2)));
});
test('同课重复成绩不重复累计学分，未通过不计入', () => {
  assert.deepEqual(model.creditSummary([{name:'数学',score:'80',credits:4},{name:'数学',score:'90',credits:4},{name:'英语',score:'59',credits:2},{name:'体育',score:'合格',credits:1}]), { credits: 5, count: 2 });
});
test('备份导出只保留学习数据顶层字段', () => {
  const source = { ...model.empty(), password: 'do-not-export', loginCode: 'secret' };
  assert.equal('password' in model.validate(source), false);
  assert.equal('loginCode' in model.validate(source), false);
});
test('拒绝损坏课程，避免将缺失周次当作全学期', () => {
  const source = model.empty(); source.courses = [{name:'数学',day:1,sections:[1],weeks:[]}];
  assert.throws(() => model.validate(source));
});
test('四川工商学院历史为途格式能解析多段安排', () => {
  const rows = [['序号','课程代码','学分','课程性质','课程名称','类别','考试','教师','学时','备注','上课时间'],['1','X','4','必修','高等数学','','','王老师','','','1-16周 星期一 1-2节\nA203\n3-9周 星期三 5-6节\nB305']];
  const records = parser.parseRows(rows, 'courses');
  assert.equal(records.length, 2); assert.equal(records[0].day, 1); assert.equal(records[0].room, 'A203');
  assert.equal(records[1].day, 3); assert.deepEqual(records[1].sections, [5, 6]);
});
test('成绩按表头取列，不依赖列顺序', () => {
  const rows = [['学期','成绩','课程名称','学分'],['2026秋','85','程序设计','3']];
  const result = parser.parseRows(rows, 'grades');
  assert.equal(result[0].name, '程序设计'); assert.equal(result[0].score, '85'); assert.equal(result[0].credits, '3');
});
test('登录页和无数据表格不会生成虚假课程', () => {
  assert.deepEqual(parser.parseRows([['用户名','密码'],['student','secret']], 'courses'), []);
});
