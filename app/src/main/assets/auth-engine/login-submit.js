/* Native supplies the payload as a JSON expression; credentials never reach an app server. */
(payload => {
  const school = location.origin === 'https://cas.stbu.edu.cn' && location.pathname === '/cas/login';
  const zf = location.origin === 'https://jwxt.stbu.edu.cn' && location.pathname === '/xtgl/login_slogin.html';
  const learning = location.origin === 'https://passport2.chaoxing.com' && location.pathname === '/login';
  if (!school && !zf && !learning) return false;
  const set = (selector, value) => { const e = document.querySelector(selector); if (!e) return false; e.value = value; e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); return true; };
  if (payload.kind === 'refresh') { const image = document.querySelector(school ? '#imgCode' : zf ? '#yzmPic' : '#numVerCode'); if (image) { image.__stbuImage = ''; image.click(); window.__stbuInlineSubmitted = false; } return !!image; }
  if (payload.kind === 'sendSms' && school) { const button = document.querySelector('#getSecondaryDynamic'); if (!button || button.disabled) return false; button.click(); return true; }
  if (payload.kind === 'sms' && school) { if (!set('#secondaryDynamicCode', payload.code)) return false; document.querySelector('#secondaryAuthForm button[type="submit"]')?.click(); return true; }
  if (school && (typeof rsaKeyReady === 'undefined' || !rsaKeyReady)) return false;
  if (!set(school ? '#fm1 #username' : zf ? '#yhm' : '#phone', payload.username) || !set(school ? '#fm1 #password' : zf ? '#mm' : '#pwd', payload.password)) return false;
  if (payload.code) set(school ? '#fm1 input[name="captcha"]' : zf ? '#yzm' : 'input[name="numcode"], #numcode', payload.code);
  const button = document.querySelector(school ? '#fm1 button[type="submit"], #fm1 input[type="submit"]' : zf ? '#dl' : '#loginBtn');
  if (!button || button.disabled) return false;
  window.__stbuInlineSubmitted = true;
  button.click(); // Keep the official RSA/AES, CSRF and device-validation submit handlers.
  return true;
})
