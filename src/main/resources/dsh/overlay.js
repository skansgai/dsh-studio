/*SEED*/
(function () {
  var KEY_BG = 'dshStudio.bg', KEY_OP = 'dshStudio.opacity', KEY_SEED = 'dshStudio.seeded';

  // 背景半透明浮层：fixed + pointer-events:none，盖在 dsh 界面之上但不挡点击
  function applyOverlay() {
    var bg = localStorage.getItem(KEY_BG) || '';
    var op = parseFloat(localStorage.getItem(KEY_OP));
    if (isNaN(op)) op = 15;
    var d = document.getElementById('dsh-bg-overlay');
    if (!bg) {
      if (d && d.parentNode) d.parentNode.removeChild(d);
      return;
    }
    if (!d) {
      d = document.createElement('div');
      d.id = 'dsh-bg-overlay';
      (document.body || document.documentElement).appendChild(d);
    }
    d.style.cssText = 'position:fixed;left:0;top:0;width:100%;height:100%;z-index:900000;pointer-events:none;'
      + 'background-image:url("' + bg + '");background-size:cover;background-position:center;background-repeat:no-repeat;opacity:' + (op / 100) + ';';
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
      ? { bg: 'rgba(43,43,43,0.96)', fg: '#e8e8e8', border: '#555', sub: '#9a9a9a', btn: '#3a3a3a', btnFg: '#eee' }
      : { bg: 'rgba(252,252,252,0.98)', fg: '#222', border: '#e3e3e3', sub: '#888', btn: '#fff', btnFg: '#222' };
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

  // 找带某段文字、且最可能是「该设置项标签」的元素（完全匹配叶子优先，其次第一个包含者）
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

  // 从标记元素向上找「含多个设置项的列表容器」
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
      + '<div style="opacity:0.75;margin-bottom:12px;font-size:12px;">为界面叠加半透明背景图（仅本机 dsh 网页生效，设置保存在本页）。</div>'
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
      var r = new FileReader();
      r.onload = function () { localStorage.setItem(KEY_BG, r.result); applyOverlay(); syncCard(); };
      r.readAsDataURL(file);
    });
    card.querySelector('[data-dsh="bg-clear"]').addEventListener('click', function () {
      localStorage.setItem(KEY_BG, '');
      applyOverlay();
      syncCard();
    });
    card.querySelector('[data-dsh="op-range"]').addEventListener('input', function (e) {
      localStorage.setItem(KEY_OP, e.target.value);
      applyOverlay();
      syncCard();
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
    var bg = localStorage.getItem(KEY_BG) || '';
    var op = parseFloat(localStorage.getItem(KEY_OP));
    if (isNaN(op)) op = 15;
    var range = card.querySelector('[data-dsh="op-range"]');
    if (range) { range.value = op; card.querySelector('[data-dsh="op-val"]').textContent = op + '%'; }
    var prev = card.querySelector('[data-dsh="bg-preview"]');
    if (prev) {
      prev.innerHTML = bg
        ? '<img src="' + bg + '" style="max-width:100%;max-height:96px;border-radius:6px;">'
        : '<span style="opacity:0.5;">未设置</span>';
    }
  }

  function ensure() {
    // 仅首次（localStorage 从没写过）用 IntelliJ 旧设置做种子；之后以网页端为准
    if (localStorage.getItem(KEY_SEED) === null) {
      localStorage.setItem(KEY_BG, window.__dshSeed && window.__dshSeed.bg ? window.__dshSeed.bg : '');
      localStorage.setItem(KEY_OP, String(window.__dshSeed && window.__dshSeed.opacity != null ? window.__dshSeed.opacity : 15));
      localStorage.setItem(KEY_SEED, '1');
    }
    applyOverlay();

    var dialog = findSettingsDialog();
    if (dialog && isGeneralPage(dialog)) {
      var start = findMarker(dialog, ['外观', '繁忙时 Enter 键行为']);
      var anchor = start ? findListContainer(start) : dialog;
      if (anchor) {
        var existing = anchor.querySelector('[data-dshstudio="bg"]');
        if (!existing) {
          buildCard(anchor);
        } else {
          updateCardTheme(existing);
          syncCard();
        }
      }
    }
  }

  if (document.readyState !== 'loading') { ensure(); }
  else { document.addEventListener('DOMContentLoaded', ensure); }
  setTimeout(ensure, 1500);
  setInterval(ensure, 2000);
})();
