/* ==========================================================================
   Claude Design System — PokeClaw mockup interaction layer
   --------------------------------------------------------------------------
   Vanilla port of the reference project's React hooks:
     useClaudeScrollPinning.tsx  →  scroll pinning
     ChatComposer.tsx            →  auto-grow + CJK IME guard
     ModelSwitcherWithTokenGauge →  model popover

   Design values come from cds.css (MIT, © Caho1). Behaviour here is written
   from scratch — no build step, no dependencies.
   ========================================================================== */
(function () {
  'use strict';

  /* ---------------------------------------------------------------- 主题 */
  var root = document.documentElement;
  var THEME_KEY = 'cds-mockup-theme';
  var ACCENT_KEY = 'cds-mockup-accent';
  var BASE_KEY = 'cds-mockup-base';

  /* localStorage 在 file://（不透明来源）下会抛错。原先全部静默 catch，
     导致「能切主题但存不住」看起来像渲染 bug，排查方向完全跑偏。
     失败必须出声 —— 在 WebView console 里能直接看到。 */
  function store(key, value) {
    try { localStorage.setItem(key, value); return true; }
    catch (e) { console.warn('[cds] localStorage.setItem(' + key + ') failed:', e); return false; }
  }

  function read(key) {
    try { return localStorage.getItem(key); }
    catch (e) { console.warn('[cds] localStorage.getItem(' + key + ') failed:', e); return null; }
  }

  /* 桥：有 Kotlin 桥时（真机），KVUtils 是主题与任务设置的唯一事实源；
     纯浏览器打开时没有桥，localStorage 兜底（设计稿预览照常工作）。
     写路径两边都写：桥让原生页与 composer  pill 立即一致，localStorage
     让无桥预览和 QA 探针仍有据可查。 */
  function bridge() {
    return (typeof PokeClawChat !== 'undefined' && PokeClawChat) ? PokeClawChat : null;
  }

  function persistPrefs() {
    var b = bridge();
    if (!b || !b.writeThemePrefs) return;
    try {
      b.writeThemePrefs(JSON.stringify({
        mode: root.getAttribute('data-mode'),
        base: root.getAttribute('data-base'),
        accent: root.getAttribute('data-theme')
      }));
    } catch (e) { console.warn('[cds] writeThemePrefs failed:', e); }
  }

  /* 读偏好：桥优先（原生主题页 / 设置页改过的话这里读得到），
     localStorage 回退，最后才轮到系统深浅色。 */
  function readPrefs() {
    var b = bridge();
    if (b && b.readThemePrefs) {
      try {
        var raw = b.readThemePrefs();
        if (raw) {
          var p = JSON.parse(raw);
          if (p && (p.mode || p.base || p.accent)) return p;
        }
      } catch (e) { console.warn('[cds] readThemePrefs failed:', e); }
    }
    return {
      mode: read(THEME_KEY) ||
        (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'),
      base: read(BASE_KEY) || 'clay',
      accent: read(ACCENT_KEY) || 'clay'
    };
  }

  function syncModeSeg(mode) {
    var seg = document.getElementById('themeModeSeg');
    if (!seg) return;
    seg.querySelectorAll('.seg-opt').forEach(function (opt) {
      opt.setAttribute('aria-checked', String(opt.dataset.modeKey === mode));
    });
  }

  function applyTheme(mode) {
    root.setAttribute('data-mode', mode);
    store(THEME_KEY, mode);
    var btn = document.getElementById('themeToggle');
    if (btn) btn.setAttribute('aria-label', mode === 'dark' ? '切换到浅色' : '切换到深色');
    syncModeSeg(mode);
    persistPrefs();
  }

  function initTheme() {
    applyTheme(readPrefs().mode || 'light');
  }

  /* 原生页（主题页 / 设置页）改过偏好后，由 Kotlin 在 onResume 调用，
     把页面拉到与 KVUtils 一致。只动变了的维度，避免无谓重排。 */
  function applyPrefs(prefs) {
    if (!prefs) return;
    if (prefs.mode && prefs.mode !== root.getAttribute('data-mode')) applyTheme(prefs.mode);
    if (prefs.base && prefs.base !== root.getAttribute('data-base')) applyBase(prefs.base);
    if (prefs.accent && prefs.accent !== root.getAttribute('data-theme')) applyAccent(prefs.accent);
  }

  /* ------------------------------------------------- 配色方案（强调色）
     与 [data-mode] 正交：换配色不动深浅，换深浅不动配色。 */
  function applyAccent(key) {
    // 写入前把关：无效的 key 不进 localStorage。否则存下去的是一个
    // 永远不会被应用的垃圾值，下次读回还要再判一次无效。
    if (!validAccent(key)) key = 'clay';
    root.setAttribute('data-theme', key);
    store(ACCENT_KEY, key);
    var rows = document.querySelectorAll('.swatch-row');
    rows.forEach(function (row) {
      row.setAttribute('aria-checked', String(row.dataset.themeKey === key));
    });
    syncThemeLabel();
    persistPrefs();
  }

  /* ------------------------------------------------- 底色方案
     第三维，与强调色、深浅三者互不影响。 */
  function applyBase(key) {
    if (!validBase(key)) key = 'clay';
    root.setAttribute('data-base', key);
    store(BASE_KEY, key);
    var rows = document.querySelectorAll('.base-row');
    rows.forEach(function (row) {
      row.setAttribute('aria-checked', String(row.dataset.baseKey === key));
    });
    syncThemeLabel();
    persistPrefs();
  }

  /* ------------------------------------------------- 存量值校验
     两种失效，症状完全一样（页面静默回落成 clay），所以分开处理：

       A. 方案本身被删了  → querySelector 找不到行，走老用户兼容
       B. 方案还在、但 CSS 变量被删了 → 行还在，querySelector 照样通过，
          页面回落成默认色，看上去像「存不住」

     B 是真正咬人的那个：只查 DOM 会放它过去。所以两关都要过 ——
     存进 localStorage 之前问「有没有对应规则」，读回时再问一次。
     规则缺失不静默放过，直接 console.warn 并回落，让 logcat 有话说。 */
  function hasKey(selector, attr, saved) {
    return !!saved && !!document.querySelector(selector + '[' + attr + '="' + saved + '"]');
  }

  // CSS 规则真的存在吗：把属性打到 <html> 上，读回解析后的变量值。
  // 只查 DOM 查不出「行还在、变量没了」，只有读计算值能查出来。
  function cssResolves(attr, key, probeVar) {
    var prev = root.getAttribute(attr);
    root.setAttribute(attr, key);
    // 强制重算，否则读到的是上一次的缓存值
    void root.offsetWidth;
    var v = getComputedStyle(root).getPropertyValue(probeVar).trim();
    if (prev === null) root.removeAttribute(attr); else root.setAttribute(attr, prev);
    void root.offsetWidth;
    return v.length > 0;
  }

  function validAccent(saved) {
    if (!hasKey('.swatch-row', 'data-theme-key', saved)) return false;
    if (cssResolves('data-theme', saved, '--theme-accent')) return true;
    console.warn('[cds] 配色 ' + saved + ' 有色块但没有 [data-theme] 规则，已回落默认');
    return false;
  }

  function validBase(saved) {
    if (!hasKey('.base-row', 'data-base-key', saved)) return false;
    // 探针变量必须是 cds.css 里真实存在的名字。写错名字不会「校验失败」，
    // 而是对所有 key 都返回空串 —— 全部误判为无效，整套底色直接瘫掉。
    if (cssResolves('data-base', saved, '--surface-0')) return true;
    console.warn('[cds] 底色 ' + saved + ' 有色块但没有 [data-base] 规则，已回落默认');
    return false;
  }

  function syncThemeLabel() {
    var b = document.querySelector('.base-row[aria-checked="true"] .swatch-name');
    var a = document.querySelector('.swatch-row[aria-checked="true"] .swatch-name');
    var inline = document.getElementById('themeNameInline');
    if (inline && b && a) inline.textContent = b.textContent + ' ' + a.textContent;
  }

  function initAccent() {
    var saved = readPrefs().accent;
    applyAccent(validAccent(saved) ? saved : 'clay');
  }

  function initBase() {
    var saved = readPrefs().base;
    applyBase(validBase(saved) ? saved : 'clay');
  }

  /* -------------------------------------------------------- Composer 行为 */
  var ta = document.getElementById('composerInput');
  var sendBtn = document.getElementById('sendBtn');

  // 输入法合成守卫：CJK 输入过程中 Enter 是「选词」，不是「发送」。
  // 必须同时看 isComposing 和 keyCode 229 —— 部分 IMEs 合成结束才派发 keyup，
  // 只判断 isComposing 会漏掉一部分。
  var composing = false;

  function syncAutoGrow() {
    ta.style.height = 'auto';
    ta.style.height = Math.min(ta.scrollHeight, 220) + 'px';
    sendBtn.disabled = ta.value.trim().length === 0;
  }

  ta.addEventListener('input', syncAutoGrow);
  ta.addEventListener('compositionstart', function () { composing = true; });
  ta.addEventListener('compositionend', function () { composing = false; syncAutoGrow(); });

  ta.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    if (e.shiftKey) return;
    if (composing || e.isComposing || e.keyCode === 229) return; // 合成中 → 不发送
    e.preventDefault();
    submit();
  });

  sendBtn.addEventListener('click', submit);

  // 有 Kotlin 桥 → 真实任务流；没有（纯浏览器打开）→ 本地模拟。
  // 用 typeof 判存在性而不是布尔标志：桥是 addJavascriptInterface 注入的
  // 对象，标志位可能在桥注入前就被读到。
  function hasNativeBridge() {
    return typeof PokeClawChat !== 'undefined' && PokeClawChat && PokeClawChat.send;
  }

  /* ------------------------------------------------- 真实模式下的收敛
     设计稿里有一批**纯静态**的东西：会话列表、搜索框、「给妈妈订高铁票」
     这个顶栏标题、重命名与「更多」按钮、记忆库与技能管理入口。接上真实任务
     流之后它们仍然是死的 —— 用户点不动，也点不出结果。

     两种做法：删掉，或者藏起来。这里选藏：这份 HTML 同时也是**纯浏览器里的
     设计稿预览**，删了就看不到完整设计了。用一个标记属性让 CSS 决定显示，
     纯浏览器打开时标记不存在，一切照旧。 */
  function enterLiveMode() {
    document.documentElement.setAttribute('data-live', 'true');
    // 标题改成中性文案：写死「给妈妈订高铁票」会让真实使用显得像被冒充。
    var title = document.querySelector('.topbar-title');
    if (title) title.textContent = '新任务';
    // 静态示例 turn（工具卡 / MCP 卡 / 记忆 chips）只属于纯浏览器里的设计稿。
    // 三类示例现在都有真实数据源（工具卡 H23、MCP 卡 H27、chips 2026-10-24），
    // 真实界面里再摆一段虚构的「给妈妈订高铁票」对话就不是示例而是冒充了。
    // 首次打开（还没有历史可恢复）时不清空的话，用户会以为任务已经跑过。
    feed.textContent = '';
  }

  function submit() {
    var text = ta.value.trim();
    if (!text) return;
    ta.value = '';
    syncAutoGrow();
    if (hasNativeBridge()) {
      PokeClawChat.send(text);
    } else {
      appendUser(text);
      runAssistant(text);
    }
  }

  /* ------------------------------------------------------- 滚动底部钉住
     抄 useClaudeScrollPinning.ts。四个机制，缺一不可：
       1. 40px 底部容差
       2. 150ms 用户输入冷却窗口 —— 用户刚滚过就别抢视口
       3. programmaticScroll 守卫 —— 程序滚动触发的事件不能再当作用户滚动
       4. 离开底部 >120px 出现「跳至最新」 */
  var feed = document.getElementById('feed');
  var jumpBtn = document.getElementById('jumpLatest');
  var pinned = true;
  var showJump = false;
  var lastUserInputAt = 0;
  var programmaticScroll = false;
  var PIN_TOLERANCE = 40;
  var JUMP_THRESHOLD = 120;
  var COOLDOWN_MS = 150;

  // 用户主动滚动（滚轮 / 触摸 / 键盘）立刻释放锁定。
  // 不只监听 scroll —— scroll 事件滞后一帧，用户快速滚动时会误判。
  ['wheel', 'touchstart', 'keydown'].forEach(function (evt) {
    feed.addEventListener(evt, function () {
      lastUserInputAt = performance.now();
      pinned = false;
    }, { passive: true });
  });

  feed.addEventListener('scroll', function () {
    if (programmaticScroll) { programmaticScroll = false; return; }
    var slack = feed.scrollHeight - feed.scrollTop - feed.clientHeight;
    pinned = slack <= PIN_TOLERANCE;
    showJump = slack > JUMP_THRESHOLD;
    syncJump();
  }, { passive: true });

  function syncJump() {
    if (!jumpBtn) return;
    jumpBtn.setAttribute('data-show', showJump ? 'true' : 'false');
  }

  function toBottom(smooth) {
    programmaticScroll = true;
    feed.scrollTo({ top: feed.scrollHeight, behavior: smooth ? 'smooth' : 'auto' });
    pinned = true;
    showJump = false;
    syncJump();
  }

  if (jumpBtn) {
    jumpBtn.addEventListener('click', function () {
      lastUserInputAt = 0;          // 主动点击 = 放弃冷却，立即贴底
      toBottom(true);
    });
  }

  // token 流式追加时的重锚定：只有「没被用户接管」才自动跟随
  function repin() {
    if (performance.now() - lastUserInputAt <= COOLDOWN_MS) return;
    if (pinned) toBottom(false);
  }

  /* ---------------------------------------------------- 限流退避倒计时
     抄 useRateLimitBackoff.ts：
       delay = min(max(base,5000) * 2^(n-1) * jitter, 600000)，jitter ±15%
       连续 3 次自动重试后停止自动重试，强制用户手动点
     演示时把 base 压到 3s，否则没人愿意等 10 分钟。 */
  var backoff = { waiting: false, at: 0, left: 0, runs: 0, manualOnly: false, timer: null };

  function renderBackoff() {
    var banner = document.getElementById('rateLimit');
    if (!banner) return;
    if (!backoff.waiting) { banner.setAttribute('data-show', 'false'); return; }
    banner.setAttribute('data-show', 'true');
    banner.querySelector('.rl-text').textContent = backoff.manualOnly
      ? '已连续退避 ' + backoff.runs + ' 次，需要你手动重试。'
      : '模型限流，' + backoff.left + ' 秒后重试（第 ' + backoff.runs + ' 次）';
    var retry = banner.querySelector('.rl-retry');
    if (retry) retry.style.display = backoff.manualOnly ? '' : 'none';
  }

  function tickBackoff() {
    backoff.left = Math.max(0, Math.ceil((backoff.at - Date.now()) / 1000));
    if (backoff.left <= 0) {
      clearInterval(backoff.timer);
      backoff.timer = null;
      backoff.waiting = false;
      renderBackoff();
      appendUser('（手动重试）');
      runAssistant('retry');
      return;
    }
    renderBackoff();
  }

  function triggerBackoff() {
    backoff.runs += 1;
    if (backoff.runs > 3) {                       // 第 4 次：不再自动重试
      backoff.waiting = true; backoff.manualOnly = true; backoff.left = 0;
      renderBackoff();
      return;
    }
    var base = 3000;                              // 演示用，生产是 5000
    var jitter = 0.85 + Math.random() * 0.3;
    var delay = Math.min(Math.max(base, 5000) * Math.pow(2, backoff.runs - 1) * jitter, 600000);
    backoff.waiting = true; backoff.manualOnly = false;
    backoff.at = Date.now() + delay;
    backoff.left = Math.ceil(delay / 1000);
    clearInterval(backoff.timer);
    backoff.timer = setInterval(tickBackoff, 250);
    renderBackoff();
  }

  var rlBanner = document.getElementById('rateLimit');
  if (rlBanner) {
    rlBanner.querySelector('.rl-close').addEventListener('click', function () {
      clearInterval(backoff.timer);
      backoff.timer = null;
      backoff.waiting = false; backoff.manualOnly = false; backoff.runs = 0;
      renderBackoff();
    });
    rlBanner.querySelector('.rl-retry').addEventListener('click', function () {
      clearInterval(backoff.timer);
      backoff.timer = null;
      backoff.waiting = false; backoff.manualOnly = false;
      backoff.runs = 0;
      renderBackoff();
      appendUser('（手动重试）');
      runAssistant('retry');
    });
  }

  /* ------------------------------------------------------------- 漫游焦点
     抄 useClaudeRovingFocus.ts：方向键在组内移动，Home/End 首尾，
     Tab 立即交还全局焦点流（防键盘死循环），tabIndex 动态 0 / -1。 */
  function rovingFocus(container, itemSelector) {
    var items = Array.prototype.slice.call(container.querySelectorAll(itemSelector));
    if (!items.length) return;
    var cur = 0;

    items.forEach(function (el, i) {
      el.tabIndex = i === 0 ? 0 : -1;
      el.addEventListener('click', function () { cur = i; apply(); });
    });

    function apply() {
      items.forEach(function (el, i) { el.tabIndex = i === cur ? 0 : -1; });
      items[cur].focus({ preventScroll: true });
    }

    container.addEventListener('keydown', function (e) {
      if (e.key === 'Tab') return;               // 关键：不拦截 Tab
      var i = items.indexOf(document.activeElement);
      if (i < 0) return;
      var next = -1;
      switch (e.key) {
        case 'ArrowDown': case 'ArrowRight': next = i + 1; break;
        case 'ArrowUp':   case 'ArrowLeft':  next = i - 1; break;
        case 'Home': case 'PageUp':            next = 0; break;
        case 'End':  case 'PageDown':          next = items.length - 1; break;
        default: return;
      }
      e.preventDefault();
      cur = (next + items.length) % items.length; // loop 回绕
      apply();
    });
  }

  rovingFocus(document.querySelector('.sessions'), '.session');
  rovingFocus(document.querySelector('.composer-bar'), '.pill');

  /* ---------------------------------------------------- popover 互斥管理器
     四个 popover（模型 / 努力档位 / 敏感操作 / 主题）共用一套开关逻辑：
     同一时刻只开一个，点外部或 Esc 全关。原来是各写一遍且第二个
     （主题）出现后两边还会互相打架。 */
  var popovers = [];

  /* 弹出方向实测：默认向上弹（bottom: calc(100% + 6px)）。触发器上方
     放不下面板高度时翻转向下（data-flip="down"，见 cds.css）。
     手机抽屉里主题行被 live 模式顶到靠近顶部——会话列表整段是死控件、
     已隐藏，脚部因此贴在品牌头下面——向上弹 520px 高出视口一截，
     「深浅 / 底色」整段裁在屏幕外。CSS 无从得知上方还剩多少空间，
     所以开合时量一次，resize 再量一次。
     水平同样量一次：右缘出视口（pill 靠近屏幕右边）时贴回包含块右缘，
     否则 item 的 tag 会被裁掉，文档还会白白获得横向滚动。 */
  function placePopover(entry) {
    var pop = entry.pop;
    pop.removeAttribute('data-flip');
    pop.style.left = '';
    pop.style.right = '';
    var roomAbove = entry.btn.getBoundingClientRect().top;
    if (roomAbove < pop.offsetHeight + 8) {
      pop.setAttribute('data-flip', 'down');
    }
    if (pop.getBoundingClientRect().right > window.innerWidth - 8) {
      pop.style.left = 'auto';
      pop.style.right = '0';
    }
  }

  function registerPopover(wrapId, popId, btnId) {
    var wrap = document.getElementById(wrapId);
    var pop = document.getElementById(popId);
    var btn = document.getElementById(btnId);
    if (!wrap || !pop || !btn) return null;
    var entry = { wrap: wrap, pop: pop, btn: btn };
    popovers.push(entry);
    btn.addEventListener('click', function (e) {
      e.stopPropagation();
      var willOpen = pop.getAttribute('data-open') !== 'true';
      closeAllPopovers();
      if (willOpen) {
        pop.setAttribute('data-open', 'true');
        btn.setAttribute('aria-expanded', 'true');
        // 先display:block再量高度；量完才决定翻不翻转。
        placePopover(entry);
      }
    });
    return entry;
  }

  function closeAllPopovers() {
    popovers.forEach(function (entry) {
      entry.pop.setAttribute('data-open', 'false');
      entry.btn.setAttribute('aria-expanded', 'false');
    });
  }

  document.addEventListener('click', function (e) {
    var inside = popovers.some(function (entry) {
      return entry.wrap.contains(e.target);
    });
    if (!inside) closeAllPopovers();
  });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closeAllPopovers();
  });

  // 开着的面板在视口变化时重新定向（分屏 / 转屏 / 键盘顶出 dock）。
  window.addEventListener('resize', function () {
    popovers.forEach(function (entry) {
      if (entry.pop.getAttribute('data-open') === 'true') placePopover(entry);
    });
  });

  // 模型切换器
  var modelPopEntry = registerPopover('modelWrap', 'modelPop', 'modelBtn');
  // 模型行点击：换模型 + 更新 pill 标签（原逻辑，迁到管理器里）
  var modelPop = modelPopEntry ? modelPopEntry.pop : null;
  var modelBtn = modelPopEntry ? modelPopEntry.btn : null;
  if (modelPop && modelBtn) {
    modelPop.addEventListener('click', function (e) {
      var item = e.target.closest('.pop-item');
      if (!item) return;
      modelPop.querySelectorAll('.pop-item').forEach(function (b) {
        b.setAttribute('aria-checked', String(b === item));
        b.style.background = b === item ? 'var(--cds-fill-ghost-selected)' : '';
      });
      var label = modelBtn.querySelector('.pill-label');
      if (label) label.textContent = item.dataset.label;
      closeAllPopovers();
    });
  }

  // 主题面板（侧边栏）：底色 × 强调色 × 深浅，一个面板三个维度
  registerPopover('themePopWrap', 'themePop', 'themePickerBtn');
  var themePop = document.getElementById('themePop');
  if (themePop) {
    themePop.addEventListener('click', function (e) {
      var row = e.target.closest('.swatch-row');
      if (row) { applyAccent(row.dataset.themeKey); return; }
      var base = e.target.closest('.base-row');
      if (base) applyBase(base.dataset.baseKey);
    });
    var modeSeg = document.getElementById('themeModeSeg');
    if (modeSeg) {
      modeSeg.addEventListener('click', function (e) {
        var opt = e.target.closest('.seg-opt');
        if (!opt) return;
        applyTheme(opt.dataset.modeKey);
      });
    }
  }

  /* --------------------------------------------- 努力档位 / 敏感操作两个档位
     原来是无 onClick 的死 pill（「高努力」「允许操作」）。现在各是一个
     换档器：写 localStorage 兜底，并经桥写 KVUtils —— AgentConfig 的
     maxIterations 与 MCP 确认门都读那份 KVUtils，档位因此是真实行为。 */
  var EFFORT_KEY = 'cds-mockup-effort';
  var EFFORT_LABELS = { low: '低', medium: '中', high: '高', max: '最大' };

  function applyEffort(key) {
    if (!EFFORT_LABELS[key]) key = 'high';
    store(EFFORT_KEY, key);
    var pop = document.getElementById('effortPop');
    if (pop) {
      pop.querySelectorAll('.pop-item').forEach(function (item) {
        item.setAttribute('aria-checked', String(item.dataset.effortKey === key));
      });
    }
    var label = document.getElementById('effortLabel');
    if (label) label.textContent = EFFORT_LABELS[key];
    var b = bridge();
    if (b && b.setEffort) {
      try { b.setEffort(key); }
      catch (e) { console.warn('[cds] setEffort failed:', e); }
    }
  }

  var POLICY_KEY = 'cds-mockup-policy';
  var POLICY_LABELS = { ask: '询问', auto: '自动' };

  function applyPolicy(key) {
    if (!POLICY_LABELS[key]) key = 'ask';
    store(POLICY_KEY, key);
    var pop = document.getElementById('policyPop');
    if (pop) {
      pop.querySelectorAll('.pop-item').forEach(function (item) {
        item.setAttribute('aria-checked', String(item.dataset.policyKey === key));
      });
    }
    var label = document.getElementById('policyLabel');
    if (label) label.textContent = POLICY_LABELS[key];
    var b = bridge();
    if (b && b.setAutoAllow) {
      try { b.setAutoAllow(key === 'auto'); }
      catch (e) { console.warn('[cds] setAutoAllow failed:', e); }
    }
  }

  function initTaskGears() {
    var b = bridge();
    var effort = null;
    var policy = null;
    if (b && b.readTaskPrefs) {
      try {
        var p = JSON.parse(b.readTaskPrefs() || '{}');
        effort = p.effort;
        policy = p.autoAllow ? 'auto' : 'ask';
      } catch (e) { console.warn('[cds] readTaskPrefs failed:', e); }
    }
    applyEffort(effort || read(EFFORT_KEY) || 'high');
    applyPolicy(policy || read(POLICY_KEY) || 'ask');
  }

  var effortEntry = registerPopover('effortWrap', 'effortPop', 'effortBtn');
  if (effortEntry) {
    effortEntry.pop.addEventListener('click', function (e) {
      var item = e.target.closest('.pop-item');
      if (!item) return;
      applyEffort(item.dataset.effortKey);
      closeAllPopovers();
    });
  }

  var policyEntry = registerPopover('policyWrap', 'policyPop', 'policyBtn');
  if (policyEntry) {
    policyEntry.pop.addEventListener('click', function (e) {
      var item = e.target.closest('.pop-item');
      if (!item) return;
      applyPolicy(item.dataset.policyKey);
      closeAllPopovers();
    });
  }

  /* ------------------------------------------------------------ 消息渲染 */
  function turn(cls, inner) {
    var t = document.createElement('div');
    t.className = 'turn ' + cls;
    t.innerHTML = inner;
    feed.appendChild(t);
    repin();
    return t;
  }

  function esc(s) {
    return s.replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function appendUser(text) {
    var t = turn('turn-user', '<div class="bubble"></div>');
    t.querySelector('.bubble').textContent = text;
  }

  /* -------------------------------------------- 真实消息流（Kotlin → 页面）
     TaskFlowController 经 WebChatSink 把消息推过来，这里负责按 role 画出对应的
     turn。全部走 createElement + textContent，不用 innerHTML 拼消息正文 ——
     助手内容是模型生成的，拼字符串就是注入面。

     整表重建而不是增量 diff：消息量以「一轮任务」计（个位数到几十），重建成本
     远低于维护一套 key 对齐的 diff；而增量一旦漏掉一次就会错位，且错位之后
     没有任何自愈路径。滚动钉住由 repin() 负责，重建不丢滚动位置。 */
  function renderLive(messages) {
    feed.textContent = '';
    (messages || []).forEach(function (m) { feed.appendChild(buildTurn(m)); });
    toBottom(false);
  }

  // Kotlin 侧已经把 JSON 字面量拼进调用点，所以这里拿到的是已解析的数组，
  // 不再 parse 一次。两条入口共用 renderLive 的实现。
  function renderLiveRaw(messages) { renderLive(messages); }

  function buildTurn(m) {
    if (m.isTypingIndicator) {
      return turn('turn-assistant',
        '<div class="status-row"><span class="timeline-quiet-dots">' +
        '<span></span><span></span><span></span></span>' +
        '<span class="status-text">正在思考…</span></div>');
    }
    if (m.role === 'USER') {
      var t = turn('turn-user', '<div class="bubble"></div>');
      t.querySelector('.bubble').textContent = m.content;
      return t;
    }

    // 一个工具步骤若带 memories（memory_recall 的结构化结果，Kotlin 侧
    // MemoryRecallFormat 从同一段 summary 文本解析而来）就画成 .chips，
    // 否则画 .tool-card。解析失败时 memories 为空 —— 那时工具卡就是回退，
    // 完整文本还在卡里，宁缺勿错。
    var parts = [];
    if (m.content) {
      parts.push('<div class="body"><p></p></div>');
    }
    (m.toolSteps || []).forEach(function (step) {
      if (step.memories && step.memories.length) {
        parts.push('<div class="chips"></div>');
      } else {
        parts.push('<div class="tool-card"><div class="tool-head">' +
          '<span class="status-dot" data-kind="ready"></span>' +
          '<span class="tool-name"></span>' +
          '<span class="tool-kind">工具调用</span></div>' +
          '<div class="tool-body"></div></div>');
      }
    });
    var node = turn('turn-assistant', parts.join(''));

    if (m.content) {
      node.querySelector('.body p').textContent = m.content;
    }
    var chipBoxes = node.querySelectorAll('.chips');
    var cards = node.querySelectorAll('.tool-card');
    var chipIndex = 0;
    var cardIndex = 0;
    (m.toolSteps || []).forEach(function (step) {
      if (step.memories && step.memories.length) {
        var box = chipBoxes[chipIndex++];
        if (!box) return;
        step.memories.forEach(function (c) {
          var chip = document.createElement('span');
          chip.className = 'chip';
          var idx = document.createElement('span');
          idx.className = 'chip-idx';
          idx.textContent = '#' + c.id;
          chip.appendChild(idx);
          chip.appendChild(document.createTextNode(c.content));
          box.appendChild(chip);
        });
        return;
      }
      var card = cards[cardIndex++];
      if (!card) return;
      card.querySelector('.tool-name').textContent = step.name;
      card.querySelector('.tool-body').textContent = step.summary;
      card.querySelector('.status-dot').setAttribute('data-kind', step.success ? 'ready' : 'error');
    });
    return node;
  }

  /* ---------------------------------------------- MCP 确认卡（真实数据）
     静态样例那几张卡（index.html 里的 .mcp-card）是给纯浏览器看的设计稿；
     这一张是活的：Kotlin 侧 ToolRegistry 在一个 mcp_* 工具要执行前把请求推
     过来，用户点一个按钮，PokeClawChat.decideMcp 把决定送回 Kotlin，
     阻塞在 executeTool 里的那个线程才继续。

     四个细节都是有原因的：
       1. 卡片**不放进消息列表**。它由 renderConfirmation 单独画在 feed 末尾，
          因为 renderLiveRaw 会先清空 feed 再重建——同一批 evaluateJavascript
          里先画卡再重建，卡就没了。Kotlin 侧也据此分两次调用。
       2. 点击后先本地禁用三个按钮，再上交决定。等待期间的重复点击会把两个
          决定送给同一次等待，而第二次会被 coordinator 当作过期点击丢掉——
          禁用让"点了没反应"和"没点上"在画面上可区分。
       3. 无桥（纯浏览器打开）时按钮点了只禁用自己：那是设计稿，没有人在
          等这个决定，不能假装有回路。
       4. 破坏性档（risk=DESTRUCTIVE，mcp_* 一律如此）**没有「始终允许」**：
          gate 会把始终允许静默降级成允许一次，卡面给一个不会被兑现的按钮
          就是卡面在说谎（QA H62）。倒计时只转述 coordinator 的时限，
          权威在 Kotlin——到点卡片会被收走。                          */
  function renderConfirmation(req) {
    removeConfirmation();
    if (!req) return;
    var destructive = req.risk === 'DESTRUCTIVE';
    var card = turn('turn-assistant',
      '<div class="mcp-card" id="mcpConfirm">' +
        '<div class="mcp-head">' +
          '<span class="status-dot" data-kind="clay"></span>' +
          '<span>需要你确认</span>' +
          '<span class="mcp-tool"></span>' +
          (destructive ? '<span class="mcp-risk">破坏性</span>' : '') +
        '</div>' +
        '<div class="mcp-desc"></div>' +
        '<div class="mcp-params"></div>' +
        (destructive ? '<div class="mcp-consequence"></div>' : '') +
        '<div class="mcp-actions">' +
          '<button class="pill cds-btn-squish" type="button" data-decide="allow_once">允许一次</button>' +
          (destructive ? '' : '<button class="pill cds-btn-squish" type="button" data-decide="allow_always">始终允许</button>') +
          '<button class="pill cds-btn-squish" type="button" data-decide="deny">本次拒绝</button>' +
        '</div>' +
        '<div class="mcp-timeout"></div>' +
      '</div>');
    card.querySelector('.mcp-tool').textContent = req.displayName + ' · ' + req.serverName;
    card.querySelector('.mcp-desc').textContent = req.description || '';
    var params = card.querySelector('.mcp-params');
    if (req.paramsPreview) {
      params.textContent = req.paramsPreview;
    } else {
      params.parentNode.removeChild(params);
    }
    if (destructive) {
      card.querySelector('.mcp-consequence').textContent =
        '破坏性操作不可撤销，只能允许这一次——拒绝后模型会换别的方式。';
    }
    startConfirmationCountdown(req.timeoutMs);
    card.addEventListener('click', function (e) {
      var btn = e.target && e.target.closest ? e.target.closest('button[data-decide]') : null;
      if (!btn) return;
      var decision = btn.getAttribute('data-decide');
      var buttons = card.querySelectorAll('button[data-decide]');
      for (var i = 0; i < buttons.length; i++) { buttons[i].disabled = true; }
      stopConfirmationCountdown();
      if (window.PokeClawChat && window.PokeClawChat.decideMcp) {
        window.PokeClawChat.decideMcp(decision);
      }
    });
    toBottom(true);
    return card;
  }

  /* 确认卡倒计时：1s 一刷，只转述 Kotlin 给的 timeoutMs。数到 0 不是
     裁决——裁决在 coordinator 超时那一刻发生，这里只是让用户在知道
     时限的情况下做决定。 */
  var confirmationTimer = null;

  function startConfirmationCountdown(timeoutMs) {
    stopConfirmationCountdown();
    var box = document.getElementById('mcpConfirm');
    var node = box && box.querySelector('.mcp-timeout');
    if (!node) return;
    var left = Math.max(1, Math.round((timeoutMs || 60000) / 1000));
    var tick = function () {
      if (left <= 0) {
        node.textContent = '已超时，按拒绝处理';
        stopConfirmationCountdown();
        return;
      }
      node.textContent = left + ' 秒后未应答将按拒绝处理';
      left -= 1;
    };
    tick();
    confirmationTimer = setInterval(tick, 1000);
  }

  function stopConfirmationCountdown() {
    if (confirmationTimer) { clearInterval(confirmationTimer); confirmationTimer = null; }
  }

  function removeConfirmation() {
    stopConfirmationCountdown();
    var existing = document.getElementById('mcpConfirm');
    if (existing && existing.parentNode) {
      existing.parentNode.removeChild(existing);
    }
  }

  // 演示用的模拟回合，保留给纯浏览器预览；接入后走 renderLive。
  function runAssistant(prompt) {
    var status = turn('turn-assistant',
      '<div class="status-row"><span class="timeline-quiet-dots">' +
      '<span></span><span></span><span></span></span><span class="status-text">正在思考…</span></div>');

    setTimeout(function () {
      status.remove();
      turn('turn-assistant',
        '<div class="body"><p>好的，我来处理。</p></div>');

      var card = turn('turn-assistant',
        '<div class="tool-card">' +
        '<div class="tool-head">' +
        '<span class="status-dot" data-kind="ready"></span>' +
        '<span class="tool-name">accessibility.perform_action</span>' +
        '<span class="tool-kind">无障碍 · 点击</span>' +
        '</div>' +
        '<div class="tool-body">NODE_ID=btn_send · ACTION=click</div>' +
        '</div>');

      setTimeout(function () {
        card.style.opacity = '0.42';
        turn('turn-assistant',
          '<div class="body"><p>已经点了发送。截图如下：</p></div>' +
          '<div class="code">' +
          '<div class="code-head">screenshot.png</div>' +
          '<pre>' + esc('$ adb exec-out screencap -p > shot.png\nbytes: 284,112 · device: vivo X300 Pro') + '</pre>' +
          '</div>');
        repin();
      }, 420);
    }, 900);
  }

  /* --------------------------------------------- 配色方案 popover 交互
     已并入上方的 popover 互斥管理器（registerPopover('themePopWrap', …)），
     这里不再重复挂监听。 */

  /* --------------------------------------------- 会话抽屉（≤760px）
     手机宽度侧栏是覆盖抽屉（见 cds.css 断点段）：汉堡开，遮罩 / Esc 关。
     桌面宽度下汉堡不存在（.menu-btn display:none），这套监听全部空转。 */
  var menuBtn = document.getElementById('menuBtn');
  var navScrim = document.getElementById('navScrim');

  function closeNav() { document.body.classList.remove('nav-open'); }

  if (menuBtn) {
    menuBtn.addEventListener('click', function () {
      document.body.classList.add('nav-open');
      maybeShowCoachMark();
    });
  }
  if (navScrim) {
    navScrim.addEventListener('click', closeNav);
  }
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closeNav();
  });

  /* --------------------------------------------- 新功能引导（一次性）
     定时队列的 teaching bubble：出现到用户点「去看看」或自己点进定时行
     为止，之后不再来。标志经 PokeClawChat 存 SharedPreferences——
     localStorage 会被清（WebView 存储清理、数据清除），一次性引导不能
     存进一个会被清掉的地方。无桥（纯浏览器预览）时不显示：设计稿没有
     「第一次使用」这回事。 */
  var schedulesCoach = document.getElementById('schedulesCoach');
  var schedulesCoachBtn = document.getElementById('schedulesCoachBtn');

  function coachMarkSeen() {
    var b = bridge();
    if (!b || !b.readCoachMarkShown) return true;   // 无桥：不当第一次
    try { return !!b.readCoachMarkShown(); }
    catch (e) { console.warn('[cds] readCoachMarkShown failed:', e); return true; }
  }

  function dismissCoachMark() {
    if (!schedulesCoach) return;
    schedulesCoach.setAttribute('data-show', 'false');
    var b = bridge();
    if (b && b.markCoachMarkShown) {
      try { b.markCoachMarkShown(); }
      catch (e) { console.warn('[cds] markCoachMarkShown failed:', e); }
    }
  }

  function maybeShowCoachMark() {
    if (!schedulesCoach) return;
    if (schedulesCoach.getAttribute('data-show') === 'true') return;
    if (coachMarkSeen()) return;
    schedulesCoach.setAttribute('data-show', 'true');
  }

  if (schedulesCoachBtn) {
    schedulesCoachBtn.addEventListener('click', function () {
      dismissCoachMark();
      // 「去看看」不是纯关闭：把用户直接带去新功能，引导要落到地上。
      openSchedulesView();
    });
  }

  /* --------------------------------------------- 设置面板
     Claude 的设置形态：从侧栏唤起的弹层 sheet。行点击经
     PokeClawChat.openSettingsPage(section) 打开对应原生编辑页；
     纯浏览器里这是设计稿的一部分，行静默不动。开合经
     notifySettingsView 通知 Kotlin，Android BACK 键据此先关面板。 */
  var settingsView = document.getElementById('settingsView');
  var settingsScrim = document.getElementById('settingsScrim');

  function openSettingsView() {
    if (!settingsView) return;
    // 三个全屏 sheet 互斥：任务页/定时页开着时从侧栏摸到设置入口是可能的
    // （侧栏在 sheet 之下但抽屉断点里侧栏浮在上面），不互斥就会叠层。
    closeTasksView();
    closeSchedulesView();
    settingsView.setAttribute('data-open', 'true');
    if (settingsScrim) settingsScrim.setAttribute('data-open', 'true');
    if (window.PokeClawChat && window.PokeClawChat.notifySettingsView) {
      window.PokeClawChat.notifySettingsView(true);
    }
  }

  function closeSettingsView() {
    if (!settingsView) return;
    settingsView.setAttribute('data-open', 'false');
    if (settingsScrim) settingsScrim.setAttribute('data-open', 'false');
    if (window.PokeClawChat && window.PokeClawChat.notifySettingsView) {
      window.PokeClawChat.notifySettingsView(false);
    }
  }

  function settingsViewIsOpen() {
    return !!settingsView && settingsView.getAttribute('data-open') === 'true';
  }

  if (settingsScrim) {
    settingsScrim.addEventListener('click', closeSettingsView);
  }
  var settingsCloseBtn = document.getElementById('settingsCloseBtn');
  if (settingsCloseBtn) {
    settingsCloseBtn.addEventListener('click', closeSettingsView);
  }
  if (settingsView) {
    settingsView.addEventListener('click', function (e) {
      var row = e.target.closest('.settings-row');
      if (!row) return;
      var section = row.getAttribute('data-section');
      if (!section) return;
      if (window.PokeClawChat && window.PokeClawChat.openSettingsPage) {
        window.PokeClawChat.openSettingsPage(section);
      }
    });
  }

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') closeSettingsView();
  });

  /* --------------------------------------------- 侧栏脚部行
     抽屉里的「设置」打开页内设置面板 —— 面板按分区列出各原生编辑页入口。
     主题（深浅 × 底色 × 强调色）是侧边栏的常驻 quick access，与设置面板
     「外观 · 主题」行打开的是同一个原生主题页。 */
  var settingsBtn = document.getElementById('settingsBtn');
  if (settingsBtn) {
    settingsBtn.addEventListener('click', function () {
      closeNav();
      openSettingsView();
    });
  }

  /* ------------------------------------------------------------ 任务账本
     只读任务页。两级：列表（task-card）与详情（步骤账本），都在 #tasksBody
     里整表重建 —— 与 renderLive 同一个取舍：一个账本按「几十条」计，重建
     比维护 key 对齐的 diff 便宜，且 diff 漏一次就永久错位、没有自愈路径。

     数据两条来路，界限划在「有没有桥」上：
       · 有桥（真机）：PokeClawChat.readTaskList() / readTaskDetail(id)，
         账本（TaskRecordStore）是唯一事实源。桥返回空数组 = 空账本，
         显示空态 —— 宁空不假，绝不拿样例冒充真实数据。
       · 无桥（纯浏览器预览）：TASK_SAMPLES 设计样例，与 feed 里的静态
         示例 turn 同性质，只给评审看。

     推送入口 __cds.renderTasks / renderTaskDetail 留给 Kotlin 侧
     （TaskSink 落地后由它调）；本页只读，没有任何写操作按钮 ——
     确认与操作留在聊天线程里，M2 的卡才进这里。 */
  var tasksView = document.getElementById('tasksView');
  var tasksScrim = document.getElementById('tasksScrim');
  var tasksBody = document.getElementById('tasksBody');
  var tasksTitle = document.getElementById('tasksTitle');
  var tasksBackBtn = document.getElementById('tasksBackBtn');
  var tasksBadge = document.getElementById('tasksBadge');

  /* glyph 是终态标的字形（✓/✕）——扫读时比辨色快，且不依赖颜色感知
     （Setproduct：纯色点对读屏不可见，文字标同时是它的可读形态）。
     行里只有一个信号族：带表面色描边的状态点 + 无底色彩字。 */
  var TASK_STATES = {
    RUNNING:         { label: '运行中', dot: 'running', glyph: '' },
    WAITING_CONFIRM: { label: '待确认', dot: 'clay', glyph: '' },
    SUCCEEDED:       { label: '已完成', dot: 'ready', glyph: '✓' },
    FAILED:          { label: '失败',   dot: 'error', glyph: '✕' },
    CANCELLED:       { label: '已取消', dot: 'muted', glyph: '' }
  };

  function taskState(key) {
    return TASK_STATES[key] || { label: key || '未知状态', dot: 'muted' };
  }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  /* 时间格式：同年 MM-DD HH:MM，跨年补年份。账本混排多年时
     「10-25」单独一个会张冠李戴。 */
  function fmtClock(ts) {
    var d = new Date(ts);
    var now = new Date();
    var md = pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) + ' ' +
      pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    return d.getFullYear() === now.getFullYear()
      ? md
      : d.getFullYear() + '-' + md;
  }

  /* 耗时只说事实：起止时间差。没有百分比、没有预估 —— 任务步数
     不可预知，任何进度条都是编的。 */
  function fmtElapsed(ms) {
    var s = Math.max(0, Math.floor(ms / 1000));
    if (s < 60) return s + ' 秒';
    var m = Math.floor(s / 60);
    if (m < 60) return m + ' 分钟';
    return Math.floor(m / 60) + ' 小时 ' + (m % 60) + ' 分';
  }

  /* 设计样例（仅无桥预览）。相对时刻算时间，评审时「已耗时 4 分钟」
     永远读得通；故事线与 feed 里的静态示例同一桩（12306 订票）。 */
  function minutesAgo(n) { return Date.now() - n * 60000; }

  var TASK_SAMPLES = [
    {
      id: 'sample-running',
      title: '给妈妈订高铁票',
      state: 'RUNNING',
      channel: '本机',
      origin: 'Claude UI 聊天',
      prompt: '帮我在 12306 买一张后天从杭州东到上海虹桥的票，二等座，优先上午出发。',
      createdAt: minutesAgo(4),
      finishedAt: null,
      steps: [
        { name: 'memory_recall', summary: '召回 3 条：妈妈出行优先上午车次 / 常买杭州东→上海虹桥 / 身份证已存', success: true, at: minutesAgo(4) },
        { name: 'accessibility.open_app', summary: 'PACKAGE=com.MobileTicket · ACTION=LAUNCH', success: true, at: minutesAgo(4) + 12000 },
        { name: 'accessibility.read_tree', summary: '128 节点 · 定位到「车票查询」入口', success: true, at: minutesAgo(3) },
        { name: 'mcp_12306_search', summary: '后天 3 趟上午车次：G7307 07:12 / G7303 08:03 / G1201 09:15', success: true, at: minutesAgo(2) },
        { name: 'accessibility.perform_action', summary: 'NODE_ID=btn_confirm · ACTION=click', success: true, at: minutesAgo(1) }
      ]
    },
    {
      id: 'sample-waiting',
      title: '微信里回复张工',
      state: 'WAITING_CONFIRM',
      channel: 'Telegram',
      origin: 'ExternalAutomation',
      prompt: '回复张工：验收报告已发他邮箱，下周一现场演示。',
      createdAt: minutesAgo(26),
      finishedAt: null,
      steps: [
        { name: 'accessibility.open_app', summary: 'PACKAGE=com.tencent.mm · ACTION=LAUNCH', success: true, at: minutesAgo(26) },
        { name: 'mcp_wechat_send', summary: '等待确认：是否允许以本人身份发送这条消息？', success: true, at: minutesAgo(25) }
      ]
    },
    {
      id: 'sample-failed',
      title: '清理相册重复照片',
      state: 'FAILED',
      channel: '微信',
      origin: 'WeChatChannel',
      prompt: '把相册里重复的照片清一下，留最清楚的那张。',
      createdAt: minutesAgo(95),
      finishedAt: minutesAgo(88),
      error: 'accessibility.perform_action 失败：当前界面里找不到删除按钮——相册在系统弹窗确认后重画了，节点 id 全部失效。',
      steps: [
        { name: 'accessibility.open_app', summary: 'PACKAGE=com.android.gallery3d · ACTION=LAUNCH', success: true, at: minutesAgo(95) },
        { name: 'accessibility.read_tree', summary: '412 节点 · 按拍摄时间分组完成', success: true, at: minutesAgo(93) },
        { name: 'accessibility.perform_action', summary: 'error: node btn_delete not found in current tree（相册在系统弹窗后重画了）', success: false, at: minutesAgo(88) }
      ]
    },
    {
      id: 'sample-done',
      title: '导出记忆库并分享',
      state: 'SUCCEEDED',
      channel: '本机',
      origin: 'Claude UI 聊天',
      prompt: '把记忆库导出成文件，发到我的微信。',
      createdAt: minutesAgo(180),
      finishedAt: minutesAgo(176),
      answer: '已导出 128 条记忆（46 KB），发送到微信·文件传输助手。',
      steps: [
        { name: 'memory_export', summary: '128 条记忆 → memories.json（46 KB）', success: true, at: minutesAgo(180) },
        { name: 'send_file', summary: '已发送至 微信 · 文件传输助手', success: true, at: minutesAgo(176) }
      ]
    }
  ];

  var TASKS_EMPTY_ICON =
    '<svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
    'stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2"/>' +
    '<rect x="9" y="3" width="6" height="4" rx="1"/><path d="M9 12h6M9 16h6"/></svg>';

  function buildEmptyState(title, desc) {
    var box = el('div', 'tasks-empty');
    box.innerHTML = TASKS_EMPTY_ICON +
      '<div class="tasks-empty-title"></div><p class="tasks-empty-desc"></p>';
    box.querySelector('.tasks-empty-title').textContent = title;
    box.querySelector('.tasks-empty-desc').textContent = desc;
    return box;
  }

  /* 详情里的终态载荷块：标签（✓/✕ + 小字）+ 深色 console 正文。
     文字一律 textContent——失败原因与最终答案都来自任务执行过程，
     拼 innerHTML 就是注入面。 */
  function buildDetailBlock(label, text, className, glyph) {
    var box = el('div', className);
    box.innerHTML =
      '<div class="task-detail-block-label">' +
        '<span class="task-detail-block-glyph"></span>' +
        '<span class="task-detail-block-name"></span>' +
      '</div>' +
      '<div class="task-detail-block-text"></div>';
    box.querySelector('.task-detail-block-glyph').textContent = glyph || '';
    box.querySelector('.task-detail-block-name').textContent = label;
    box.querySelector('.task-detail-block-text').textContent = text;
    return box;
  }

  /* ------------------------------------------------------------ 桥读取
     null = 没有桥（预览模式，由调用方决定用样例）；[] = 有桥、空账本。
     这两种「没有数据」含义相反，不能混成一个返回值。 */
  function readTasksFromBridge() {
    var b = bridge();
    if (!b || !b.readTaskList) return null;
    try {
      var raw = b.readTaskList();
      if (!raw) return [];
      var arr = JSON.parse(raw);
      return Array.isArray(arr) ? arr : [];
    } catch (e) {
      console.warn('[cds] readTaskList failed:', e);
      return [];   // 桥在但读坏了：显示空态，不拿样例冒充
    }
  }

  function readTaskDetailFromBridge(id) {
    var b = bridge();
    if (!b || !b.readTaskDetail) return null;
    try {
      var task = JSON.parse(b.readTaskDetail(id) || 'null');
      return task && typeof task === 'object' ? task : null;
    } catch (e) {
      console.warn('[cds] readTaskDetail failed:', e);
      return null;
    }
  }

  /* ------------------------------------------------------------ 徽标
     只数真实数据里的待处理任务（运行中 + 待确认）。关着的页面不亮 ——
     徽标是状态不是装饰；预览样例照亮，那是设计的一部分。 */
  function updateTasksBadge(tasks) {
    if (!tasksBadge) return;
    var pending = 0;
    (tasks || []).forEach(function (t) {
      if (t.state === 'RUNNING' || t.state === 'WAITING_CONFIRM') pending += 1;
    });
    tasksBadge.textContent = String(pending);
    tasksBadge.setAttribute('data-show', pending > 0 ? 'true' : 'false');
  }

  /* ------------------------------------------------------------ 列表级 */
  /* 过滤三段：全部 / 进行中 / 已结束。客户端过滤——桥的载荷不变，过滤
     只是「看哪一部分」的选择，不是新的数据面（ChatGPT Scheduled 的
     All/Active/Paused 是同一思路）。lastTasksPayload 记住未过滤的
     全集，切段时按它重画，徽标也永远数全集——徽章是状态不是装饰。 */
  var tasksFilter = 'all';
  var lastTasksPayload = [];

  var TASKS_FILTERS = [
    { key: 'all', label: '全部' },
    { key: 'active', label: '进行中' },
    { key: 'done', label: '已结束' }
  ];

  function taskMatchesFilter(t) {
    if (tasksFilter === 'active') {
      return t.state === 'RUNNING' || t.state === 'WAITING_CONFIRM';
    }
    if (tasksFilter === 'done') {
      return t.state !== 'RUNNING' && t.state !== 'WAITING_CONFIRM';
    }
    return true;
  }

  function buildTasksFilter() {
    var row = el('div', 'tasks-filter');
    TASKS_FILTERS.forEach(function (f) {
      var btn = el('button', 'tasks-filter-opt', f.label);
      btn.type = 'button';
      btn.setAttribute('data-filter', f.key);
      btn.setAttribute('aria-pressed', tasksFilter === f.key ? 'true' : 'false');
      btn.addEventListener('click', function () {
        if (tasksFilter === f.key) return;
        tasksFilter = f.key;
        renderTaskList(lastTasksPayload);
      });
      row.appendChild(btn);
    });
    return row;
  }

  function buildTaskCard(t) {
    var st = taskState(t.state);
    var card = el('button', 'task-card');
    card.type = 'button';
    card.setAttribute('data-task-id', String(t.id == null ? '' : t.id));
    card.innerHTML =
      '<div class="task-card-head">' +
        '<span class="status-dot"></span>' +
        '<span class="task-card-title"></span>' +
        '<span class="task-state"></span>' +
      '</div>' +
      '<div class="task-card-meta"></div>' +
      '<div class="task-card-last"></div>';
    card.querySelector('.status-dot').setAttribute('data-kind', st.dot);
    card.querySelector('.task-card-title').textContent = t.title || '（无标题任务）';
    var pill = card.querySelector('.task-state');
    pill.setAttribute('data-state', String(t.state || ''));
    pill.textContent = st.glyph ? st.glyph + ' ' + st.label : st.label;

    var meta = card.querySelector('.task-card-meta');
    if (t.channel) {
      meta.appendChild(el('span', 'task-channel', t.channel));
      meta.appendChild(document.createTextNode(' · '));
    }
    var stepCount = t.stepCount != null
      ? t.stepCount
      : (t.steps ? t.steps.length : 0);
    meta.appendChild(document.createTextNode(stepCount + ' 步'));
    meta.appendChild(document.createTextNode(' · '));
    var elapsed = el('span', 'task-elapsed');
    if (t.state === 'RUNNING') {
      var since = t.createdAt || Date.now();
      elapsed.textContent = '已耗时 ' + fmtElapsed(Date.now() - since);
      elapsed.setAttribute('data-since', String(since));   // ticker 按它续算
    } else if (t.finishedAt) {
      elapsed.textContent = fmtClock(t.finishedAt) + ' 完成';
    } else {
      elapsed.textContent = '发起于 ' + fmtClock(t.createdAt || Date.now());
    }
    meta.appendChild(elapsed);

    var last = card.querySelector('.task-card-last');
    var lastStep = t.lastStep || (t.steps && t.steps.length ? t.steps[t.steps.length - 1] : null);
    if (t.error) {
      // 失败原因优先于最近一步：卡片要回答「为什么没成」，而那才是答案。
      // （Failed 事件不产步骤，原因只存在于 error 字段——见 TaskLedgerBridge。）
      last.textContent = t.error;
      last.setAttribute('data-kind', 'error');
    } else if (t.answer) {
      last.textContent = t.answer;
    } else if (lastStep) {
      last.textContent = (lastStep.name || '') + (lastStep.summary ? ' · ' + lastStep.summary : '');
    } else {
      last.parentNode.removeChild(last);   // 没有最近一步就不留空行
    }
    return card;
  }

  function renderTaskList(tasks) {
    lastTasksPayload = tasks || [];
    tasksBody.textContent = '';
    updateTasksBadge(tasks);
    if (!tasks.length) {
      tasksBody.appendChild(buildEmptyState(
        '还没有任务记录',
        '在聊天页发起一个任务，它的每一步都会记在这里——跑了多久、调用了哪些工具、结果如何，任务结束后也能回看。'
      ));
      return;
    }
    tasksBody.appendChild(buildTasksFilter());
    var shown = tasks.filter(taskMatchesFilter);
    if (!shown.length) {
      // 有全集但当前段为空：说清楚是筛选问题，不是数据没了。
      tasksBody.appendChild(buildEmptyState(
        '没有符合当前筛选的任务',
        '换个筛选条件，或回到「全部」看看。'
      ));
      return;
    }
    var frag = document.createDocumentFragment();
    shown.forEach(function (t) { frag.appendChild(buildTaskCard(t)); });
    tasksBody.appendChild(frag);
  }

  /* ------------------------------------------------------------ 详情级 */
  function buildLedgerStep(s, idx) {
    var li = el('li', 'ledger-step');
    li.setAttribute('data-ok', s.success === false ? 'false' : 'true');
    li.innerHTML =
      '<div class="ledger-idx"></div>' +
      '<div class="ledger-main">' +
        '<div class="ledger-head">' +
          '<span class="ledger-name"></span>' +
          '<span class="ledger-at"></span>' +
        '</div>' +
        '<div class="ledger-summary"></div>' +
      '</div>';
    li.querySelector('.ledger-idx').textContent = String(idx);
    li.querySelector('.ledger-name').textContent = s.name || '（未命名步骤）';
    li.querySelector('.ledger-at').textContent = s.at ? fmtClock(s.at) : '';
    var summary = li.querySelector('.ledger-summary');
    summary.textContent = s.summary || '';
    if (!s.summary) summary.parentNode.removeChild(summary);
    return li;
  }

  function renderTaskDetail(task) {
    tasksBody.textContent = '';
    openTaskId = task.id;
    var st = taskState(task.state);

    var wrap = el('div', 'task-detail');
    wrap.innerHTML =
      '<div class="task-detail-title"></div>' +
      '<div class="task-detail-meta"></div>';
    wrap.querySelector('.task-detail-title').textContent = task.title || '任务详情';

    var meta = wrap.querySelector('.task-detail-meta');
    var metaBits = [st.label];
    if (task.channel) metaBits.push('来源 ' + task.channel);
    if (task.origin) metaBits.push(task.origin);
    metaBits.forEach(function (bit, i) {
      if (i > 0) meta.appendChild(document.createTextNode(' · '));
      meta.appendChild(document.createTextNode(bit));
    });
    if (task.state === 'RUNNING') {
      // 运行中：meta 末项挂可续算的耗时，ticker 每 30s 刷
      meta.appendChild(document.createTextNode(' · '));
      var since = task.createdAt || Date.now();
      var span = el('span', 'task-elapsed', '已耗时 ' + fmtElapsed(Date.now() - since));
      span.setAttribute('data-since', String(since));
      meta.appendChild(span);
    } else {
      meta.appendChild(document.createTextNode(' · 发起于 ' + fmtClock(task.createdAt || Date.now())));
      if (task.finishedAt) {
        meta.appendChild(document.createTextNode(' · 完成于 ' + fmtClock(task.finishedAt)));
        // 参考实现的终态输出区带时长（mono tabular-nums）。同样只报起止
        // 差——耗时是说事实，不是估进度。
        meta.appendChild(document.createTextNode(
          ' · 耗时 ' + fmtElapsed(task.finishedAt - (task.createdAt || task.finishedAt))
        ));
      }
    }

    if (task.prompt) {
      var prompt = el('div', 'task-detail-prompt');
      prompt.textContent = task.prompt;
      wrap.appendChild(prompt);
    }

    // 终态载荷两个块：失败原因（✕ 危险染）与最终答案（✓ 中性）。形态抄
    // open-claude-ui 的终态输出面。Failed 事件在账本里不产步骤，没有这个
    // 块，失败任务的详情就只剩一串正常步骤、看不到它是怎么死的——设备上
    // 看不见失败原因正是这个页面要补的旧账。
    if (task.error) {
      wrap.appendChild(buildDetailBlock('失败原因', task.error, 'task-detail-error', '✕'));
    }
    if (task.answer) {
      wrap.appendChild(buildDetailBlock('结果', task.answer, 'task-detail-answer', '✓'));
    }

    var steps = task.steps || [];
    wrap.appendChild(el('div', 'group-label', '步骤账本 · ' + steps.length + ' 步'));
    if (steps.length) {
      var ol = el('ol', 'ledger');
      steps.forEach(function (s, i) { ol.appendChild(buildLedgerStep(s, i + 1)); });
      wrap.appendChild(ol);
    } else {
      wrap.appendChild(buildEmptyState('这条任务还没有步骤记录', '步骤随任务执行逐条落账。刚发起或已被清理的任务可能暂时没有。'));
    }
    wrap.appendChild(el('p', 'tasks-foot', '只读视图：确认与操作在聊天线程里进行。'));
    // 终态语义脚注（Claude 官方文案中文化）：「完成」只表示这次运行正常
    // 收尾，不表示目标达成——步骤账本才是证据。没有这句，一个 FAILED
    // 的 answer 和一个 COMPLETED 的 answer 在用户眼里一样重。
    wrap.appendChild(el('p', 'tasks-foot', '状态只表示这次运行如何结束，不代表任务目标是否达成——细节在步骤账本里。'));

    tasksBody.appendChild(wrap);
    tasksTitle.textContent = task.title || '任务详情';
    tasksBackBtn.setAttribute('aria-label', '返回列表');
    setTasksLevel('detail');
  }

  function renderTaskDetailMissing() {
    tasksBody.textContent = '';
    tasksBody.appendChild(buildEmptyState(
      '任务不存在或已被清理',
      '账本里找不到这条记录。它可能来自一次已经清除的应用数据。'
    ));
    tasksTitle.textContent = '任务详情';
    tasksBackBtn.setAttribute('aria-label', '返回列表');
    setTasksLevel('detail');   // 停在这一级，返回键回列表而不是直接关页
  }

  /* ------------------------------------------------------------ 开关与层级
     level 三级：closed / list / detail。每次过渡都经 notifyTasksView
     报给 Kotlin —— Android BACK 的处理完全读这个镜像状态，不搞异步
     回查（evaluateJavascript 的回调进不了同步的 BACK 链）。 */
  var tasksLevel = 'closed';
  var openTaskId = null;

  function setTasksLevel(level) {
    tasksLevel = level;
    var b = bridge();
    if (b && b.notifyTasksView) {
      try { b.notifyTasksView(level); }
      catch (e) { console.warn('[cds] notifyTasksView failed:', e); }
    }
  }

  function loadTaskList() {
    var tasks = readTasksFromBridge();
    if (tasks === null) tasks = TASK_SAMPLES;   // 无桥：设计样例
    openTaskId = null;
    tasksTitle.textContent = '任务';
    tasksBackBtn.setAttribute('aria-label', '关闭');
    renderTaskList(tasks);
    setTasksLevel('list');
    startTasksTicker();
  }

  function openTasksView() {
    if (!tasksView) return;
    closeSettingsView();
    closeSchedulesView();
    closeNav();
    tasksScrim.setAttribute('data-open', 'true');
    tasksView.setAttribute('data-open', 'true');
    loadTaskList();
  }

  function closeTasksView() {
    if (!tasksView) return;
    tasksScrim.setAttribute('data-open', 'false');
    tasksView.setAttribute('data-open', 'false');
    stopTasksTicker();
    openTaskId = null;
    setTasksLevel('closed');
  }

  function openTaskDetail(id) {
    var task = readTaskDetailFromBridge(id);
    if (!task) {
      // 无桥时详情也只在样例里找；有桥但读不到 = 诚实的缺失态
      var b = bridge();
      if (!b || !b.readTaskDetail) {
        for (var i = 0; i < TASK_SAMPLES.length; i++) {
          if (TASK_SAMPLES[i].id === id) { task = TASK_SAMPLES[i]; break; }
        }
      }
    }
    if (!task) { renderTaskDetailMissing(); return; }
    renderTaskDetail(task);
  }

  /* Kotlin BACK 键的页面侧答复：处理了返回 true，没开任何一层返回 false。
     detail → 回列表（重读，推送过的变化此时进来）；list → 关页。 */
  function tasksBack() {
    if (tasksLevel === 'detail') { loadTaskList(); return true; }
    if (tasksLevel === 'list') { closeTasksView(); return true; }
    return false;
  }

  /* ------------------------------------------------------------ 耗时 ticker
     运行中卡的「已耗时」按 createdAt 现算，30s 一次。只认 data-since
     （运行中行才有），终态行的完成时间是静态文本，不会被刷掉。 */
  var tasksTimer = null;

  function startTasksTicker() {
    if (tasksTimer) return;
    tasksTimer = setInterval(tickTasksElapsed, 30000);
  }

  function stopTasksTicker() {
    if (tasksTimer) { clearInterval(tasksTimer); tasksTimer = null; }
  }

  function tickTasksElapsed() {
    var nodes = tasksBody.querySelectorAll('.task-elapsed[data-since]');
    for (var i = 0; i < nodes.length; i++) {
      var since = parseInt(nodes[i].getAttribute('data-since'), 10);
      if (!isNaN(since)) {
        nodes[i].textContent = '已耗时 ' + fmtElapsed(Date.now() - since);
      }
    }
  }

  /* ------------------------------------------------------------ Kotlin 推送
     TaskSink 落地后由它调用。关着时只更新徽标 —— 下次打开从桥重读，
     推送无需缓存；开着详情时若当前任务被推送更新就重读详情，若它从
     账本里消失了就回列表（不留一个详情指向不存在的记录）。 */
  function renderTasks(payload) {
    var tasks = payload;
    if (typeof payload === 'string') {
      try { tasks = JSON.parse(payload || '[]'); }
      catch (e) { console.warn('[cds] renderTasks: bad json', e); return; }
    }
    if (!Array.isArray(tasks)) { console.warn('[cds] renderTasks: not an array'); return; }
    updateTasksBadge(tasks);
    if (tasksLevel === 'closed') return;
    if (tasksLevel === 'detail') {
      var stillThere = tasks.some(function (t) { return String(t.id) === String(openTaskId); });
      if (stillThere) {
        var fresh = readTaskDetailFromBridge(openTaskId);
        if (fresh) renderTaskDetail(fresh);
      } else {
        loadTaskList();
      }
      return;
    }
    renderTaskList(tasks);
  }

  function renderTaskDetailPush(payload) {
    var task = payload;
    if (typeof payload === 'string') {
      try { task = JSON.parse(payload || 'null'); }
      catch (e) { console.warn('[cds] renderTaskDetail: bad json', e); return; }
    }
    if (task && typeof task === 'object' && tasksLevel === 'detail' &&
        String(task.id) === String(openTaskId)) {
      renderTaskDetail(task);
    }
  }

  /* ------------------------------------------------------------ 完成 chips
     任务答案回合顶部的一行数字（耗时/步骤/工具/已建日程）。Kotlin 在 feed
     重建之后紧接着调用（chips 搭重建那一班车，晚一步就会被 renderLiveRaw
     的清空抹掉，见 ClaudeUiPreviewActivity.publish 的注释）——所以这里
     只需把行挂到最后一个助手回合上。没有助手回合就不挂：那是没有答案
     可摘要的路径，挂上去就是张冠李戴。 */
  function renderTaskChips(chips) {
    if (!chips || !chips.length) return;
    var turns = document.querySelectorAll('#feed .turn-assistant');
    if (!turns.length) return;
    turns[turns.length - 1].appendChild(buildTaskChips(chips));
  }

  function buildTaskChips(chips) {
    var row = el('div', 'task-chips');
    chips.forEach(function (c) {
      var chip = el('span', 'task-chip');
      chip.appendChild(el('span', 'task-chip-label', c.label));
      chip.appendChild(el('span', 'task-chip-value', c.value));
      row.appendChild(chip);
    });
    return row;
  }

  /* ------------------------------------------------------------ 接线 */
  var tasksBtn = document.getElementById('tasksBtn');
  if (tasksBtn) {
    tasksBtn.addEventListener('click', function () {
      closeNav();
      openTasksView();
    });
  }
  if (tasksScrim) {
    tasksScrim.addEventListener('click', closeTasksView);
  }
  if (tasksBackBtn) {
    tasksBackBtn.addEventListener('click', tasksBack);
  }
  if (tasksBody) {
    tasksBody.addEventListener('click', function (e) {
      var card = e.target && e.target.closest ? e.target.closest('.task-card') : null;
      if (!card) return;
      openTaskDetail(card.getAttribute('data-task-id'));
    });
  }

  /* ------------------------------------------------------------ 定时队列
     与任务账本同构的两级页：列表（schedule-card）与详情（意图 + 上次运行
     结果），都在 #schedulesBody 里整表重建。数据两条来路，界限同样划在
     「有没有桥」上：
       · 有桥（真机）：PokeClawChat.readScheduleList() / readScheduleDetail(id)，
         ScheduledTaskStore 是唯一事实源；[] = 空队列，显示空态。
       · 无桥（纯浏览器预览）：SCHEDULE_SAMPLES 设计样例。

     与任务页的唯一结构差异：这里有一个写操作——取消。取消的是「尚未
     运行的意图」而不是账本记录（账本仍然只读），所以两步确认：一步
     取消的误触成本是「用户以为取消了、其实还在跑」，比多点一次贵得多。
     队列的诚实全靠状态词：QUEUED 不等同于运行中（在等前一个任务），
     MISSED 是设备关机错过、不迟跑——这两个状态在卡片上都必须带原因。 */
  var schedulesView = document.getElementById('schedulesView');
  var schedulesScrim = document.getElementById('schedulesScrim');
  var schedulesBody = document.getElementById('schedulesBody');
  var schedulesTitle = document.getElementById('schedulesTitle');
  var schedulesBackBtn = document.getElementById('schedulesBackBtn');
  var schedulesBadge = document.getElementById('schedulesBadge');

  /* 五态。glyph 纪律同任务页：扫读比辨色快，且不依赖颜色感知。 */
  var SCHEDULE_STATES = {
    SCHEDULED: { label: '待运行', dot: 'running', glyph: '' },
    QUEUED:    { label: '排队中', dot: 'clay',   glyph: '' },
    MISSED:    { label: '已错过', dot: 'error',  glyph: '✕' },
    FIRED:     { label: '已触发', dot: 'ready',  glyph: '✓' },
    CANCELLED: { label: '已取消', dot: 'muted',  glyph: '' }
  };

  function scheduleState(key) {
    return SCHEDULE_STATES[key] || { label: key || '未知状态', dot: 'muted' };
  }

  /* 未来时刻的相对量，与 fmtElapsed（过去）互补。倒计时比绝对时间有
     行动感——「还有 16 小时」是在回答「我还要管它吗」。 */
  function fmtUntil(ts) {
    var ms = ts - Date.now();
    if (ms <= 0) return '即将运行';
    var minutes = Math.floor(ms / 60000);
    if (minutes < 1) return '不到 1 分钟';
    if (minutes < 60) return minutes + ' 分钟';
    var hours = Math.floor(minutes / 60);
    if (hours < 24) return hours + ' 小时';
    return Math.floor(hours / 24) + ' 天';
  }

  /* 设计样例（仅无桥预览）。故事线与任务页一致：妈妈的日历简报。 */
  var SCHEDULE_SAMPLES = [
    {
      id: 'sample-daily-brief',
      title: '总结我今天的日历和未读通知',
      state: 'SCHEDULED',
      repeat: '每天',
      nextFireAt: Date.now() + 16 * 3600 * 1000,
      createdAt: minutesAgo(60 * 24 * 9),
      calendarAnchorHours: 12
    },
    {
      id: 'sample-queued',
      title: '给妈妈回个电话说发票的事',
      state: 'QUEUED',
      repeat: '单次',
      createdAt: minutesAgo(26),
      outcome: 'queued: another task was running when this fired'
    },
    {
      id: 'sample-missed',
      title: '晨间简报：今天的日程',
      state: 'MISSED',
      repeat: '每天',
      createdAt: minutesAgo(60 * 30),
      outcome: 'missed: the device was off at the scheduled time'
    },
    {
      id: 'sample-fired',
      title: '每周五下午汇总本周开销',
      state: 'FIRED',
      repeat: '每周',
      createdAt: minutesAgo(60 * 24 * 8),
      lastFiredAt: minutesAgo(180),
      outcome: 'done: 3 笔开销已汇总，明细在任务账本'
    }
  ];

  /* ------------------------------------------------------------ 桥读取
     与任务页同一契约：null = 没有桥（预览模式），[] = 有桥、空队列。 */
  function readSchedulesFromBridge() {
    var b = bridge();
    if (!b || !b.readScheduleList) return null;
    try {
      var raw = b.readScheduleList();
      if (!raw) return [];
      var arr = JSON.parse(raw);
      return Array.isArray(arr) ? arr : [];
    } catch (e) {
      console.warn('[cds] readScheduleList failed:', e);
      return [];
    }
  }

  function readScheduleDetailFromBridge(id) {
    var b = bridge();
    if (!b || !b.readScheduleDetail) return null;
    try {
      var task = JSON.parse(b.readScheduleDetail(id) || 'null');
      return task && typeof task === 'object' ? task : null;
    } catch (e) {
      console.warn('[cds] readScheduleDetail failed:', e);
      return null;
    }
  }

  /* 设备级事实：精确闹钟是否被允许。降级不是空态，是队列顶部一条
     诚实的话——任务照跑，时间可能漂（BACKLOG P3）。 */
  function readSchedulePrefs() {
    var b = bridge();
    if (!b || !b.readSchedulePrefs) return null;
    try {
      var p = JSON.parse(b.readSchedulePrefs() || 'null');
      return p && typeof p === 'object' ? p : null;
    } catch (e) {
      console.warn('[cds] readSchedulePrefs failed:', e);
      return null;
    }
  }

  function schedulesDegraded() {
    var p = readSchedulePrefs();
    return !!(p && p.exactAlarms === false);
  }

  /* ------------------------------------------------------------ 徽标
     只数 live 任务（待运行 + 排队中）。徽章是状态不是装饰。 */
  function updateSchedulesBadge(tasks) {
    if (!schedulesBadge) return;
    var live = 0;
    (tasks || []).forEach(function (t) {
      if (t.state === 'SCHEDULED' || t.state === 'QUEUED') live += 1;
    });
    schedulesBadge.textContent = String(live);
    schedulesBadge.setAttribute('data-show', live > 0 ? 'true' : 'false');
  }

  /* ------------------------------------------------------------ 列表级 */
  function buildScheduleCard(t) {
    var st = scheduleState(t.state);
    var card = el('button', 'schedule-card');
    card.type = 'button';
    card.setAttribute('data-schedule-id', String(t.id == null ? '' : t.id));
    card.innerHTML =
      '<div class="schedule-card-head">' +
        '<span class="status-dot"></span>' +
        '<span class="schedule-card-title"></span>' +
        '<span class="schedule-state"></span>' +
      '</div>' +
      '<div class="schedule-card-meta"></div>' +
      '<div class="schedule-card-next"></div>';
    card.querySelector('.status-dot').setAttribute('data-kind', st.dot);
    card.querySelector('.schedule-card-title').textContent = t.title || '（无标题任务）';
    var pill = card.querySelector('.schedule-state');
    pill.setAttribute('data-state', String(t.state || ''));
    pill.textContent = st.glyph ? st.glyph + ' ' + st.label : st.label;

    var meta = card.querySelector('.schedule-card-meta');
    meta.appendChild(document.createTextNode(t.repeat || '单次'));
    meta.appendChild(document.createTextNode(' · '));
    if (t.state === 'SCHEDULED' && t.nextFireAt) {
      var next = el('span', 'schedule-next', '还有 ' + fmtUntil(t.nextFireAt));
      next.setAttribute('data-fire', String(t.nextFireAt));   // ticker 按它续算
      meta.appendChild(next);
    } else if (t.state === 'QUEUED') {
      // 排队不是运行中：说清楚它在等什么，否则用户会以为它已经跑挂了。
      meta.appendChild(document.createTextNode('等待前一个任务结束'));
    } else if (t.lastFiredAt) {
      meta.appendChild(document.createTextNode(
        fmtClock(t.lastFiredAt) + (t.state === 'MISSED' ? ' 错过' : ' 触发')
      ));
    } else {
      meta.appendChild(document.createTextNode('设置于 ' + fmtClock(t.createdAt || Date.now())));
    }

    var tail = card.querySelector('.schedule-card-next');
    if (t.outcome) {
      // 排队/错过/上次运行的原因随行载荷走——没有它，QUEUED/MISSED
      // 在设备上就只剩一个没有下文的标签。
      tail.textContent = t.outcome;
      if (t.state === 'MISSED') tail.setAttribute('data-kind', 'error');
    } else if (t.calendarAnchorHours) {
      tail.textContent = '触发时带入接下来 ' + t.calendarAnchorHours + ' 小时的日历';
    } else {
      tail.parentNode.removeChild(tail);
    }
    return card;
  }

  function buildSchedulesNote() {
    var box = el('div', 'schedules-note');
    box.textContent =
      '系统没有授予「精确闹钟」权限：定时任务仍然会运行，但可能被系统延后——' +
      '07:30 的简报迟到几分钟是正常的。要准时就到系统设置里给 PokeClaw 打开「闹钟和提醒」。';
    return box;
  }

  function renderScheduleList(tasks, degraded) {
    schedulesBody.textContent = '';
    updateSchedulesBadge(tasks);
    if (degraded) schedulesBody.appendChild(buildSchedulesNote());
    if (!tasks.length) {
      schedulesBody.appendChild(buildEmptyState(
        '还没有定时任务',
        '在聊天里说「每天早上七点半看我今天的日历」，任务就会落到这里——到点自动跑，排队、错过、取消都写在明面上。'
      ));
      return;
    }
    var frag = document.createDocumentFragment();
    tasks.forEach(function (t) { frag.appendChild(buildScheduleCard(t)); });
    schedulesBody.appendChild(frag);
  }

  /* ------------------------------------------------------------ 取消区
     两步确认。初态一个出口；确认态把后果写成句子再给两个出口，
     「再想想」回列表（不保留半确认状态）。成功取消后回列表看终态——
     详情留着就是旧状态，那是在显示一个已经不存在的事实。 */
  function buildCancelArea(id) {
    var box = el('div', 'schedule-cancel');
    var btn = el('button', 'schedule-cancel-btn', '取消这个任务');
    btn.type = 'button';
    btn.addEventListener('click', function () {
      box.textContent = '';
      var confirm = el('div', 'schedule-cancel-confirm');
      confirm.innerHTML =
        '<div class="schedule-cancel-confirm-text"></div>' +
        '<div class="schedule-cancel-actions"></div>';
      confirm.querySelector('.schedule-cancel-confirm-text').textContent =
        '确认取消？这个任务不会再运行；已经跑过的记录不受影响。';
      var actions = confirm.querySelector('.schedule-cancel-actions');
      var yes = el('button', 'schedule-cancel-btn', '确认取消');
      yes.type = 'button';
      var no = el('button', 'pill cds-btn-squish', '再想想');
      no.type = 'button';
      yes.addEventListener('click', function () { doCancelSchedule(id, confirm); });
      no.addEventListener('click', function () { loadScheduleList(); });
      actions.appendChild(yes);
      actions.appendChild(no);
      box.appendChild(confirm);
    });
    box.appendChild(btn);
    return box;
  }

  function doCancelSchedule(id, confirmBox) {
    var b = bridge();
    var text = confirmBox.querySelector('.schedule-cancel-confirm-text');
    if (!b || !b.cancelSchedule) {
      text.textContent = '预览模式：取消需要连接到 PokeClaw。';
      return;
    }
    var ok = false;
    try { ok = !!b.cancelSchedule(id); }
    catch (e) { console.warn('[cds] cancelSchedule failed:', e); }
    if (ok) {
      loadScheduleList();
    } else {
      // 失败不假装成功：任务不存在、已跑完或已取消，都说出来。
      text.textContent = '取消失败：这个任务不存在、已经跑完，或者已被取消。';
    }
  }

  /* ------------------------------------------------------------ 详情级 */
  function renderScheduleDetail(task) {
    schedulesBody.textContent = '';
    openScheduleId = task.id;
    var st = scheduleState(task.state);

    var wrap = el('div', 'schedule-detail');
    wrap.innerHTML =
      '<div class="task-detail-title"></div>' +
      '<div class="task-detail-meta"></div>';
    wrap.querySelector('.task-detail-title').textContent = task.title || '定时详情';

    var meta = wrap.querySelector('.task-detail-meta');
    // 顺序即读法：状态 · 周期 · （倒计时/排队说明）· 触发时间 · 创建于 ·
    // 日历锚定。倒计时是 span 不是文本节点（ticker 要按 data-fire 续算），
    // 所以它不能进纯文本的 bits 数组——拼在行尾就成了「创建于…还有 15 小时」
    // 这种倒装句。
    meta.appendChild(document.createTextNode(st.label));
    meta.appendChild(document.createTextNode(' · '));
    meta.appendChild(document.createTextNode(task.repeat || '单次'));
    if (task.state === 'SCHEDULED') {
      meta.appendChild(document.createTextNode(' · '));
      if (task.nextFireAt) {
        var span = el('span', 'schedule-next', '还有 ' + fmtUntil(task.nextFireAt));
        span.setAttribute('data-fire', String(task.nextFireAt));
        meta.appendChild(span);
      } else {
        meta.appendChild(document.createTextNode('运行时间待定'));
      }
    } else if (task.state === 'QUEUED') {
      meta.appendChild(document.createTextNode(' · 前一个任务结束后自动补跑'));
    } else if (task.lastFiredAt) {
      meta.appendChild(document.createTextNode(
        ' · ' + fmtClock(task.lastFiredAt) + (task.state === 'MISSED' ? ' 错过' : ' 触发')
      ));
    }
    meta.appendChild(document.createTextNode(' · 创建于 ' + fmtClock(task.createdAt || Date.now())));
    if (task.calendarAnchorHours) {
      meta.appendChild(document.createTextNode(' · 触发时带入 ' + task.calendarAnchorHours + ' 小时日历'));
    }

    if (task.prompt) {
      var prompt = el('div', 'task-detail-prompt');
      prompt.textContent = task.prompt;
      wrap.appendChild(prompt);
    }

    // 上次运行/排队/错过原因：复用任务页终态载荷块（console 块 + 标签），
    // 一个页面两种盒子就够，不新造第三种。
    if (task.outcome) {
      var label = task.state === 'QUEUED' ? '排队原因'
        : task.state === 'MISSED' ? '错过原因' : '上次运行';
      var kind = task.state === 'MISSED' ? 'task-detail-error' : 'task-detail-answer';
      wrap.appendChild(buildDetailBlock(label, task.outcome, kind, task.state === 'MISSED' ? '✕' : '✓'));
    }

    if (task.state === 'SCHEDULED' || task.state === 'QUEUED') {
      wrap.appendChild(buildCancelArea(task.id));
    }
    wrap.appendChild(el('p', 'schedules-foot', '任务账本只读；这里取消的是尚未运行的意图，历史记录不受影响。'));

    schedulesBody.appendChild(wrap);
    schedulesTitle.textContent = task.title || '定时详情';
    schedulesBackBtn.setAttribute('aria-label', '返回列表');
    setSchedulesLevel('detail');
  }

  function renderScheduleDetailMissing() {
    schedulesBody.textContent = '';
    schedulesBody.appendChild(buildEmptyState(
      '定时任务不存在或已被清理',
      '队列里找不到这条记录。它可能来自一次已经清除的应用数据。'
    ));
    schedulesTitle.textContent = '定时详情';
    schedulesBackBtn.setAttribute('aria-label', '返回列表');
    setSchedulesLevel('detail');
  }

  /* ------------------------------------------------------------ 开关与层级
     与任务页同一机制：每次过渡都经 notifySchedulesView 报给 Kotlin，
     Android BACK 的处理完全读这个镜像状态。 */
  var schedulesLevel = 'closed';
  var openScheduleId = null;

  function setSchedulesLevel(level) {
    schedulesLevel = level;
    var b = bridge();
    if (b && b.notifySchedulesView) {
      try { b.notifySchedulesView(level); }
      catch (e) { console.warn('[cds] notifySchedulesView failed:', e); }
    }
  }

  function loadScheduleList() {
    var tasks = readSchedulesFromBridge();
    if (tasks === null) tasks = SCHEDULE_SAMPLES;   // 无桥：设计样例
    var degraded = schedulesDegraded();
    openScheduleId = null;
    schedulesTitle.textContent = '定时';
    schedulesBackBtn.setAttribute('aria-label', '关闭');
    renderScheduleList(tasks, degraded);
    setSchedulesLevel('list');
    startSchedulesTicker();
  }

  function openSchedulesView() {
    if (!schedulesView) return;
    closeSettingsView();
    closeTasksView();
    closeNav();
    schedulesScrim.setAttribute('data-open', 'true');
    schedulesView.setAttribute('data-open', 'true');
    loadScheduleList();
  }

  function closeSchedulesView() {
    if (!schedulesView) return;
    schedulesScrim.setAttribute('data-open', 'false');
    schedulesView.setAttribute('data-open', 'false');
    stopSchedulesTicker();
    openScheduleId = null;
    setSchedulesLevel('closed');
  }

  function openScheduleDetail(id) {
    var task = readScheduleDetailFromBridge(id);
    if (!task) {
      var b = bridge();
      if (!b || !b.readScheduleDetail) {
        for (var i = 0; i < SCHEDULE_SAMPLES.length; i++) {
          if (SCHEDULE_SAMPLES[i].id === id) { task = SCHEDULE_SAMPLES[i]; break; }
        }
      }
    }
    if (!task) { renderScheduleDetailMissing(); return; }
    renderScheduleDetail(task);
  }

  /* Kotlin BACK 键的页面侧答复：detail → 回列表，list → 关页。 */
  function schedulesBack() {
    if (schedulesLevel === 'detail') { loadScheduleList(); return true; }
    if (schedulesLevel === 'list') { closeSchedulesView(); return true; }
    return false;
  }

  /* ------------------------------------------------------------ 倒计时 ticker
     「还有 N 小时」按 nextFireAt 现算，30s 一次。只认 data-fire（待运行
     行/详情才有），终态行的时间是静态文本，不会被刷掉。 */
  var schedulesTimer = null;

  function startSchedulesTicker() {
    if (schedulesTimer) return;
    schedulesTimer = setInterval(tickSchedulesNext, 30000);
  }

  function stopSchedulesTicker() {
    if (schedulesTimer) { clearInterval(schedulesTimer); schedulesTimer = null; }
  }

  function tickSchedulesNext() {
    var nodes = schedulesBody.querySelectorAll('.schedule-next[data-fire]');
    for (var i = 0; i < nodes.length; i++) {
      var fire = parseInt(nodes[i].getAttribute('data-fire'), 10);
      if (!isNaN(fire)) nodes[i].textContent = '还有 ' + fmtUntil(fire);
    }
  }

  /* ------------------------------------------------------------ Kotlin 推送
     ScheduleSink 落地后由它调用。关着时只更新徽标；开着详情时若当前任务
     被推送更新就重读详情（取消/触发后状态变了），不在队列里就回列表。 */
  function renderSchedules(payload) {
    var tasks = payload;
    if (typeof payload === 'string') {
      try { tasks = JSON.parse(payload || '[]'); }
      catch (e) { console.warn('[cds] renderSchedules: bad json', e); return; }
    }
    if (!Array.isArray(tasks)) { console.warn('[cds] renderSchedules: not an array'); return; }
    updateSchedulesBadge(tasks);
    if (schedulesLevel === 'closed') return;
    if (schedulesLevel === 'detail') {
      var stillThere = tasks.some(function (t) { return String(t.id) === String(openScheduleId); });
      if (stillThere) {
        var fresh = readScheduleDetailFromBridge(openScheduleId);
        if (fresh) renderScheduleDetail(fresh);
      } else {
        loadScheduleList();
      }
      return;
    }
    renderScheduleList(tasks, schedulesDegraded());
  }

  /* ------------------------------------------------------------ 接线 */
  var schedulesBtn = document.getElementById('schedulesBtn');
  if (schedulesBtn) {
    schedulesBtn.addEventListener('click', function () {
      closeNav();
      // 用户自己摸到了入口——引导的目的达成，同样不再出现。
      dismissCoachMark();
      openSchedulesView();
    });
  }
  if (schedulesScrim) {
    schedulesScrim.addEventListener('click', closeSchedulesView);
  }
  if (schedulesBackBtn) {
    schedulesBackBtn.addEventListener('click', schedulesBack);
  }
  if (schedulesBody) {
    schedulesBody.addEventListener('click', function (e) {
      var card = e.target && e.target.closest ? e.target.closest('.schedule-card') : null;
      if (!card) return;
      openScheduleDetail(card.getAttribute('data-schedule-id'));
    });
  }

  /* ==================================================== QA 自检面板
     为什么把诊断画进页面，而不是只靠 logcat：
       §18.7  uiautomator 看不见 WebView 内部，截图才是可靠 ground truth
       §18.13 postDelayed 探针 3 次只触发 1 次，取证全是噪音
     画进页面之后，一张截图同时回答四个互斥的可能：

       loads 是否增长          → 存储跨进程存活吗
       自检 写/读/一致          → localStorage 本身能读写吗
       applied 与 stored 是否同 → 存下来了但没读回来？
       spAccent 与 stored.accent → 上次原生记住的配色 vs 本地存的
         同 → localStorage 被清空了（原生记得，WebView 不记得）
         不同 → cds.js 的读写路径断了

     第四条是关键：SharedPreferences 一定持久，所以它是唯一的
     「已知为真的对照组」。没有对照组就无法区分「没写」和「写丢了」。
     ==================================================== */
  var QA_LOADS_KEY = 'cds-qa-loads';
  var QA_TOKEN_KEY = 'cds-qa-token';
  var qaPanel = null;
  var qaLaunchAt = new Date();

  // 本次页面加载序号。它每次 +1 并落盘，所以它是「存储跨重启是否存活」
  // 的唯一可靠读数：恒为 1 = 每次启动都从空存储开始。
  var qaLoads = (function () {
    var n = parseInt(read(QA_LOADS_KEY) || '0', 10);
    if (isNaN(n)) n = 0;
    n += 1;
    store(QA_LOADS_KEY, String(n));
    return n;
  }());

  // 写-读-比对三列，能区分「setItem 抛错」「setItem 静默失败」
  // 和「写进去了」，这三种在页面外观上完全一样。
  function qaRoundTrip() {
    var token = 't' + Date.now() + '-' + Math.floor(Math.random() * 1e6);
    var out = { w: '-', r: '-', ok: false };
    try { localStorage.setItem(QA_TOKEN_KEY, token); out.w = 'OK'; }
    catch (e) { out.w = 'THREW'; return out; }
    try { out.r = String(localStorage.getItem(QA_TOKEN_KEY)); } catch (e) { out.r = 'THREW'; }
    out.ok = out.r === token;
    return out;
  }

  function qaState() {
    var rt = qaRoundTrip();
    var all = {};
    try {
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        all[k] = String(localStorage.getItem(k));
      }
    } catch (e) { all = { ERROR: String(e) }; }
    return {
      loads: qaLoads,
      launchAt: qaLaunchAt.toISOString().slice(11, 19),
      origin: String(location.origin),
      protocol: String(location.protocol),
      ua: String(navigator.userAgent).replace(/^.*Chrome\//, 'Chrome/').replace(/ Mobile.*/, ''),
      applied: {
        mode: root.getAttribute('data-mode'),
        accent: root.getAttribute('data-theme'),
        base: root.getAttribute('data-base'),
        effort: read(EFFORT_KEY),
        policy: read(POLICY_KEY)
      },
      stored: {
        theme: read(THEME_KEY),
        accent: read(ACCENT_KEY),
        base: read(BASE_KEY),
        effort: read(EFFORT_KEY),
        policy: read(POLICY_KEY),
        len: Object.keys(all).length
      },
      roundTrip: rt
    };
  }

  function qaReport() {
    var s = qaState();
    if (window.PokeClawQA) {
      // 顺序要紧：先读原生记住的上次值，再写本次值。反过来会读到刚写的
      // 自己，对照组就退化成 localStorage 自己，等于什么都没验。
      try {
        var last = window.PokeClawQA.lastState();
        if (last) {
          var parts = String(last).split('|');
          s.spAccent = parts[0];
          s.spMode = parts[1];
        }
      } catch (e) { s.spAccent = 'THREW'; }
      try {
        window.PokeClawQA.report(
          JSON.stringify(s), String(s.applied.accent), String(s.applied.mode)
        );
      } catch (e) { /* 无桥：只留页面内证据 */ }
    }
    console.log('[cdsQA] ' + JSON.stringify(s));
    return s;
  }

  // 面板已整体移除。
  //
  // 它是为了排查「配色存不住」临时加的诊断脚手架，排查早已结束（QA H13-b
  // PASS，见 QA_CHECKLIST）。但它一直留在页面顶端，污染了要给用户看的成品
  // —— 而且它在设备上把自身源码当纯文本渲染出来（同一份代码在桌面 Chromium
  // 上正常），排查那个渲染问题本身没有价值：结论已经拿到了。
  //
  // 诊断能力不丢，只是改为纯编程接口：`window.__cds.qa()` 返回同一份状态对象，
  // 自动化仍可调用，也仍会经 PokeClawQA 桥镜像到 SharedPreferences。
  // 结论写在代码注释与 QA_CHECKLIST 里，而不是永久糊在设计稿顶上。
  function qaBuildPanel() {
    // 故意不渲染任何东西 —— 见上。留这个空函数只为记录「面板已移除」这件事。
  }

  /* ------------------------------------------------------------------ 启动 */
  qaBuildPanel();
  initTheme();
  initAccent();
  initBase();
  initTaskGears();
  // 必须在主题初始化之后、且在首次读桥之前。addJavascriptInterface 在页面
  // 加载前就注入了，所以这里读到的一定是最终状态。
  if (hasNativeBridge()) enterLiveMode();
  // 一次性引导的首评：桌面宽度侧栏常驻，加载后稍待即评；手机宽度抽屉
  // 关着，这时显示也看不见，真正露面在抽屉打开时（menuBtn 回调里再评）。
  setTimeout(maybeShowCoachMark, 1500);
  syncAutoGrow();
  requestAnimationFrame(function () { toBottom(false); });

  // 演示入口：首次打开时演示一次限流退避，之后不再打扰。
  // 真实接入时由请求层在收到 429 时调用，UI 不该自己判断限流。
  try {
    if (!localStorage.getItem('cds-mockup-rl-demo')) {
      localStorage.setItem('cds-mockup-rl-demo', '1');
      setTimeout(triggerBackoff, 1200);
    }
  } catch (e) { /* file:// 下 localStorage 可能不可用 */ }

  // 暴露给调试 / 自动化验证
  window.__cds = {
    renderLive: renderLive,
    renderLiveRaw: renderLiveRaw,
    renderConfirmation: renderConfirmation,
    removeConfirmation: removeConfirmation,
    enterLiveMode: enterLiveMode,
    hasNativeBridge: hasNativeBridge,
    openSettingsView: openSettingsView,
    closeSettingsView: closeSettingsView,
    settingsViewIsOpen: settingsViewIsOpen,
    openTasksView: openTasksView,
    closeTasksView: closeTasksView,
    tasksBack: tasksBack,
    tasksLevel: function () { return tasksLevel; },
    renderTasks: renderTasks,
    renderTaskDetail: renderTaskDetailPush,
    renderTaskChips: renderTaskChips,
    openSchedulesView: openSchedulesView,
    closeSchedulesView: closeSchedulesView,
    schedulesBack: schedulesBack,
    schedulesLevel: function () { return schedulesLevel; },
    renderSchedules: renderSchedules,
    rateLimit: triggerBackoff,
    resetRateLimit: function () {
      clearInterval(backoff.timer); backoff.timer = null;
      backoff.waiting = false; backoff.manualOnly = false; backoff.runs = 0;
      renderBackoff();
    },
    setAccent: applyAccent,
    setBase: applyBase,
    applyTheme: applyTheme,
    applyPrefs: applyPrefs,
    setEffort: applyEffort,
    setAutoAllow: function (auto) { applyPolicy(auto ? 'auto' : 'ask'); },
    qa: qaReport,
    state: function () {
      return { pinned: pinned, showJump: showJump, backoff: backoff,
               mode: root.getAttribute('data-mode'),
               accent: root.getAttribute('data-theme'),
               base: root.getAttribute('data-base'),
               effort: read(EFFORT_KEY),
               policy: read(POLICY_KEY) };
    }
  };
})();
