/*SEED*/
(function () {
  var KEY_BG = 'dshStudio.bg', KEY_OP = 'dshStudio.opacity';
  var SYNC_PREFIX = 'DSHSTUDIO_SYNC:';

  // —— 存储：localStorage 优先，不可用时退到内存（插件端才是权威存储，这里只是本页缓存）——
  function get(k) {
    try { var v = localStorage.getItem(k); if (v !== null) return v; } catch (e) { /* 忽略 */ }
    return (window.__dshMem || {})[k] != null ? window.__dshMem[k] : null;
  }
  function set(k, v) {
    try { localStorage.setItem(k, v); } catch (e) { /* 忽略 */ }
    window.__dshMem = window.__dshMem || {};
    window.__dshMem[k] = v;
  }
  function curBg() { return get(KEY_BG) || ''; }
  function curOp() { var o = parseFloat(get(KEY_OP)); return isNaN(o) ? 15 : o; }

  // 背景半透明浮层：fixed + pointer-events:none，盖在 dsh 界面之上但不挡点击
  function applyOverlay() {
    var bg = curBg();
    var d = document.getElementById('dsh-bg-overlay');
    if (!bg) {
      if (d && d.parentNode) d.parentNode.removeChild(d);
      return;
    }
    if (!d) {
      d = document.createElement('div');
      d.id = 'dsh-bg-overlay';
      (document.body || document.documentElement).appendChild(d);
    } else if (d.parentNode !== (document.body || document.documentElement)) {
      // SPA 重绘可能把浮层挪出/移除，重新挂回去
      (document.body || document.documentElement).appendChild(d);
    }
    d.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;z-index:900000;pointer-events:none;'
      + 'background-image:url("' + bg + '");background-size:cover;background-position:center;background-repeat:no-repeat;opacity:' + (curOp() / 100) + ';';
  }

  // 根据页面当前颜色方案选择卡片配色，尽量融入 dsh 主题
  function luminance(rgb) {
    var m = /rgba?\(([^)]+)\)/.exec(rgb || '');
    if (!m) return 1;
    var p = m[1].split(',').map(function (x) { return parseFloat(x); });
    if (p.length < 3) return 1;
    return (0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]) / 255;
  }
  function themeColors() {
    var cs = getComputedStyle(document.documentElement);
    var scheme = (document.documentElement.style.colorScheme || cs.colorScheme || '').toString().toLowerCase();
    var dark = scheme.indexOf('dark') !== -1
      || (cs.backgroundColor && cs.backgroundColor.indexOf('rgb') === 0 && luminance(cs.backgroundColor) < 0.5);
    return dark
      ? { bg: 'rgba(43,43,43,0.96)', fg: '#e8e8e8', border: '#555', sub: '#9a9a9a', btn: '#3a3a3a', btnFg: '#eee',
          topbar: 'rgba(18,18,18,0.55)' }
      : { bg: 'rgba(252,252,252,0.98)', fg: '#222', border: '#e3e3e3', sub: '#888', btn: '#fff', btnFg: '#222',
          topbar: 'rgba(255,255,255,0.6)' };
  }

  // —— 探测 dsh 设置弹窗与「通用设置」内容容器 ——

  function findSettingsDialog() {
    var d = document.querySelector('[role="dialog"]');
    if (d) return d;
    var all = document.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var t = all[i].textContent || '';
      if (t.indexOf('通用设置') !== -1 && all[i].children.length > 2 && all[i].offsetWidth > 200) {
        return all[i];
      }
    }
    return null;
  }

  function isGeneralPage(root) {
    var t = root.textContent || '';
    return t.indexOf('外观') !== -1 && t.indexOf('语言') !== -1;
  }

  function findMarker(root, markers) {
    var all = root.querySelectorAll('*');
    var fallback = null;
    for (var i = 0; i < all.length; i++) {
      var t = (all[i].textContent || '').trim();
      for (var m = 0; m < markers.length; m++) {
        if (t === markers[m] && all[i].children.length === 0) return all[i];
        if (fallback === null && t.indexOf(markers[m]) !== -1) fallback = all[i];
      }
    }
    return fallback;
  }

  function findListContainer(start) {
    var el = start;
    for (var i = 0; i < 12 && el && el !== document.body; i++) {
      var kids = el.children ? Array.prototype.slice.call(el.children) : [];
      var withText = kids.filter(function (k) { return (k.textContent || '').trim().length > 0; });
      if (kids.length >= 3 && withText.length >= 2) return el;
      el = el.parentElement;
    }
    return start ? start.parentElement : null;
  }

  // —— 把控制卡片注入到通用设置内容区 ——

  function buildCard(anchor) {
    var c = themeColors();
    var card = document.createElement('div');
    card.setAttribute('data-dshstudio', 'bg');
    card.style.cssText = 'margin:18px 0;padding:14px 16px;border:1px solid ' + c.border + ';border-radius:10px;'
      + 'background:' + c.bg + ';color:' + c.fg + ';font-family:inherit;font-size:13px;';
    card.innerHTML =
      '<div style="font-weight:600;margin-bottom:4px;">背景图 · DSH Studio 增强</div>'
      + '<div style="opacity:0.75;margin-bottom:12px;font-size:12px;">为界面叠加半透明背景图（保存在本机插件配置，刷新与重启后保留）。</div>'
      + '<div style="display:flex;align-items:center;gap:12px;margin-bottom:10px;flex-wrap:wrap;">'
      + '<label style="min-width:64px;opacity:0.85;">背景图片</label>'
      + '<input data-dsh="bg-file" type="file" accept="image/*" style="color:' + c.fg + ';">'
      + '</div>'
      + '<div data-dsh="bg-preview" style="margin-bottom:10px;"></div>'
      + '<button data-dsh="bg-clear" style="background:' + c.btn + ';color:' + c.btnFg + ';border:1px solid ' + c.border + ';border-radius:6px;padding:4px 10px;cursor:pointer;">移除背景</button>'
      + '<div style="margin:14px 0 4px;">浮层透明度：<span data-dsh="op-val">15%</span></div>'
      + '<input data-dsh="op-range" type="range" min="0" max="60" value="15" style="width:100%;">';
    anchor.appendChild(card);

    card.querySelector('[data-dsh="bg-file"]').addEventListener('change', function (e) {
      var file = e.target.files[0];
      if (!file) return;
      pickAndCompress(file, function (dataUrl) {
        if (!dataUrl) return;
        set(KEY_BG, dataUrl);
        applyOverlay();
        styleTopBar();
        syncCard();
        reportSync();
      });
    });
    card.querySelector('[data-dsh="bg-clear"]').addEventListener('click', function () {
      set(KEY_BG, '');
      applyOverlay();
      styleTopBar();
      syncCard();
      reportSync(); // 回传空串 → 插件端真正清空，刷新后不会再被恢复
    });
    var range = card.querySelector('[data-dsh="op-range"]');
    range.addEventListener('input', function () {
      set(KEY_OP, range.value);
      applyOverlay();
      syncCard();
      scheduleSync(); // 拖动时防抖回传
    });
    syncCard();
  }

  function updateCardTheme(card) {
    var c = themeColors();
    card.style.background = c.bg;
    card.style.color = c.fg;
    card.style.borderColor = c.border;
    var clear = card.querySelector('[data-dsh="bg-clear"]');
    if (clear) { clear.style.background = c.btn; clear.style.color = c.btnFg; clear.style.borderColor = c.border; }
  }

  function syncCard() {
    var card = document.querySelector('[data-dshstudio="bg"]');
    if (!card) return;
    var bg = curBg(), op = curOp();
    var range = card.querySelector('[data-dsh="op-range"]');
    if (range) { range.value = op; card.querySelector('[data-dsh="op-val"]').textContent = op + '%'; }
    var prev = card.querySelector('[data-dsh="bg-preview"]');
    if (prev) {
      prev.innerHTML = bg
        ? '<img src="' + bg + '" style="max-width:100%;max-height:96px;border-radius:6px;">'
        : '<span style="opacity:0.5;">未设置</span>';
    }
  }

  // 选图后压缩为 jpeg data URL（控制体积，便于回传与显示）
  function pickAndCompress(file, cb) {
    var reader = new FileReader();
    reader.onload = function () {
      var img = new Image();
      img.onload = function () {
        var max = 1920, w = img.width, h = img.height;
        if (w > max || h > max) {
          var r = max / Math.max(w, h);
          w = Math.round(w * r); h = Math.round(h * r);
        }
        var cv = document.createElement('canvas');
        cv.width = w; cv.height = h;
        cv.getContext('2d').drawImage(img, 0, 0, w, h);
        try { cb(cv.toDataURL('image/jpeg', 0.82)); }
        catch (e) { cb(null); }
      };
      img.onerror = function () { cb(null); };
      img.src = reader.result;
    };
    reader.onerror = function () { cb(null); };
    reader.readAsDataURL(file);
  }

  // 把当前 bg/op 回传给插件（前缀被 Java 端 CefDisplayHandler 拦截并持久化）
  function reportSync() {
    try { console.log(SYNC_PREFIX + JSON.stringify({ bg: curBg(), op: curOp() })); } catch (e) { /* 忽略 */ }
  }
  var syncTimer = null;
  function scheduleSync() {
    if (syncTimer) clearTimeout(syncTimer);
    syncTimer = setTimeout(reportSync, 300);
  }

  // 让 dsh 网页顶部菜单栏半透明 + 模糊，使背景图透出；无背景图时还原原样式
  function styleTopBar() {
    var sels = ['header', '[role="banner"]', '[class*="header" i]', '[class*="topbar" i]',
      '[class*="navbar" i]', '[class*="appbar" i]'];
    var el = null;
    for (var i = 0; i < sels.length && !el; i++) {
      var list = document.querySelectorAll(sels[i]);
      for (var j = 0; j < list.length; j++) {
        if (list[j].offsetHeight > 20 && list[j].getBoundingClientRect().top < 5) { el = list[j]; break; }
      }
    }
    if (!el) return;
    if (!curBg()) {
      if (el.__dshOrigBg !== undefined) {
        el.style.background = el.__dshOrigBg;
        el.style.backdropFilter = '';
        el.style.webkitBackdropFilter = '';
      }
      return;
    }
    if (el.__dshOrigBg === undefined) el.__dshOrigBg = el.style.background;
    el.style.background = themeColors().topbar;
    el.style.backdropFilter = 'blur(10px)';
    el.style.webkitBackdropFilter = 'blur(10px)';
  }

  function ensure() {
    // 插件端权威值：每次注入带一次，只应用一次（applied 标记由注入前缀复位）。
    // 只应用一次很关键 —— 否则 2s 轮询会把用户刚在页面里清空/改掉的值又覆盖回去。
    if (window.__dshRestore && !window.__dshRestoreApplied) {
      window.__dshRestoreApplied = true;
      set(KEY_BG, window.__dshRestore.bg || '');
      if (window.__dshRestore.opacity != null) set(KEY_OP, String(window.__dshRestore.opacity));
    }
    applyOverlay();

    var dialog = findSettingsDialog();
    if (dialog && isGeneralPage(dialog)) {
      var start = findMarker(dialog, ['外观', '繁忙时 Enter 键行为']);
      var anchor = start ? findListContainer(start) : dialog;
      if (anchor) {
        var existing = anchor.querySelector('[data-dshstudio="bg"]');
        if (!existing) buildCard(anchor);
        else { updateCardTheme(existing); syncCard(); }
      }
    }
    styleTopBar();
  }

  if (document.readyState !== 'loading') { ensure(); }
  else { document.addEventListener('DOMContentLoaded', ensure, { once: true }); }
  // SPA 首屏渲染可能晚于脚本注入，补几拍
  setTimeout(ensure, 300);
  setTimeout(ensure, 1000);
  setTimeout(ensure, 2500);
  // 避免重复注入时定时器叠加：用单一句柄
  if (window.__dshInterval) clearInterval(window.__dshInterval);
  window.__dshInterval = setInterval(ensure, 2000);
})();
