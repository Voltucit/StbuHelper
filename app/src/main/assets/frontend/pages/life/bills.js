'use strict';

async function lifeBills() {
  if (!life.connected) return lifeLogin();
  sheet('消费账单', '<label for="bill-month">月份</label><input id="bill-month" type="month"><button class="text-button" id="load-bills">重新查询</button><div id="bill-results" role="status" aria-live="polite"></div>', () => {
    const now = new Date(); $('bill-month').value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    const button = $('load-bills'), results = $('bill-results'), monthInput = $('bill-month');
    let serial = 0, requestedMonth = '';
    async function load(force = false) {
      const month = monthInput.value;
      if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) { serial++; requestedMonth = ''; Motion.busy(button, false); button.textContent = '重新查询'; results.setAttribute('aria-busy', 'false'); results.textContent = '请选择月份'; return; }
      if (!force && requestedMonth === month) return;
      requestedMonth = month;
      const request = ++serial;
      Motion.busy(button, true); button.textContent = '查询中'; results.setAttribute('aria-busy', 'true');
      results.textContent = `正在查询 ${month} 的账单`;
      try {
        const result = await Native.call('lifeBills', { month });
        if (!results.isConnected || request !== serial || monthInput.value !== month) return;
        results.innerHTML = (result.bills.length ? result.bills.map(b => `<article class="record-row"><div class="name-wrap"><h3>${esc(b.name)}</h3><p>${esc(b.date)}</p></div><strong class="bill-amount">${esc(b.money)} 元</strong></article>`).join('') : '<p class="fine">这个月暂无完整的消费记录。</p>') + (result.incomplete ? `<p class="fine">${result.incomplete} 条账单暂未返回完整金额。</p>` : '');
        Motion.enter(results, 0, 4, 155);
      } catch (error) { if (results.isConnected && request === serial) results.textContent = error.message; }
      finally { if (button.isConnected && request === serial) { Motion.busy(button, false); button.textContent = '重新查询'; results.setAttribute('aria-busy', 'false'); } }
    }
    monthInput.onchange = () => load();
    monthInput.oninput = () => load();
    button.onclick = () => load(true);
    load();
  });
}
