'use strict';

let rechargeLaunching = false;
const rechargeReturnKey = 'stbu.recharge-return.v1';
let rechargeReturnAt = 0;
try { rechargeReturnAt = Number(localStorage.getItem(rechargeReturnKey)) || 0; } catch (_) {}

function clearRechargeReturn() {
  rechargeReturnAt = 0;
  try { localStorage.removeItem(rechargeReturnKey); } catch (_) {}
}

async function lifeRecharge() {
  if (!life.connected) return lifeLogin();
  if (rechargeLaunching) return;
  rechargeLaunching = true; rechargeReturnAt = Date.now();
  try { localStorage.setItem(rechargeReturnKey, String(rechargeReturnAt)); } catch (_) {}
  render();
  try { await Native.call('lifeRecharge'); }
  catch (error) { clearRechargeReturn(); toast(error.message); }
  finally { rechargeLaunching = false; render(); }
}

async function refreshAfterRecharge() {
  const pending = rechargeReturnAt > 0 && Date.now() - rechargeReturnAt < 24 * 60 * 60 * 1000;
  if (!pending) { clearRechargeReturn(); return false; }
  clearRechargeReturn();
  if (!life.connected) return false;
  route = 'life'; render();
  try { await lifeRefresh(true); toast('已返回，余额已刷新'); }
  catch (_) { toast('余额暂未刷新，请稍后点“刷新余额”'); }
  // Returning from Alipay does not prove payment succeeded; only display the queried balance.
  return true;
}
