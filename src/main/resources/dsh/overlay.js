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

  // 通用设置浮层：齿轮按钮 + 弹层，控制项位于 dsh 界面内
  function buildPanel() {
    if (document.getElementById('dsh-settings-fab')) return;
    var fab = document.createElement('div');
    fab.id = 'dsh-settings-fab';
    fab.textContent = '⚙';
    fab.title = '通用设置';
    fab.style.cssText = 'position:fixed;right:16px;bottom:16px;width:40px;height:40px;border-radius:50%;z-index:999999;'
      + 'background:rgba(40,40,40,0.72);color:#eee;font-size:20px;line-height:40px;text-align:center;cursor:pointer;'
      + 'box-shadow:0 2px 8px rgba(0,0,0,0.4);user-select:none;';
    var pop = document.createElement('div');
    pop.id = 'dsh-settings-panel';
    pop.style.cssText = 'position:fixed;right:16px;bottom:64px;width:280px;z-index:999999;background:#2b2b2b;color:#eee;'
      + 'border:1px solid #444;border-radius:10px;padding:12px;font-family:sans-serif;font-size:13px;'
      + 'box-shadow:0 4px 16px rgba(0,0,0,0.5);display:none;';
    pop.innerHTML = '<div style="font-weight:600;margin-bottom:8px;">通用设置</div>'
      + '<div style="margin-bottom:6px;">背景图片</div>'
      + '<input id="dsh-bg-file" type="file" accept="image/*" style="width:100%;margin-bottom:6px;color:#eee;">'
      + '<div id="dsh-bg-preview" style="margin-bottom:6px;"></div>'
      + '<button id="dsh-bg-clear" style="margin-bottom:10px;background:#3a3a3a;color:#eee;border:1px solid #555;border-radius:6px;padding:4px 8px;cursor:pointer;">移除背景</button>'
      + '<div style="margin-bottom:4px;">浮层透明度：<span id="dsh-op-val">15%</span></div>'
      + '<input id="dsh-op-range" type="range" min="0" max="60" value="15" style="width:100%;">'
      + '<div style="margin-top:8px;opacity:0.6;font-size:11px;">设置保存在本页，刷新后保留。</div>';
    document.body.appendChild(fab);
    document.body.appendChild(pop);
    fab.addEventListener('click', function () {
      pop.style.display = pop.style.display === 'none' ? 'block' : 'none';
      syncPanel();
    });
    pop.querySelector('#dsh-bg-file').addEventListener('change', function (e) {
      var file = e.target.files[0];
      if (!file) return;
      var r = new FileReader();
      r.onload = function () { localStorage.setItem(KEY_BG, r.result); applyOverlay(); syncPanel(); };
      r.readAsDataURL(file);
    });
    pop.querySelector('#dsh-bg-clear').addEventListener('click', function () {
      localStorage.setItem(KEY_BG, '');
      applyOverlay();
      syncPanel();
    });
    var range = pop.querySelector('#dsh-op-range');
    range.addEventListener('input', function () {
      localStorage.setItem(KEY_OP, range.value);
      applyOverlay();
      syncPanel();
    });
  }

  function syncPanel() {
    var pop = document.getElementById('dsh-settings-panel');
    if (!pop) return;
    var bg = localStorage.getItem(KEY_BG) || '';
    var op = parseFloat(localStorage.getItem(KEY_OP));
    if (isNaN(op)) op = 15;
    pop.querySelector('#dsh-op-range').value = op;
    pop.querySelector('#dsh-op-val').textContent = op + '%';
    var prev = pop.querySelector('#dsh-bg-preview');
    prev.innerHTML = bg
      ? '<img src="' + bg + '" style="max-width:100%;max-height:80px;border-radius:6px;">'
      : '<span style="opacity:0.5;">未设置</span>';
  }

  function ensure() {
    // 仅首次（localStorage 从没写过）用 IntelliJ 旧设置做种子；之后以网页端为准
    if (localStorage.getItem(KEY_SEED) === null) {
      localStorage.setItem(KEY_BG, window.__dshSeed && window.__dshSeed.bg ? window.__dshSeed.bg : '');
      localStorage.setItem(KEY_OP, String(window.__dshSeed && window.__dshSeed.opacity != null ? window.__dshSeed.opacity : 15));
      localStorage.setItem(KEY_SEED, '1');
    }
    applyOverlay();
    buildPanel();
    syncPanel();
  }

  if (document.readyState !== 'loading') { ensure(); }
  else { document.addEventListener('DOMContentLoaded', ensure); }
  setTimeout(ensure, 1500);
  setInterval(ensure, 2000);
})();
