'use strict';

function meView() {
  return `<div class="menu-list">${menu('学习通账号', academic.learning ? '已连接，会话保存在本机' : '登录后同步追踪课程的作业', 'open-learning')}${menu('学校教务账号', academic.school ? '已连接，同步课表、考试与成绩' : '四川工商学院正方教务系统', 'open-school')}${menu('趣智校园账号', life.connected ? '已连接' : Native.ios ? '查询钱包余额和账单' : '连接洗澡、饮水服务', 'life-login')}${menu('账号管理', '查看校园账号连接状态', 'connect')}</div>
    <h2>应用</h2><div class="menu-list">${menu('设置', '主题颜色、夜间模式、首页提示与本机数据', 'app-settings')}</div>
    <h2>关于</h2><div class="menu-list">${menu('隐私与数据', '凭据本地加密，直接连接官方服务', 'privacy')}${menu('开源项目', '协议参考与许可说明', 'credits')}</div><p class="fine stats-note">${esc(appInfo.name)}${appInfo.versionName ? ' ' + esc(appInfo.versionName) : ''} · Power By Voltucit<br>使用时若出现意外，作者不承担其责任</p>`;
}
