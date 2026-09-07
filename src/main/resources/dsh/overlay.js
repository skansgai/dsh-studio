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

  // —— 关于区块：插件版本 + dsh 版本 + 检查更新 ——
  // dsh 0.1.2-rc.1 的设置对话框只有「通用设置 / 模型 / 插件 / 插件列表」，没有原生的「关于」面板，
  // 所以这里克隆一个已有的导航条目造一个出来，并配一个独立内容页。
  // 克隆（而不是自己拼 class）是为了让样式与原生条目一致，也不依赖 dsh 内部的面板注册机制
  // —— 那是服务端插件才有的权限，注入脚本拿不到。
  var ABOUT_NAV_TITLES = ['通用设置', '模型', '插件列表'];
  var ABOUT_NAV = 'about-nav';
  var ABOUT_PANEL = 'about-panel';
  var aboutState = { nav: null, item: null, panel: null, content: null, dimmed: null, active: false };

  // 跨 dsh 重绘缓存：dsh 会周期性重绘设置对话框并冲掉克隆的关于卡片，
  // 重建的卡片默认显示「查询中…」。把插件端回传的版本/更新结果缓存下来，
  // 一旦卡片被重建就从缓存里回填，避免版本号「过一会儿就消失」。
  var aboutVersionInfo = null;
  var aboutUpdateInfo = null;

  function applyVersionToCards(card) {
    if (!card) return;
    var dv = card.querySelector('[data-dsh="dv"]');
    if (dv && aboutVersionInfo) {
      dv.textContent = aboutVersionInfo.dshLatest
        ? (aboutVersionInfo.dshLatest + '（npm 最新版）')
        : '（无法获取）';
    }
    var pv = card.querySelector('[data-dsh="pv"]');
    if (pv && aboutVersionInfo && aboutVersionInfo.pluginVersion) {
      pv.textContent = aboutVersionInfo.pluginVersion;
    }
    var box = card.querySelector('[data-dsh="result"]');
    if (box && aboutUpdateInfo) applyUpdateToBox(box, aboutUpdateInfo);
  }

  function leavesWithExactText(root, texts) {
    var all = root.querySelectorAll('*');
    var out = [];
    for (var i = 0; i < all.length; i++) {
      if (all[i].children.length !== 0) continue;
      if (texts.indexOf((all[i].textContent || '').trim()) !== -1) out.push(all[i]);
    }
    return out;
  }

  function ancestorChain(node) {
    var a = [], e = node;
    while (e) { a.unshift(e); e = e.parentElement; }
    return a;
  }

  /** 设置对话框的左侧导航容器：几个导航标题叶子节点"最深的共同祖先"。 */
  function findNavContainer(dialog) {
    var leaves = leavesWithExactText(dialog, ABOUT_NAV_TITLES);
    if (leaves.length < 2) return null;
    // 同一标题只取第一个：最靠前的通常是导航项，而不是当前面板里同名的标题
    var seen = {}, picked = [];
    for (var i = 0; i < leaves.length; i++) {
      var t = (leaves[i].textContent || '').trim();
      if (seen[t]) continue;
      seen[t] = 1;
      picked.push(leaves[i]);
    }
    if (picked.length < 2) return null;
    var chain = ancestorChain(picked[0]);
    var best = null;
    for (var c = 0; c < chain.length; c++) {
      var el = chain[c];
      if (el === document.body || el === document.documentElement) continue;
      var holds = true;
      for (var k = 1; k < picked.length; k++) {
        if (!el.contains(picked[k])) { holds = false; break; }
      }
      if (holds) best = el; // 循环跑完时保留的是最深的那个
    }
    // 孩子太多说明判错了层级（比如命中了整个对话框），宁可放弃也别把内容塞错地方
    return (best && best.children.length >= 2 && best.children.length <= 12) ? best : null;
  }

  /** 内容区：导航容器的同级里最宽的那个兄弟（dsh 是"左导航 + 右内容"两栏布局）。 */
  function findContentArea(nav) {
    var el = nav;
    for (var up = 0; up < 5 && el; up++) {
      var parent = el.parentElement;
      if (!parent) break;
      var best = null, bestW = 0;
      for (var k = 0; k < parent.children.length; k++) {
        var sib = parent.children[k];
        if (sib === el || sib.getAttribute('data-dshstudio') === ABOUT_PANEL) continue;
        var w = sib.offsetWidth || 0;
        if (w > bestW) { bestW = w; best = sib; }
      }
      if (best && bestW >= 150) return best;
      el = parent;
    }
    return null;
  }

  function closestWithAttr(node, attr, value) {
    var e = node;
    while (e && e.nodeType === 1) {
      if (e.getAttribute && e.getAttribute(attr) === value) return e;
      e = e.parentElement;
    }
    return null;
  }

  function activeNativeNavItem(nav) {
    if (!nav) return null;
    var c = nav.querySelectorAll('[aria-selected="true"],[data-active="true"],[aria-current="true"]');
    for (var i = 0; i < c.length; i++) {
      if (c[i].getAttribute('data-dshstudio') !== ABOUT_NAV) return c[i];
    }
    return null;
  }

  // 当关于处于激活态时，把原生高亮项压暗、把关于项标为高亮，做出"互斥"的视觉效果。
  function markAboutActive(on) {
    var s = aboutState;
    if (!s.item) return;
    if (on) {
      s.item.setAttribute('aria-selected', 'true');
      s.item.setAttribute('data-active', 'true');
      var src = activeNativeNavItem(s.nav);
      if (src && src !== s.item) {
        var cs = getComputedStyle(src);
        s.item.style.setProperty('background', cs.backgroundColor, 'important');
        s.item.style.setProperty('color', cs.color, 'important');
        s.item.style.setProperty('font-weight', cs.fontWeight, 'important');
        // !important 顶住 dsh 重绘对样式的内联覆盖
        src.style.setProperty('opacity', '0.5', 'important');
        s.dimmed = src;
      }
    } else {
      s.item.removeAttribute('aria-selected');
      s.item.removeAttribute('data-active');
      s.item.style.removeProperty('background');
      s.item.style.removeProperty('color');
      s.item.style.removeProperty('font-weight');
    }
    if (!on && s.dimmed) {
      s.dimmed.style.removeProperty('opacity');
      s.dimmed = null;
    }
  }

  // 按 s.active 强制应用可见性。用 !important 顶住 dsh 重绘时对 display 的内联覆盖，
  // 这样"关于"页和其它菜单之间才真正互斥、且不闪退。
  function applyAbout() {
    var s = aboutState;
    if (!s.item || !s.panel || !s.content) return;
    if (s.active) {
      s.content.style.setProperty('display', 'none', 'important');
      s.panel.style.setProperty('display', 'block', 'important');
      markAboutActive(true);
    } else {
      s.content.style.removeProperty('display');
      s.panel.style.setProperty('display', 'none', 'important');
      markAboutActive(false);
    }
  }

  /** 克隆一个原生导航条目，把标题换成「关于」。 */
  function buildAboutNavItem(nav, srcItem, titleText) {
    var item = srcItem.cloneNode(true);
    item.setAttribute('data-dshstudio', ABOUT_NAV);
    item.removeAttribute('aria-selected');
    item.removeAttribute('data-active');
    item.removeAttribute('aria-current');
    var inner = item.querySelectorAll('*');
    for (var i = 0; i < inner.length; i++) {
      if (inner[i].children.length === 0 && (inner[i].textContent || '').trim() === titleText) {
        inner[i].textContent = '关于';
        break;
      }
    }
    nav.appendChild(item);
    return item;
  }

  function onAboutNavClick(e) {
    // 只处理真实用户点击：dsh 自身的标签切换有时会派发合成 click（isTrusted===false），
    // 若对它响应会把"关于"页误关掉，表现为"闪一下就消失"。
    if (e.isTrusted === false) return;
    if (closestWithAttr(e.target || e.srcElement, 'data-dshstudio', ABOUT_NAV)) {
      aboutState.active = true;
    } else if (aboutState.active) {
      aboutState.active = false;
    } else {
      return;
    }
    applyAbout();
  }

  function bindAboutNav() {
    var s = aboutState;
    if (!s.nav || s.nav.getAttribute('data-dshstudio-nav-bound') === '1') return;
    s.nav.setAttribute('data-dshstudio-nav-bound', '1');
    s.nav.addEventListener('click', onAboutNavClick, true);
  }

  function ensureAbout() {
    var dialog = document.querySelector('[role="dialog"]');
    if (!dialog) dialog = findSettingsDialog();
    if (!dialog) { aboutState.active = false; return false; }

    var s = aboutState;
    var navItem = dialog.querySelector('[data-dshstudio="' + ABOUT_NAV + '"]');
    var panel = dialog.querySelector('[data-dshstudio="' + ABOUT_PANEL + '"]');
    if (navItem && panel && s.content && s.content.isConnected) {
      s.item = navItem;
      s.panel = panel;
      var oldCard = panel.querySelector('[data-dshstudio="about"]');
      if (oldCard) updateAboutTheme(oldCard);
      bindAboutNav();
      applyAbout();
      return true;
    }

    var nav = findNavContainer(dialog);
    if (!nav) return false;
    var content = findContentArea(nav);
    if (!content || !content.parentElement) return false;

    // 找一个原生导航条目作为克隆模板
    var srcItem = null, titleText = '';
    var titles = leavesWithExactText(nav, ABOUT_NAV_TITLES);
    for (var i = 0; i < titles.length && !srcItem; i++) {
      for (var k = 0; k < nav.children.length; k++) {
        if (nav.children[k].contains(titles[i])) {
          srcItem = nav.children[k];
          titleText = (titles[i].textContent || '').trim();
          break;
        }
      }
    }
    if (!srcItem) return false;

    if (panel && panel.parentNode) panel.parentNode.removeChild(panel);
    panel = document.createElement('div');
    panel.setAttribute('data-dshstudio', ABOUT_PANEL);
    panel.style.cssText = 'display:none;flex:1 1 auto;min-width:0;overflow:auto;';
    content.parentElement.insertBefore(panel, content.nextSibling);

    if (navItem && navItem.parentNode) navItem.parentNode.removeChild(navItem);

    s.nav = nav;
    s.content = content;
    s.panel = panel;
    s.dimmed = null;
    s.item = buildAboutNavItem(nav, srcItem, titleText);

    if (!panel.querySelector('[data-dshstudio="about"]')) buildAboutCard(panel);
    bindAboutNav();
    applyAbout();
    return true;
  }

  // 设置对话框是 SPA 后渲染出来的，且每次切换面板 dsh 都可能重绘导航把克隆节点冲掉，
  // 所以挂一个防抖的 MutationObserver 持续补挂。
  var aboutObserverStarted = false;
  function startAboutObserver() {
    if (aboutObserverStarted || typeof MutationObserver === 'undefined') return;
    aboutObserverStarted = true;
    var pending = false;
    new MutationObserver(function () {
      if (pending) return;
      pending = true;
      setTimeout(function () {
        pending = false;
        try { ensureAbout(); } catch (e) { /* 页面结构变了就算了，不影响主功能 */ }
      }, 120);
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  function buildAboutCard(anchor) {
    var c = themeColors();
    var card = document.createElement('div');
    card.setAttribute('data-dshstudio', 'about');
    card.style.cssText = 'margin:18px 0;padding:14px 16px;border:1px solid ' + c.border + ';border-radius:10px;'
      + 'background:' + c.bg + ';color:' + c.fg + ';font-family:inherit;font-size:13px;';
    var pv = (window.__dshStudioInfo && window.__dshStudioInfo.pluginVersion) || '…';
    card.innerHTML =
      '<div style="font-weight:600;margin-bottom:4px;">版本 · DSH Studio 增强</div>'
      + '<div style="opacity:0.75;margin-bottom:12px;font-size:12px;">插件与 DeepSeek Harness 的版本信息，以及一键检查更新。</div>'
      + '<div style="display:flex;align-items:center;gap:10px;margin-bottom:8px;"><span style="min-width:130px;opacity:0.85;">插件版本</span><b data-dsh="pv">' + esc(pv) + '</b></div>'
      + '<div style="display:flex;align-items:center;gap:10px;margin-bottom:12px;"><span style="min-width:130px;opacity:0.85;">DeepSeek Harness (dsh)</span><b data-dsh="dv">查询中…</b></div>'
      + '<button data-dsh="check" style="background:' + c.btn + ';color:' + c.btnFg + ';border:1px solid ' + c.border + ';border-radius:6px;padding:4px 12px;cursor:pointer;">检查更新</button>'
      + '<div data-dsh="result" style="margin-top:10px;font-size:12px;line-height:1.6;"></div>';
    anchor.appendChild(card);

    card.querySelector('[data-dsh="check"]').addEventListener('click', function () {
      var box = card.querySelector('[data-dsh="result"]');
      if (box) { box.textContent = '检查中…'; box.style.color = ''; }
      try { console.log(SYNC_PREFIX + JSON.stringify({ cmd: 'checkUpdate' })); } catch (e) { /* 忽略 */ }
    });

    // 请求 dsh 最新版本（插件版本已由注入种子给出）
    try { console.log(SYNC_PREFIX + JSON.stringify({ cmd: 'version' })); } catch (e) { /* 忽略 */ }

    // 若此前已回传过版本/更新结果，立即从缓存回填，避免重建后显示「查询中…」又消失。
    applyVersionToCards(card);
  }

  function updateAboutTheme(card) {
    var c = themeColors();
    card.style.background = c.bg;
    card.style.color = c.fg;
    card.style.borderColor = c.border;
    var btn = card.querySelector('[data-dsh="check"]');
    if (btn) { btn.style.background = c.btn; btn.style.color = c.btnFg; btn.style.borderColor = c.border; }
  }

  function applyUpdateToBox(box, res) {
    if (!box || !res) return;
    if (res.hasPluginUpdate) {
      box.style.color = '#e8a33d';
      box.innerHTML = '插件有新版 <b>v' + esc(res.pluginLatest) + '</b>（当前 v' + esc(res.installed)
        + '）。前往 IDE 的 <b>Settings → Plugins → Marketplace</b> 搜索 “DeepSeek Harness” 更新。';
    } else {
      box.style.color = '';
      var dsh = res.dshLatest ? (' DeepSeek Harness 最新 v' + esc(res.dshLatest) + '（npx 下次启动自动使用）。') : '';
      box.innerHTML = '插件已是最新（v' + esc(res.installed) + '）。' + dsh;
    }
  }

  function applyAllAboutCards(fn) {
    var cards = document.querySelectorAll('[data-dshstudio="about"]');
    for (var i = 0; i < cards.length; i++) fn(cards[i]);
  }

  // 插件端回传：版本信息（cmd=version 的响应）。
  // 缓存 + 应用到所有卡片：dsh 重绘会重建卡片，重建后必须能从缓存回填，否则版本号会「消失」。
  window.__dshStudioVersion = function (info) {
    if (!info) return;
    aboutVersionInfo = info;
    applyAllAboutCards(function (card) { applyVersionToCards(card); });
  };

  // 插件端回传：检查更新结果（cmd=checkUpdate 的响应）。同样缓存 + 应用到所有卡片。
  window.__dshStudioUpdate = function (res) {
    if (!res) return;
    aboutUpdateInfo = res;
    applyAllAboutCards(function (card) {
      var box = card.querySelector('[data-dsh="result"]');
      applyUpdateToBox(box, res);
    });
  };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (ch) {
      return ch === '&' ? '&amp;' : ch === '<' ? '&lt;' : ch === '>' ? '&gt;' : '&quot;';
    });
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
    try { ensureAbout(); } catch (e) { /* 设置对话框结构变了就算了，不影响背景图等主功能 */ }
    styleTopBar();
  }

  if (document.readyState !== 'loading') { ensure(); }
  else { document.addEventListener('DOMContentLoaded', ensure, { once: true }); }
  // SPA 首屏渲染可能晚于脚本注入，补几拍
  setTimeout(ensure, 300);
  setTimeout(ensure, 1000);
  setTimeout(ensure, 2500);
  startAboutObserver();
  // 避免重复注入时定时器叠加：用单一句柄
  if (window.__dshInterval) clearInterval(window.__dshInterval);
  window.__dshInterval = setInterval(ensure, 2000);

  // 仅供自动化测试：window.__dshTestMode 为 true 时暴露 About 状态切换钩子，便于绕过
  // jsdom 无法把 isTrusted 伪造为 true 的限制，直接驱动状态并断言可见性逻辑。生产环境无此全局，不受影响。
  if (window.__dshTestMode) {
    window.__dshAbout = {
      activate: function () { aboutState.active = true; applyAbout(); },
      deactivate: function () { aboutState.active = false; applyAbout(); },
      isActive: function () { return aboutState.active; }
    };
  }
})();
