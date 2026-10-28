// android.js — the APK's layer over the game page: controller mapping, touch controls, a side
// menu, an FPS counter and a render-scale setting. The Android app adds it to index.html when it
// packs the web build (scripts/android-build.sh); the dev page and the realm never load it.
//
// Everything reaches the game as ordinary page input dispatched on the canvas: KeyboardEvents
// with the `code` the game reads (KeyW, Space, Digit1…), PointerEvents for the cursor and clicks,
// WheelEvents for zoom. So the game needs no Android code, and this works in any WebView,
// whether or not it exposes the Gamepad API: the app forwards the controller itself
// (`survivorsAndroid.pad`). A desktop browser runs it too: add the two tags to a page to try it.
//
// Settings live in localStorage under `sa-settings`.
(() => {
  'use strict';
  const host = window.AndroidHost || null; // the app's JavascriptInterface, when there is one
  const canvas = () => document.getElementById('benilla');

  // ── Settings ──

  const DEFAULTS = { touch: 'auto', fps: false, scale: 1 };
  let settings = { ...DEFAULTS };
  try {
    settings = { ...DEFAULTS, ...JSON.parse(localStorage.getItem('sa-settings') || '{}') };
  } catch (_) {}
  const save = () => {
    try {
      localStorage.setItem('sa-settings', JSON.stringify(settings));
    } catch (_) {}
  };

  // ── Keys ──
  //
  // Several sources can hold one key (the stick and the touch joystick both hold W): a key goes
  // down when the first source takes it and up when the last lets go.

  const KEY_TEXT = {
    KeyW: 'w', KeyA: 'a', KeyS: 's', KeyD: 'd', KeyF: 'f', KeyC: 'c', KeyG: 'g',
    Space: ' ', Enter: 'Enter', Escape: 'Escape',
    Digit1: '1', Digit2: '2', Digit3: '3',
    ArrowUp: 'ArrowUp', ArrowDown: 'ArrowDown', ArrowLeft: 'ArrowLeft', ArrowRight: 'ArrowRight',
  };
  const KEYCODE = {
    KeyW: 87, KeyA: 65, KeyS: 83, KeyD: 68, KeyF: 70, KeyC: 67, KeyG: 71, Space: 32, Enter: 13,
    Escape: 27, Digit1: 49, Digit2: 50, Digit3: 51, ArrowUp: 38, ArrowDown: 40, ArrowLeft: 37,
    ArrowRight: 39,
  };
  const sources = new Map(); // source -> Set of codes
  const down = new Set(); // codes currently down in the page

  function sendKey(code, isDown) {
    const c = canvas();
    if (!c) return;
    c.dispatchEvent(new KeyboardEvent(isDown ? 'keydown' : 'keyup', {
      code, key: KEY_TEXT[code] || code, keyCode: KEYCODE[code] || 0, which: KEYCODE[code] || 0,
      bubbles: true, cancelable: true,
    }));
  }

  function setHeld(source, codes) {
    sources.set(source, new Set(codes));
    const want = new Set();
    for (const set of sources.values()) for (const c of set) want.add(c);
    for (const c of down) if (!want.has(c)) { down.delete(c); sendKey(c, false); }
    for (const c of want) if (!down.has(c)) { down.add(c); sendKey(c, true); }
  }

  function hold(source, code, on) {
    const set = new Set(sources.get(source) || []);
    on ? set.add(code) : set.delete(code);
    setHeld(source, set);
  }

  // A tap: down now, up a few frames later, so the game sees it as one `just_pressed`.
  function tap(code) {
    const src = 'tap:' + code;
    hold(src, code, true);
    setTimeout(() => hold(src, code, false), 80);
  }

  function releaseAll() {
    for (const s of [...sources.keys()]) setHeld(s, []);
  }

  // ── Cursor ──
  //
  // The hero faces the cursor and F aims at it, so the right stick drives a virtual mouse.
  // A finger on the game also moves it, so the hero turns towards where you touch.

  const cursor = { x: innerWidth / 2, y: innerHeight / 2, el: null, shown: false };

  function pointer(type, extra) {
    const c = canvas();
    if (!c) return;
    c.dispatchEvent(new PointerEvent(type, {
      pointerId: 1, pointerType: 'mouse', isPrimary: true,
      clientX: cursor.x, clientY: cursor.y, bubbles: true, cancelable: true, composed: true,
      ...extra,
    }));
  }

  function moveCursor(x, y, show) {
    cursor.x = Math.max(0, Math.min(innerWidth - 1, x));
    cursor.y = Math.max(0, Math.min(innerHeight - 1, y));
    pointer('pointermove', { buttons: clicking ? 1 : 0 });
    if (cursor.el) {
      cursor.el.style.transform = `translate(${cursor.x}px, ${cursor.y}px)`;
      if (show !== undefined && show !== cursor.shown) {
        cursor.shown = show;
        cursor.el.hidden = !show;
      }
    }
  }

  let clicking = false;
  function click(isDown) {
    if (isDown === clicking) return;
    clicking = isDown;
    pointer(isDown ? 'pointerdown' : 'pointerup', { button: 0, buttons: isDown ? 1 : 0 });
  }

  function wheel(deltaY) {
    const c = canvas();
    if (!c) return;
    c.dispatchEvent(new WheelEvent('wheel', {
      deltaY, deltaMode: 0, clientX: cursor.x, clientY: cursor.y, bubbles: true, cancelable: true,
    }));
  }

  // ── Controller ──
  //
  // The app sends button edges as `pad(name, down)` and stick moves as `axes(lx, ly, rx, ry)`.

  const pad = { lx: 0, ly: 0, rx: 0, ry: 0, zoom: 0, last: 0 };
  const STICK_DEAD = 0.35;
  const AIM_DEAD = 0.15;

  // Shown in the menu's "Controller" panel; keep in step with `button()` below.
  const LAYOUT = [
    ['Left stick', 'Move'],
    ['Right stick', 'Aim / cursor'],
    ['A', 'Click'],
    ['B', 'Pause / back (Esc)'],
    ['X', 'Jump'],
    ['Y', 'Movement spell (F)'],
    ['D-pad ← ↑ →', 'Level-up card 1 / 2 / 3'],
    ['D-pad ↓', 'Free / lock the camera (C)'],
    ['L1 / R1', 'Zoom out / in'],
    ['Start', 'Enter World / play again'],
    ['Select, Back', 'This menu'],
  ];

  function button(name, isDown) {
    usingPad();
    if (menu.open) {
      if (isDown) menuButton(name);
      return;
    }
    switch (name) {
      case 'A': click(isDown); break;
      case 'B': if (isDown) tap('Escape'); break;
      case 'X': hold('pad', 'Space', isDown); break;
      case 'Y': hold('pad', 'KeyF', isDown); break;
      case 'LEFT': if (isDown) tap('Digit1'); break;
      case 'UP': if (isDown) tap('Digit2'); break;
      case 'RIGHT': if (isDown) tap('Digit3'); break;
      case 'DOWN': if (isDown) tap('KeyC'); break;
      case 'L1': pad.zoom = isDown ? 1 : (pad.zoom === 1 ? 0 : pad.zoom); break;
      case 'R1': pad.zoom = isDown ? -1 : (pad.zoom === -1 ? 0 : pad.zoom); break;
      case 'START': if (isDown) tap('Enter'); break;
      case 'SELECT': if (isDown) toggleMenu(); break;
    }
  }

  function axes(lx, ly, rx, ry) {
    if (Math.hypot(lx, ly) > STICK_DEAD || Math.hypot(rx, ry) > AIM_DEAD) usingPad();
    Object.assign(pad, { lx, ly, rx, ry });
    if (menu.open) {
      // The stick steps through the menu like the D-pad.
      const dir = ly < -0.6 ? 'UP' : ly > 0.6 ? 'DOWN' : '';
      if (dir && dir !== pad.menuDir) menuButton(dir);
      pad.menuDir = dir;
    }
  }

  let lastFrame = performance.now();
  let zoomAcc = 0;
  function frame(t) {
    const dt = Math.min(0.05, (t - lastFrame) / 1000);
    lastFrame = t;
    if (!menu.open) {
      const codes = [];
      if (Math.hypot(pad.lx, pad.ly) > STICK_DEAD) {
        // 8-way: a diagonal holds both keys, as WASD does.
        const a = Math.atan2(pad.ly, pad.lx);
        const cx = Math.cos(a), cy = Math.sin(a);
        if (cx < -0.38) codes.push('KeyA');
        if (cx > 0.38) codes.push('KeyD');
        if (cy < -0.38) codes.push('KeyW');
        if (cy > 0.38) codes.push('KeyS');
      }
      setHeld('stick', codes);
      const m = Math.hypot(pad.rx, pad.ry);
      if (m > AIM_DEAD) {
        // Squared response: fine aim near the centre, quick sweeps at full tilt.
        const speed = Math.min(innerWidth, innerHeight) * 1.6 * ((m - AIM_DEAD) / (1 - AIM_DEAD)) ** 2;
        moveCursor(cursor.x + (pad.rx / m) * speed * dt, cursor.y + (pad.ry / m) * speed * dt, true);
      }
      if (pad.zoom) {
        zoomAcc += dt;
        if (zoomAcc > 0.06) {
          wheel(pad.zoom * 60);
          zoomAcc = 0;
        }
      }
    }
    fpsFrame(t);
    requestAnimationFrame(frame);
  }

  // ── Touch controls ──

  let touchShown = false;
  let touchRoot = null;

  function updateTouchVisibility(seenTouch) {
    let want;
    if (settings.touch === 'on') want = true;
    else if (settings.touch === 'off') want = false;
    else want = seenTouch === undefined ? touchShown : seenTouch;
    touchShown = want;
    if (touchRoot) touchRoot.hidden = !want;
    if (!want) setHeld('touch', []);
  }

  function usingPad() {
    pad.last = performance.now();
    if (settings.touch === 'auto' && touchShown) updateTouchVisibility(false);
  }

  function buildTouch() {
    touchRoot = el('div', 'sa-touch');
    const stick = el('div', 'sa-stick');
    const knob = el('div', 'sa-knob');
    stick.append(knob);
    let stickId = null;
    const stickMove = (e) => {
      const r = stick.getBoundingClientRect();
      const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      let dx = (e.clientX - cx) / (r.width / 2), dy = (e.clientY - cy) / (r.height / 2);
      const m = Math.hypot(dx, dy);
      if (m > 1) { dx /= m; dy /= m; }
      knob.style.transform = `translate(${dx * 50}%, ${dy * 50}%)`;
      const codes = [];
      if (Math.hypot(dx, dy) > 0.3) {
        const a = Math.atan2(dy, dx);
        if (Math.cos(a) < -0.38) codes.push('KeyA');
        if (Math.cos(a) > 0.38) codes.push('KeyD');
        if (Math.sin(a) < -0.38) codes.push('KeyW');
        if (Math.sin(a) > 0.38) codes.push('KeyS');
      }
      setHeld('touch', codes);
    };
    stick.addEventListener('pointerdown', (e) => {
      stickId = e.pointerId;
      stick.setPointerCapture(e.pointerId);
      stickMove(e);
      e.preventDefault();
    });
    stick.addEventListener('pointermove', (e) => { if (e.pointerId === stickId) stickMove(e); });
    const stickEnd = (e) => {
      if (e.pointerId !== stickId) return;
      stickId = null;
      knob.style.transform = '';
      setHeld('touch', []);
    };
    stick.addEventListener('pointerup', stickEnd);
    stick.addEventListener('pointercancel', stickEnd);

    const pads = el('div', 'sa-buttons');
    const btn = (label, cls, onDown, onUp) => {
      const b = el('button', 'sa-btn ' + cls);
      b.textContent = label;
      b.addEventListener('pointerdown', (e) => { b.classList.add('down'); onDown(); e.preventDefault(); });
      const up = () => { if (b.classList.contains('down')) { b.classList.remove('down'); onUp && onUp(); } };
      b.addEventListener('pointerup', up);
      b.addEventListener('pointercancel', up);
      b.addEventListener('pointerleave', up);
      return b;
    };
    pads.append(
      btn('Jump', 'sa-jump', () => hold('touchbtn', 'Space', true), () => hold('touchbtn', 'Space', false)),
      btn('F', 'sa-spell', () => hold('touchbtn', 'KeyF', true), () => hold('touchbtn', 'KeyF', false)),
    );
    const top = el('div', 'sa-top');
    top.append(
      btn('−', 'sa-small', () => wheel(240)),
      btn('+', 'sa-small', () => wheel(-240)),
      btn('❚❚', 'sa-small', () => tap('Escape')),
      btn('☰', 'sa-small', () => toggleMenu()),
    );
    touchRoot.append(stick, pads, top);
    touchRoot.hidden = true;
    document.body.append(touchRoot);

    // A finger on the game itself also aims: the hero turns to face it.
    addEventListener('pointerdown', (e) => {
      if (e.pointerType !== 'touch') return;
      if (settings.touch === 'auto' && !touchShown) updateTouchVisibility(true);
      if (e.target === canvas()) moveCursor(e.clientX, e.clientY, false);
    }, true);
    addEventListener('pointermove', (e) => {
      if (e.pointerType === 'touch' && e.target === canvas()) moveCursor(e.clientX, e.clientY, false);
    }, true);
  }

  // ── Menu ──

  const menu = { open: false, root: null, items: [], focus: 0, help: null };

  function menuEntries() {
    const pct = Math.round(settings.scale * 100);
    const list = [
      ['Resume', () => toggleMenu(false)],
      ['Pause / game menu (Esc)', () => { toggleMenu(false); tap('Escape'); }],
      [`Touch controls: ${settings.touch}`, () => {
        settings.touch = { auto: 'on', on: 'off', off: 'auto' }[settings.touch] || 'auto';
        save();
        updateTouchVisibility(settings.touch === 'on');
      }],
      [`FPS counter: ${settings.fps ? 'on' : 'off'}`, () => {
        settings.fps = !settings.fps;
        save();
        applyFps();
      }],
      [`Render scale: ${pct}%`, () => {
        const steps = [1, 0.85, 0.7, 0.5];
        settings.scale = steps[(steps.indexOf(settings.scale) + 1) % steps.length];
        save();
        applyScale();
      }],
      ['Controller layout', () => { menu.help.hidden = !menu.help.hidden; }],
      ['Reload', () => location.reload()],
    ];
    if (host && host.setup) list.push(['Game folder and setup…', () => host.setup()]);
    if (host && host.exit) list.push(['Exit', () => host.exit()]);
    return list;
  }

  function buildMenu() {
    menu.root = el('div', 'sa-menu');
    menu.root.hidden = true;
    document.body.append(menu.root);
  }

  function renderMenu() {
    const panel = el('div', 'sa-panel');
    const title = el('div', 'sa-title');
    title.textContent = 'Azeroth Survivors';
    panel.append(title);
    menu.items = menuEntries().map(([label, act], i) => {
      const b = el('button', 'sa-item');
      b.textContent = label;
      b.addEventListener('click', () => { act(); if (menu.open) renderMenu(); });
      b.addEventListener('pointerenter', () => setFocus(i));
      panel.append(b);
      return b;
    });
    const help = el('table', 'sa-help');
    for (const [k, v] of LAYOUT) {
      const tr = el('tr');
      const a = el('td');
      a.textContent = k;
      const b = el('td');
      b.textContent = v;
      tr.append(a, b);
      help.append(tr);
    }
    help.hidden = menu.help ? menu.help.hidden : true;
    menu.help = help;
    panel.append(help);
    if (host && host.version) {
      const v = el('div', 'sa-version');
      v.textContent = host.version();
      panel.append(v);
    }
    menu.root.replaceChildren(panel);
    setFocus(Math.min(menu.focus, menu.items.length - 1));
  }

  function setFocus(i) {
    menu.focus = i;
    menu.items.forEach((b, j) => b.classList.toggle('focus', j === i));
  }

  function menuButton(name) {
    const n = menu.items.length;
    if (name === 'UP') setFocus((menu.focus + n - 1) % n);
    else if (name === 'DOWN') setFocus((menu.focus + 1) % n);
    else if (name === 'A') menu.items[menu.focus].click();
    else if (name === 'B' || name === 'SELECT') toggleMenu(false);
  }

  function toggleMenu(open = !menu.open) {
    if (open === menu.open) return;
    menu.open = open;
    if (open) {
      releaseAll();
      click(false);
      renderMenu();
    }
    menu.root.hidden = !open;
  }

  // ── FPS and render scale ──

  let fpsEl = null, fpsCount = 0, fpsSince = performance.now();
  function fpsFrame(t) {
    if (!settings.fps || !fpsEl) return;
    fpsCount++;
    if (t - fpsSince >= 500) {
      fpsEl.textContent = `${Math.round((fpsCount * 1000) / (t - fpsSince))} fps`;
      fpsCount = 0;
      fpsSince = t;
    }
  }
  function applyFps() {
    if (fpsEl) fpsEl.hidden = !settings.fps;
  }

  // Below 100 %, the canvas is laid out smaller and stretched back up with a CSS transform, so
  // the GPU draws fewer pixels. The game sizes itself from the canvas, and pointer positions are
  // mapped through the transform by the browser.
  function applyScale() {
    const c = canvas();
    if (!c) return;
    const s = settings.scale;
    if (s >= 1) {
      c.style.width = c.style.height = c.style.transform = c.style.transformOrigin = '';
    } else {
      c.style.width = `${100 * s}vw`;
      c.style.height = `${100 * s}vh`;
      c.style.transformOrigin = '0 0';
      c.style.transform = `scale(${1 / s})`;
    }
  }

  // ── Setup ──

  function el(tag, cls) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    return e;
  }

  function start() {
    cursor.el = el('div', 'sa-cursor');
    cursor.el.hidden = true;
    fpsEl = el('div', 'sa-fps');
    document.body.append(cursor.el, fpsEl);
    applyFps();
    applyScale();
    buildTouch();
    buildMenu();
    updateTouchVisibility(settings.touch === 'on');
    // A held key must not stay down when the app goes to the background.
    addEventListener('blur', releaseAll);
    document.addEventListener('visibilitychange', () => { if (document.hidden) releaseAll(); });
    requestAnimationFrame(frame);
  }

  window.survivorsAndroid = {
    pad: button,
    axes,
    /** Back from the system (gesture or button): close the menu, else open it. */
    back: () => toggleMenu(),
    menu: toggleMenu,
    /** Put the virtual cursor somewhere, in CSS pixels (for scripts and tests). */
    cursorTo: (x, y) => moveCursor(x, y, true),
    settings: () => ({ ...settings }),
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
