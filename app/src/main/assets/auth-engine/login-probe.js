/* Executed only by the isolated official-site WebView; no native bridge exists there. */
(() => {
  const visible = e => !!e && getComputedStyle(e).display !== 'none' && getComputedStyle(e).visibility !== 'hidden' && !!(e.offsetWidth || e.offsetHeight || e.getClientRects().length);
  const text = selector => [...document.querySelectorAll(selector)].filter(visible).map(e => e.textContent.trim()).filter(Boolean).join(' ').slice(0, 180);
  const school = location.origin === 'https://cas.stbu.edu.cn' && location.pathname === '/cas/login';
  const zf = location.origin === 'https://jwxt.stbu.edu.cn' && location.pathname === '/xtgl/login_slogin.html';
  const learning = location.origin === 'https://passport2.chaoxing.com' && location.pathname === '/login';
  if (!school && !zf && !learning) return { stage: 'waiting' };
  if (school && visible(document.querySelector('#secondaryAuthModal')) && document.querySelector('#secondaryAuthModal').getAttribute('aria-hidden') !== 'true') {
    const phone = document.querySelector('#secondaryPhone')?.value || '';
    return { stage: 'sms', phone: phone ? '尾号 ' + phone.slice(-4) : '', canSend: !document.querySelector('#getSecondaryDynamic')?.disabled, message: text('.secondary-auth-error') };
  }
  if (school && !window.__stbuPasswordTab) { document.querySelector('#passtab')?.click(); window.__stbuPasswordTab = true; }
  const error = text(school ? '#fm1 .textError, #fm1 .errors' : zf ? '#tips, #error, .error' : '#err-txt, #phoneMsg, #pwdMsg');
  if (error) return { stage: 'failed', message: error };
  const slider = document.querySelector('#captcha');
  if (learning && slider && [...slider.children].some(visible)) return { stage: 'webRequired', message: '学习通要求安全验证，请主动打开官方网页完成。' };
  const input = document.querySelector(school ? '#fm1 input[name="captcha"]' : zf ? '#yzm' : 'input[name="numcode"], #numcode');
  const image = document.querySelector(school ? '#imgCode' : zf ? '#yzmPic' : '#numVerCode');
  let captcha = '';
  if (visible(input) && image) {
    try {
      if (!image.complete || !image.naturalWidth) return { stage: 'waiting' };
      if (!image.__stbuImage || image.__stbuSource !== image.src) {
        const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight;
        canvas.getContext('2d').drawImage(image, 0, 0); image.__stbuImage = canvas.toDataURL('image/png'); image.__stbuSource = image.src;
      }
      captcha = image.__stbuImage;
    } catch (_) { return { stage: 'webRequired', message: '验证码暂时无法在 App 表单中显示，请主动打开官方网页。' }; }
  }
  if (window.__stbuInlineSubmitted) return { stage: error ? 'failed' : 'waiting', message: error };
  if (captcha) return { stage: 'captcha', image: captcha, message: error };
  const user = document.querySelector(school ? '#fm1 #username' : zf ? '#yhm' : '#phone');
  const password = document.querySelector(school ? '#fm1 #password' : zf ? '#mm' : '#pwd');
  if (!user || !password) return { stage: 'waiting' };
  if (school && (typeof rsaKeyReady === 'undefined' || !rsaKeyReady)) return { stage: 'waiting' };
  return { stage: 'ready', message: error };
})()
