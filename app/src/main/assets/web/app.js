/* EFK Music arayüzü. Yerel taraf `window.EFKNative` üzerinden çağrılır,
   yerelden gelen olaylar `window.efk.onEvent(type, data)` ile ulaşır. */
(() => {
  'use strict';

  const N = window.EFKNative || null;
  const $ = (s, r = document) => r.querySelector(s);
  const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ESC[c]);
  const ACTIVE = ['queued', 'running', 'converting'];

  const state = {
    library: [], downloads: [], engine: {}, settings: { quality: '192', autoUpdate: true },
    player: { id: '', queue: [], playing: false, pos: 0, dur: 0, shuffle: false, repeat: 0 },
    app: {}, appUpdate: null,
    tab: 'home', playerOpen: false, ptab: 'song', sheet: null,
    search: { q: '', results: null, loading: false, error: '', reqId: '' },
    lib: { filter: 'all', sort: 'recent', q: '' },
  };
  let libById = new Map();
  const ctxLists = {};
  const colorCache = {};
  let seeking = false;
  let lastPlayerKey = '';

  function call(name, ...args) {
    if (!N || typeof N[name] !== 'function') return undefined;
    try { return N[name](...args); } catch (e) { console.error(name, e); return undefined; }
  }

  // ---------- Biçimlendirme ----------
  const pad = (n) => String(n).padStart(2, '0');
  function fmt(sec) {
    sec = Math.max(0, Math.round(sec || 0));
    const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    return h ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
  }
  const fmtMs = (ms) => fmt((ms || 0) / 1000);
  function fmtSize(bytes) {
    if (!bytes) return '0 MB';
    const mb = bytes / 1048576;
    return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb.toFixed(mb < 10 ? 1 : 0)} MB`;
  }
  const isUrl = (s) => /^https?:\/\/|(?:^|\s)(?:www\.|m\.)?(?:youtube\.com|youtu\.be|music\.youtube\.com)\//i.test(s.trim());
  function videoIdFrom(url) {
    const m = /(?:[?&]v=|youtu\.be\/|\/shorts\/|\/embed\/|\/live\/)([A-Za-z0-9_-]{11})/.exec(url || '');
    return m ? m[1] : null;
  }
  function hashStr(s) { let h = 2166136261; for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }

  // ---------- HTML parçaları ----------
  function icon(id, attrs = '') { return `<svg ${attrs}><use href="#${id}"/></svg>`; }
  function artHTML(src, extra = '') {
    return `<div class="art"><div class="ph">${icon('i-note')}</div>${src ? `<img src="${esc(src)}" alt="" loading="lazy" onerror="this.remove()">` : ''}${extra}</div>`;
  }
  function eqHTML() { return `<div class="eq ${state.player.playing ? '' : 'paused'}"><i></i><i></i><i></i></div>`; }

  function trackRow(t, ctx) {
    const playing = state.player.id === t.id;
    const sub = [t.artist || 'Bilinmeyen sanatçı', fmt(t.duration)].filter(Boolean).join(' • ');
    return `<div class="row ${playing ? 'playing' : ''}" data-action="play" data-id="${esc(t.id)}" data-ctx="${ctx}">
      ${artHTML(t.cover, playing ? eqHTML() : '')}
      <div class="meta"><div class="t1">${esc(t.title)}</div><div class="t2">${t.fav ? '<span class="fav-dot">♥</span> ' : ''}${esc(sub)}</div></div>
      <div class="actions"><button class="icon-btn" data-action="track-menu" data-id="${esc(t.id)}" aria-label="Seçenekler">${icon('i-more')}</button></div>
    </div>`;
  }

  function cardHTML(t, ctx) {
    const playing = state.player.id === t.id;
    return `<div class="card" data-action="play" data-id="${esc(t.id)}" data-ctx="${ctx}">
      ${artHTML(t.cover, playing ? eqHTML() : '')}
      <div class="t1">${esc(t.title)}</div><div class="t2">${esc(t.artist || 'Bilinmeyen sanatçı')}</div>
    </div>`;
  }

  function emptyHTML(iconId, title, text, extra = '') {
    return `<div class="empty"><div class="big">${icon(iconId)}</div><h3>${esc(title)}</h3><p>${text}</p>${extra}</div>`;
  }

  function setCtx(name, list) { ctxLists[name] = list.map((t) => t.id); return name; }

  // ---------- Sekmeler ----------
  const view = $('#view');

  function setTab(tab) {
    if (tab === 'player') { openPlayer(); return; }
    if (!['home', 'search', 'downloads', 'library'].includes(tab)) tab = 'home';
    const changed = state.tab !== tab;
    state.tab = tab;
    document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
    mount();
    if (changed) view.scrollTop = 0;
    if (tab === 'search' && !state.search.q) setTimeout(() => $('#q')?.focus(), 60);
  }

  function mount() {
    if (state.tab === 'home') mountHome();
    else if (state.tab === 'search') mountSearch();
    else if (state.tab === 'downloads') mountDownloads();
    else mountLibrary();
  }

  function refresh() {
    if (state.tab === 'home') fillHome();
    else if (state.tab === 'search') fillSearch();
    else if (state.tab === 'downloads') fillDownloads();
    else fillLibrary();
  }

  // ---------- Ana sayfa ----------
  function mountHome() {
    view.innerHTML = `<div class="page">
      <div class="chips">
        <button class="chip brand" data-action="shuffle-all">${icon('i-shuffle')} Karışık çal</button>
        <button class="chip" data-action="lib-filter" data-filter="fav">Beğenilenler</button>
        <button class="chip" data-action="lib-filter" data-filter="top">Sık dinlenenler</button>
        <button class="chip" data-action="lib-filter" data-filter="all">Tüm şarkılar</button>
      </div>
      <div id="update-slot"></div>
      <div class="hero">
        <h2>YouTube'dan MP3</h2>
        <p>Linki yapıştır, şarkı MP3 olarak telefonuna insin. Sonra internetsiz dinle.</p>
        <form class="url-row" id="url-form" autocomplete="off">
          <label class="url-wrap">${icon('i-link')}<input id="url" class="url-input" type="url" inputmode="url" placeholder="https://youtu.be/…" enterkeyhint="go"></label>
          <button class="btn btn-primary" type="submit">${icon('i-download')} MP3</button>
        </form>
        <div class="hero-actions">
          <button class="chip" data-action="paste-download">${icon('i-paste')} Panodan yapıştır ve indir</button>
        </div>
      </div>
      <div id="home-sections"></div>
    </div>`;
    $('#url-form').addEventListener('submit', (e) => {
      e.preventDefault();
      const input = $('#url');
      startDownload(input.value);
      input.value = '';
      input.blur();
    });
    fillHome();
  }

  function fillHome() {
    const slot = $('#update-slot');
    if (slot) {
      const u = state.appUpdate;
      slot.innerHTML = u && u.available ? `<div class="banner">${icon('i-update')}<div class="meta"><b>Yeni sürüm hazır</b><div class="t2">${esc(u.version)} · güncellemek için indir ve kur</div></div><button class="btn btn-white" data-action="open-url" data-url="${esc(u.url)}">İndir</button></div>` : '';
    }
    const box = $('#home-sections');
    if (!box) return;
    const lib = state.library;
    if (!lib.length) {
      box.innerHTML = emptyHTML('i-library', 'Kitaplığın henüz boş',
        'Yukarıya bir YouTube linki yapıştır ya da <b>Keşfet</b> sekmesinden şarkı arayıp indir. YouTube uygulamasında <b>Paylaş → EFK Music</b> de çalışır.',
        `<div style="margin-top:16px"><button class="btn btn-primary" data-action="tab" data-tab="search">${icon('i-search')} Şarkı ara</button></div>`);
      return;
    }
    const day = Math.floor(Date.now() / 86400000);
    const played = lib.filter((t) => t.lastPlayed).sort((a, b) => b.lastPlayed - a.lastPlayed).slice(0, 12);
    const newest = [...lib].sort((a, b) => b.addedAt - a.addedAt).slice(0, 12);
    const quick = [...lib]
      .map((t) => ({ t, k: (t.plays || 0) * 3 + (t.fav ? 6 : 0) + (hashStr(t.id + day) % 10) }))
      .sort((a, b) => b.k - a.k).slice(0, 20).map((x) => x.t);
    const favs = lib.filter((t) => t.fav).slice(0, 12);

    let html = '';
    const cards = (name, list) => {
      setCtx(name, list);
      return `<div class="carousel">${list.map((t) => cardHTML(t, name)).join('')}</div>`;
    };
    if (played.length) html += section('Tekrar dinle', 'Son çaldıkların', cards('played', played));
    setCtx('quick', quick);
    html += section('Hızlı seçimler', 'Sana özel karışım',
      `<div class="quick">${quick.map((t) => trackRow(t, 'quick')).join('')}</div>`,
      `<button class="pill-btn" data-action="play-ctx" data-ctx="quick">${icon('i-play')} Hepsini çal</button>`);
    html += section('Yeni eklenenler', 'İndirdiklerin', cards('newest', newest),
      `<button class="pill-btn" data-action="tab" data-tab="library">Tümü</button>`);
    if (favs.length) html += section('Beğendiklerin', 'Favoriler', cards('favs', favs));
    box.innerHTML = html;
  }

  function section(title, kicker, body, action = '') {
    return `<section class="section"><div class="section-head"><div><div class="section-kicker">${esc(kicker)}</div><div class="section-title">${esc(title)}</div></div>${action}</div>${body}</section>`;
  }

  // ---------- Keşfet / Arama ----------
  function recentSearches() {
    try { return JSON.parse(localStorage.getItem('efk.recent') || '[]'); } catch (e) { return []; }
  }
  function saveRecent(q) {
    try {
      const list = [q, ...recentSearches().filter((x) => x.toLowerCase() !== q.toLowerCase())].slice(0, 8);
      localStorage.setItem('efk.recent', JSON.stringify(list));
    } catch (e) { /* tarayıcı depolaması yoksa geçmiş tutulmaz */ }
  }

  function mountSearch() {
    view.innerHTML = `<div class="page">
      <div class="search-bar"><form class="search-field" id="search-form" autocomplete="off">
        ${icon('i-search')}
        <input id="q" type="search" placeholder="Şarkı, sanatçı ara ya da link yapıştır" enterkeyhint="search" value="${esc(state.search.q)}">
        <button type="button" class="icon-btn" data-action="clear-search" aria-label="Temizle" ${state.search.q ? '' : 'hidden'}>${icon('i-close')}</button>
      </form></div>
      <div id="search-body"></div>
    </div>`;
    const input = $('#q');
    $('#search-form').addEventListener('submit', (e) => { e.preventDefault(); runSearch(input.value); input.blur(); });
    input.addEventListener('input', () => {
      $('[data-action="clear-search"]').hidden = !input.value;
      if (!input.value) { state.search = { q: '', results: null, loading: false, error: '', reqId: '' }; fillSearch(); }
    });
    fillSearch();
  }

  function runSearch(q) {
    q = (q || '').trim();
    if (!q) return;
    state.search.q = q;
    if (isUrl(q)) { state.search.results = null; state.search.loading = false; state.search.error = ''; fillSearch(); return; }
    saveRecent(q);
    const reqId = 'r' + Date.now();
    Object.assign(state.search, { reqId, loading: true, error: '', results: null });
    fillSearch();
    if (N) call('search', reqId, q);
    else setTimeout(() => window.efk.onEvent('search', { reqId, error: 'Önizleme modunda arama yapılamaz' }), 400);
  }

  function dlStatus(videoId) {
    if (!videoId) return { s: 'none' };
    if (libById.has(videoId)) return { s: 'done' };
    const j = state.downloads.find((d) => d.videoId === videoId && ACTIVE.includes(d.status));
    if (j) return { s: j.status, p: j.progress };
    const e = state.downloads.find((d) => d.videoId === videoId && d.status === 'error');
    return e ? { s: 'error' } : { s: 'none' };
  }

  function dlButton(videoId) {
    const st = dlStatus(videoId);
    if (st.s === 'done') return `<span class="dl-btn done" aria-label="İndirildi">${icon('i-check')}</span>`;
    if (ACTIVE.includes(st.s)) {
      const c = 2 * Math.PI * 12;
      const p = st.s === 'running' ? Math.max(4, st.p || 0) : 25;
      const spin = st.s !== 'running' || !st.p;
      return `<span class="dl-btn" aria-label="İndiriliyor"><svg class="ring ${spin ? 'spin' : ''}" viewBox="0 0 30 30"><circle class="bgc" cx="15" cy="15" r="12"/><circle class="fgc" cx="15" cy="15" r="12" stroke-dasharray="${c}" stroke-dashoffset="${c * (1 - p / 100)}"/></svg></span>`;
    }
    return `<button class="dl-btn" data-action="dl-result" data-vid="${esc(videoId)}" aria-label="MP3 indir">${icon(st.s === 'error' ? 'i-refresh' : 'i-download')}</button>`;
  }

  function fillSearch() {
    const box = $('#search-body');
    if (!box) return;
    const s = state.search;
    if (s.q && isUrl(s.q)) {
      const vid = videoIdFrom(s.q);
      box.innerHTML = `<div class="link-card">${artHTML(vid ? `https://i.ytimg.com/vi/${vid}/mqdefault.jpg` : '')}
        <div class="meta"><div class="t1">YouTube bağlantısı</div><div class="t2">${esc(s.q)}</div></div>
        <button class="btn btn-primary" data-action="dl-link">${icon('i-download')} MP3</button></div>
        <p class="empty" style="padding-top:8px">Oynatma listesi linki yapıştırırsan listedeki bütün şarkılar sırayla iner.</p>`;
      return;
    }
    if (s.loading) {
      box.innerHTML = `<div class="list skeleton">${Array.from({ length: 7 }, () => `<div class="row"><div class="art"></div><div class="meta"><div class="bar" style="width:70%"></div><div class="bar" style="width:40%"></div></div></div>`).join('')}</div>`;
      return;
    }
    if (s.error) {
      box.innerHTML = emptyHTML('i-error', 'Arama yapılamadı', esc(s.error),
        `<div style="margin-top:14px"><button class="btn btn-ghost" data-action="retry-search">${icon('i-refresh')} Tekrar dene</button></div>`);
      return;
    }
    if (s.results) {
      if (!s.results.length) { box.innerHTML = emptyHTML('i-search', 'Sonuç yok', 'Başka kelimelerle aramayı dene.'); return; }
      box.innerHTML = `<div class="section-head" style="padding-top:4px"><div><div class="section-kicker">YouTube</div><div class="section-title">Şarkılar</div></div></div>
        <div class="list">${s.results.map((r) => {
          const inLib = libById.get(r.videoId);
          const sub = [r.channel, r.duration ? fmt(r.duration) : ''].filter(Boolean).join(' • ');
          return `<div class="row ${state.player.id === r.videoId ? 'playing' : ''}" data-action="result" data-vid="${esc(r.videoId)}">
            ${artHTML(inLib?.cover || r.thumb)}
            <div class="meta"><div class="t1">${esc(r.title)}</div><div class="t2">${esc(sub)}</div></div>
            <div class="actions">${dlButton(r.videoId)}</div></div>`;
        }).join('')}</div>`;
      return;
    }
    const recent = recentSearches();
    const engineNote = state.engine.ready ? '' : `<div class="banner">${icon('i-update')}<div class="meta">İndirme motoru hazırlanıyor… İlk açılışta yarım dakika sürebilir.</div></div>`;
    box.innerHTML = engineNote + (recent.length
      ? `<div class="section-head" style="padding-top:12px"><div><div class="section-title" style="font-size:18px">Son aramalar</div></div><button class="pill-btn" data-action="clear-recent">Temizle</button></div>
         <div class="recent">${recent.map((q) => `<div class="row" data-action="recent" data-q="${esc(q)}">${icon('i-search')}<div class="meta"><div class="t1">${esc(q)}</div></div></div>`).join('')}</div>`
      : emptyHTML('i-explore', 'YouTube\'da ara', 'Şarkı ya da sanatçı adını yaz. Bulduğun şarkının yanındaki indirme düğmesine dokun; MP3 olarak kitaplığına eklensin.'));
  }

  // ---------- İndirmeler ----------
  function mountDownloads() {
    view.innerHTML = `<div class="page"><div class="lib-head"><h1>İndirmeler</h1></div><div id="engine-slot"></div><div id="dl-body"></div></div>`;
    fillDownloads();
  }

  function engineCard() {
    const e = state.engine;
    let dot = '', t2 = '';
    if (e.error) { dot = 'err'; t2 = 'Başlatılamadı: ' + e.error; }
    else if (!e.ready) { dot = 'wait'; t2 = 'Hazırlanıyor… (ilk açılışta 10–30 sn)'; }
    else if (e.updating) { dot = 'wait'; t2 = 'Güncelleniyor…'; }
    else t2 = `yt-dlp ${e.version || 'dahili sürüm'} · Hazır`;
    return `<div class="engine-card"><span class="dot ${dot}"></span><div class="meta"><div class="t1">İndirme motoru</div><div class="t2">${esc(t2)}</div></div>
      <button class="small-btn" data-action="update-engine" ${e.updating || !e.ready ? 'disabled' : ''}>${e.updating ? '…' : 'Güncelle'}</button></div>`;
  }

  function jobRow(j) {
    const active = ACTIVE.includes(j.status);
    let status = '', bar = '';
    if (j.status === 'queued') status = j.note || 'Sırada bekliyor';
    else if (j.status === 'running') {
      const p = Math.floor(j.progress || 0);
      status = j.note || (p > 0 ? `İndiriliyor %${p}${j.eta > 0 ? ` • ${fmt(j.eta)} kaldı` : ''}` : 'Bağlanıyor…');
    } else if (j.status === 'converting') status = j.note || 'MP3\'e dönüştürülüyor…';
    else if (j.status === 'done') status = j.note || 'Kitaplığa eklendi';
    else if (j.status === 'canceled') status = 'İptal edildi';
    else if (j.status === 'error') status = j.error || 'Hata';
    if (active) {
      const indet = j.status !== 'running' || !(j.progress > 0);
      bar = `<div class="bar-track"><div class="bar-fill ${indet ? 'indet' : ''}" style="width:${Math.max(2, j.progress || 0)}%"></div></div>`;
    }
    const cls = j.status === 'error' ? 'err' : j.status === 'done' ? 'ok' : '';
    let actions = '';
    if (active) actions = `<button class="icon-btn" data-action="dl-cancel" data-job="${esc(j.id)}" aria-label="İptal">${icon('i-close')}</button>`;
    else if (j.status === 'done' && j.videoId && libById.has(j.videoId)) actions = `<button class="icon-btn" data-action="play-one" data-id="${esc(j.videoId)}" aria-label="Çal">${icon('i-play')}</button>`;
    else if (j.status === 'error' || j.status === 'canceled') actions = `<button class="icon-btn" data-action="dl-retry" data-job="${esc(j.id)}" aria-label="Tekrar dene">${icon('i-refresh')}</button><button class="icon-btn" data-action="dl-remove" data-job="${esc(j.id)}" aria-label="Kaldır">${icon('i-close')}</button>`;
    const sub = j.artist ? `<div class="t2">${esc(j.artist)}</div>` : '';
    return `<div class="job">${artHTML(j.thumb)}<div class="meta"><div class="t1">${esc(j.title || 'Bilgiler alınıyor…')}</div>${sub}
      <div class="status ${cls}">${cls === 'err' ? icon('i-error', 'style="width:16px;height:16px"') : ''}${cls === 'ok' ? icon('i-check', 'style="width:16px;height:16px"') : ''}<span>${esc(status)}</span></div>${bar}</div>
      <div class="actions">${actions}</div></div>`;
  }

  function fillDownloads() {
    const es = $('#engine-slot');
    if (es) es.innerHTML = engineCard();
    const box = $('#dl-body');
    if (!box) return;
    const active = state.downloads.filter((j) => ACTIVE.includes(j.status));
    const done = state.downloads.filter((j) => !ACTIVE.includes(j.status)).reverse();
    if (!active.length && !done.length) {
      box.innerHTML = emptyHTML('i-download', 'İndirme kuyruğu boş',
        'Ana sayfaya bir YouTube linki yapıştır ya da YouTube uygulamasında <b>Paylaş → EFK Music</b>\'e dokun. Şarkılar <b>Müzik / EFK Music</b> klasörüne MP3 olarak kaydedilir.');
      return;
    }
    let html = '';
    if (active.length) html += section(`${active.length} şarkı`, 'Devam eden', `<div class="list">${active.map(jobRow).join('')}</div>`);
    if (done.length) html += section('Tamamlananlar', 'Geçmiş', `<div class="list">${done.map(jobRow).join('')}</div>`, `<button class="pill-btn" data-action="dl-clear">Temizle</button>`);
    box.innerHTML = html;
  }

  // ---------- Kitaplık ----------
  const SORTS = { recent: 'Son eklenen', az: 'A–Z', artist: 'Sanatçı', plays: 'En çok dinlenen' };

  function mountLibrary() {
    view.innerHTML = `<div class="page">
      <div class="lib-head"><h1>Kitaplık</h1><div class="lib-stats" id="lib-stats"></div></div>
      <div class="lib-actions">
        <button class="btn btn-white" data-action="play-lib">${icon('i-play')} Tümünü çal</button>
        <button class="btn btn-ghost" data-action="shuffle-lib">${icon('i-shuffle')} Karıştır</button>
      </div>
      <div class="chips" id="lib-chips"></div>
      <div class="filter-row"><input id="lib-q" class="mini-search" type="search" placeholder="Kitaplıkta ara" value="${esc(state.lib.q)}"></div>
      <div id="lib-list" class="list" style="margin-top:6px"></div>
    </div>`;
    $('#lib-q').addEventListener('input', (e) => { state.lib.q = e.target.value; fillLibrary(); });
    fillLibrary();
  }

  function libraryView() {
    let list = state.library.slice();
    const f = state.lib.filter;
    if (f === 'fav') list = list.filter((t) => t.fav);
    if (f === 'top') list = list.filter((t) => t.plays > 0);
    const q = state.lib.q.trim().toLocaleLowerCase('tr');
    if (q) list = list.filter((t) => (t.title + ' ' + t.artist).toLocaleLowerCase('tr').includes(q));
    const by = f === 'top' ? 'plays' : state.lib.sort;
    const coll = new Intl.Collator('tr');
    if (by === 'recent') list.sort((a, b) => b.addedAt - a.addedAt);
    else if (by === 'az') list.sort((a, b) => coll.compare(a.title, b.title));
    else if (by === 'artist') list.sort((a, b) => coll.compare(a.artist || '~', b.artist || '~') || coll.compare(a.title, b.title));
    else if (by === 'plays') list.sort((a, b) => (b.plays || 0) - (a.plays || 0));
    return list;
  }

  function fillLibrary() {
    const stats = $('#lib-stats');
    if (!stats) return;
    const total = state.library.reduce((s, t) => s + (t.size || 0), 0);
    const secs = state.library.reduce((s, t) => s + (t.duration || 0), 0);
    stats.innerHTML = state.library.length
      ? `${icon('i-offline')} ${state.library.length} şarkı • ${fmt(secs)} • ${fmtSize(total)} • tamamı çevrimdışı`
      : 'Henüz şarkı yok';
    const f = state.lib.filter;
    $('#lib-chips').innerHTML = [['all', 'Hepsi'], ['fav', 'Beğenilenler'], ['top', 'Sık dinlenenler']]
      .map(([k, l]) => `<button class="chip ${f === k ? 'active' : ''}" data-action="lib-filter" data-filter="${k}">${l}</button>`).join('')
      + `<button class="chip" data-action="lib-sort">${icon('i-sort')} ${SORTS[state.lib.sort]}</button>`;
    const list = libraryView();
    setCtx('lib', list);
    const box = $('#lib-list');
    if (!state.library.length) {
      box.innerHTML = emptyHTML('i-library', 'Burada indirdiğin şarkılar olacak', 'Keşfet sekmesinden arayıp indir ya da ana sayfaya link yapıştır.');
    } else if (!list.length) {
      box.innerHTML = emptyHTML('i-search', 'Eşleşen şarkı yok', f === 'fav' ? 'Bir şarkıyı beğenmek için oynatıcıdaki kalbe dokun.' : 'Aramayı ya da filtreyi değiştir.');
    } else {
      box.innerHTML = list.map((t) => trackRow(t, 'lib')).join('');
    }
  }

  // ---------- Oynatıcı ----------
  const mini = $('#mini'), playerEl = $('#player'), seek = $('#seek');

  function currentTrack() { return libById.get(state.player.id) || null; }

  function updatePlayerUI() {
    const t = currentTrack();
    const p = state.player;
    $('#app').classList.toggle('has-mini', !!t);
    mini.hidden = !t;
    if (!t) { if (state.playerOpen) closePlayer(); return; }
    const playIcon = p.playing ? '#i-pause' : '#i-play';
    $('#mini-title').textContent = t.title;
    $('#mini-artist').textContent = t.artist || 'Bilinmeyen sanatçı';
    setImg($('#mini-art'), t.cover);
    $('#mini-play use').setAttribute('href', playIcon);
    $('#p-play use').setAttribute('href', playIcon);
    $('#p-title').textContent = t.title;
    $('#p-artist').textContent = t.artist || 'Bilinmeyen sanatçı';
    const art = $('#p-art');
    setImg(art, t.cover);
    $('#p-art-fallback').hidden = !!t.cover;
    $('#p-fav use').setAttribute('href', t.fav ? '#i-heart' : '#i-heart-o');
    $('[data-action="fav-current"]').classList.toggle('on', !!t.fav);
    $('[data-action="shuffle"]').classList.toggle('on', !!p.shuffle);
    $('[data-action="repeat"]').classList.toggle('on', p.repeat > 0);
    $('#p-repeat use').setAttribute('href', p.repeat === 1 ? '#i-repeat-one' : '#i-repeat');
    updatePosition();
    applyArtColor(t);
    if (state.playerOpen && state.ptab === 'queue') renderQueue();
  }

  function setImg(img, src) {
    if (!src) { img.removeAttribute('src'); img.style.visibility = 'hidden'; return; }
    if (img.getAttribute('src') !== src) img.src = src;
    img.style.visibility = '';
  }

  function updatePosition() {
    const p = state.player;
    const dur = p.dur || (currentTrack()?.duration || 0) * 1000;
    const pct = dur ? Math.min(100, (p.pos / dur) * 100) : 0;
    $('#mini-bar').style.width = pct + '%';
    if (!seeking) {
      seek.value = String(Math.round(pct * 10));
      seek.style.setProperty('--p', pct + '%');
      $('#p-pos').textContent = fmtMs(p.pos);
    }
    $('#p-dur').textContent = fmtMs(dur);
  }

  seek.addEventListener('input', () => {
    seeking = true;
    const dur = state.player.dur || (currentTrack()?.duration || 0) * 1000;
    seek.style.setProperty('--p', seek.value / 10 + '%');
    $('#p-pos').textContent = fmtMs((seek.value / 1000) * dur);
  });
  seek.addEventListener('change', () => {
    const dur = state.player.dur || (currentTrack()?.duration || 0) * 1000;
    const ms = Math.round((seek.value / 1000) * dur);
    state.player.pos = ms;
    call('player', 'seek', String(ms));
    seeking = false;
  });

  function applyArtColor(t) {
    const set = (c) => playerEl.style.setProperty('--pc', c);
    if (!t.cover) { set('#1b3f8f'); return; }
    if (colorCache[t.cover]) { set(colorCache[t.cover]); return; }
    const img = new Image();
    img.onload = () => {
      try {
        const c = document.createElement('canvas');
        c.width = c.height = 12;
        const g = c.getContext('2d');
        g.drawImage(img, 0, 0, 12, 12);
        const d = g.getImageData(0, 0, 12, 12).data;
        let r = 0, gr = 0, b = 0;
        for (let i = 0; i < d.length; i += 4) { r += d[i]; gr += d[i + 1]; b += d[i + 2]; }
        const n = d.length / 4;
        // Kapak rengini EFK mavisiyle harmanla ki tema her zaman mavi kalsın.
        const mix = (v, brand) => Math.round(v / n * 0.55 + brand * 0.45);
        const col = `rgb(${mix(r, 27)},${mix(gr, 63)},${mix(b, 160)})`;
        colorCache[t.cover] = col;
        if (currentTrack() === t) set(col);
      } catch (e) { set('#1b3f8f'); }
    };
    img.onerror = () => set('#1b3f8f');
    img.src = t.cover;
  }

  function openPlayer() {
    if (!currentTrack()) return;
    state.playerOpen = true;
    playerEl.classList.add('open');
    playerEl.setAttribute('aria-hidden', 'false');
    $('#app').classList.add('player-open');
    setPtab(state.ptab);
    updatePlayerUI();
  }
  function closePlayer() {
    state.playerOpen = false;
    playerEl.classList.remove('open');
    playerEl.setAttribute('aria-hidden', 'true');
    $('#app').classList.remove('player-open');
  }
  function setPtab(tab) {
    state.ptab = tab;
    document.querySelectorAll('.ptab').forEach((b) => b.classList.toggle('active', b.dataset.ptab === tab));
    $('#player-song').hidden = tab !== 'song';
    $('#player-queue').hidden = tab !== 'queue';
    if (tab === 'queue') renderQueue();
  }
  function renderQueue() {
    const q = state.player.queue || [];
    const box = $('#player-queue');
    if (!q.length) { box.innerHTML = emptyHTML('i-queue', 'Sıra boş', 'Kitaplıktan bir şarkı çal.'); return; }
    // Karıştırma açıksa sırayı oynatıcının gerçek çalma düzeninde göster.
    const order = Array.isArray(state.player.order) && state.player.order.length === q.length ? state.player.order : q.map((_, i) => i);
    box.innerHTML = `<div class="queue-head">${q.length} şarkı sırada${state.player.shuffle ? ' · karışık' : ''}</div>` + order.map((i) => {
      const id = q[i];
      const t = libById.get(id);
      if (!t) return '';
      const cur = i === state.player.index;
      return `<div class="row ${cur ? 'playing' : ''}" data-action="queue-index" data-index="${i}">${artHTML(t.cover, cur ? eqHTML() : '')}
        <div class="meta"><div class="t1">${esc(t.title)}</div><div class="t2">${esc(t.artist || 'Bilinmeyen sanatçı')} • ${fmt(t.duration)}</div></div>
        ${cur ? '' : `<button class="icon-btn" data-action="queue-remove" data-index="${i}" aria-label="Sıradan çıkar">${icon('i-close')}</button>`}</div>`;
    }).join('');
  }

  function playList(ids, index = 0, shuffle = false) {
    if (!ids || !ids.length) return;
    call('play', JSON.stringify(ids), index, shuffle);
  }

  // ---------- Alt sayfalar ----------
  const sheet = $('#sheet'), scrim = $('#scrim');
  function openSheet(html, kind) {
    state.sheet = kind;
    sheet.innerHTML = html;
    sheet.hidden = false;
    scrim.hidden = false;
  }
  function closeSheet() { state.sheet = null; sheet.hidden = true; scrim.hidden = true; }

  function trackMenu(id) {
    const t = libById.get(id);
    if (!t) return;
    openSheet(`<div class="sheet-head">${artHTML(t.cover)}<div class="meta"><div class="t1">${esc(t.title)}</div><div class="t2">${esc(t.artist || 'Bilinmeyen sanatçı')} • ${fmtSize(t.size)}</div></div></div>
      <button class="sheet-item" data-action="m-play" data-id="${esc(id)}">${icon('i-play')} Çal</button>
      <button class="sheet-item" data-action="m-next" data-id="${esc(id)}">${icon('i-play-next')} Sıradaki olarak çal</button>
      <button class="sheet-item" data-action="m-queue" data-id="${esc(id)}">${icon('i-queue')} Sıraya ekle</button>
      <button class="sheet-item" data-action="m-fav" data-id="${esc(id)}">${icon(t.fav ? 'i-heart' : 'i-heart-o')} ${t.fav ? 'Beğenilenlerden çıkar' : 'Beğen'}</button>
      <button class="sheet-item" data-action="m-share" data-id="${esc(id)}">${icon('i-share')} MP3'ü paylaş</button>
      <button class="sheet-item danger" data-action="m-delete" data-id="${esc(id)}">${icon('i-delete')} Telefondan sil</button>`, 'track');
  }

  function confirmDelete(id) {
    const t = libById.get(id);
    if (!t) return;
    openSheet(`<h3>Silinsin mi?</h3><p class="t2" style="margin:0 20px 12px;white-space:normal">"${esc(t.title)}" telefonundaki Müzik/EFK Music klasöründen silinecek. Tekrar dinlemek için yeniden indirmen gerekir.</p>
      <button class="sheet-item danger" data-action="m-delete-yes" data-id="${esc(id)}">${icon('i-delete')} Evet, sil</button>
      <button class="sheet-item" data-action="close-sheet">${icon('i-close')} Vazgeç</button>`, 'confirm');
  }

  function settingsSheet() {
    const s = state.settings, e = state.engine, a = state.app;
    const q = (v, label, hint) => `<button class="${s.quality === v ? 'active' : ''}" data-action="set-quality" data-v="${v}">${label}<small>${hint}</small></button>`;
    openSheet(`<h3>Ayarlar</h3>
      <div class="set-group"><div class="set-label">MP3 kalitesi</div>
        <div class="seg">${q('128', '128 kbps', '≈1 MB/dk')}${q('192', '192 kbps', '≈1,4 MB/dk')}${q('320', '320 kbps', '≈2,4 MB/dk')}</div>
        <div class="t2" style="white-space:normal;margin-top:8px">Düşük kalite hem daha az internet hem daha az yer harcar. 192 kbps çoğu kulaklık için yeterli.</div>
      </div>
      <div class="set-group">
        <div class="set-label">İndirme motoru</div>
        <div class="set-row"><div class="meta"><div class="t1">yt-dlp ${esc(e.version || '')}</div><div class="t2">YouTube değiştikçe motorun güncel kalması gerekir.</div></div>
          <button class="small-btn" data-action="update-engine">${e.updating ? 'Güncelleniyor…' : 'Şimdi güncelle'}</button></div>
        <div class="set-row" data-action="toggle-auto"><div class="meta"><div class="t1">Otomatik güncelle</div><div class="t2">Günde en fazla bir kez, uygulama açılırken</div></div><span class="switch ${s.autoUpdate ? 'on' : ''}"></span></div>
      </div>
      <div class="set-group">
        <div class="set-label">YouTube hesabı</div>
        <div class="set-row"><div class="meta"><div class="t1">${s.cookies ? 'Bağlı' : 'Bağlı değil'}</div><div class="t2">Yalnızca "bot değilim doğrulaması" hatası alırsan gerekir. Mümkünse yan hesap kullan.</div></div>
          ${s.cookies ? '<button class="small-btn" data-action="yt-logout">Çıkış</button>' : '<button class="small-btn" data-action="yt-login">Giriş yap</button>'}</div>
      </div>
      <div class="set-group">
        <div class="set-label">Depolama</div>
        <div class="set-row"><div class="meta"><div class="t1">Telefon › Müzik › EFK Music</div><div class="t2">MP3'ler burada durur; uygulamayı silsen bile kaybolmaz, başka müzik uygulamalarında da görünür.</div></div>
          <button class="small-btn" data-action="rescan">Tara</button></div>
      </div>
      <div class="set-group" style="border:0">
        <div class="set-label">Uygulama</div>
        <div class="set-row"><div class="meta"><div class="t1">EFK Music ${esc(a.version || '')}</div><div class="t2">EFK Studio · YouTube'dan MP3 indir, internetsiz dinle</div></div>
          <button class="small-btn" data-action="check-update">Güncelleme</button></div>
        <div class="t2" style="white-space:normal;margin-top:6px">Yalnızca hakkına sahip olduğun ya da kişisel kullanım için izin verilen içerikleri indir.</div>
      </div>`, 'settings');
  }

  // ---------- Bildirim balonu ----------
  let toastTimer = 0;
  function toast(msg) {
    const el = $('#toast');
    el.textContent = msg;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 2800);
  }

  // ---------- İşlemler ----------
  function startDownload(text, meta = {}) {
    let url = (text || '').trim();
    if (!url) { toast('Önce bir YouTube linki yapıştır'); return false; }
    if (!isUrl(url)) { toast('Bu bir YouTube linki gibi görünmüyor'); return false; }
    const m = /https?:\/\/\S+/i.exec(url);
    if (m) url = m[0];
    else url = 'https://' + url.replace(/^\s*\/+/, '');
    call('download', url, JSON.stringify(meta));
    if (!N) toast('Önizleme modu: indirme yalnızca telefonda çalışır');
    return true;
  }

  const actions = {
    'tab': (el) => { if (state.playerOpen) closePlayer(); setTab(el.dataset.tab); },
    'settings': () => settingsSheet(),
    'open-player': () => openPlayer(),
    'close-player': () => closePlayer(),
    'ptab': (el) => setPtab(el.dataset.ptab),
    'player-more': () => { const t = currentTrack(); if (t) trackMenu(t.id); },
    'toggle': () => call('player', 'toggle', ''),
    'next': () => call('player', 'next', ''),
    'prev': () => call('player', 'prev', ''),
    'shuffle': () => call('player', 'shuffle', ''),
    'repeat': () => call('player', 'repeat', ''),
    'fav-current': () => { const t = currentTrack(); if (t) call('toggleFav', t.id); },
    'queue-index': (el) => call('player', 'index', el.dataset.index),
    'queue-remove': (el) => call('player', 'remove', el.dataset.index),
    'play': (el) => {
      const ids = ctxLists[el.dataset.ctx] || [el.dataset.id];
      const idx = Math.max(0, ids.indexOf(el.dataset.id));
      if (state.player.id === el.dataset.id) { openPlayer(); return; }
      playList(ids, idx, false);
    },
    'play-one': (el) => playList([el.dataset.id], 0, false),
    'play-ctx': (el) => playList(ctxLists[el.dataset.ctx], 0, false),
    'play-lib': () => playList(ctxLists.lib, 0, false),
    'shuffle-lib': () => playList(ctxLists.lib, -1, true),
    'shuffle-all': () => {
      if (!state.library.length) { toast('Önce birkaç şarkı indir'); return; }
      playList(state.library.map((t) => t.id), -1, true);
    },
    'lib-filter': (el) => { state.lib.filter = el.dataset.filter; if (state.tab !== 'library') setTab('library'); else fillLibrary(); },
    'lib-sort': () => {
      const keys = Object.keys(SORTS);
      state.lib.sort = keys[(keys.indexOf(state.lib.sort) + 1) % keys.length];
      if (state.lib.filter === 'top') state.lib.filter = 'all';
      fillLibrary();
    },
    'track-menu': (el) => trackMenu(el.dataset.id),
    'close-sheet': () => closeSheet(),
    'm-play': (el) => { closeSheet(); playList([el.dataset.id], 0, false); },
    'm-next': (el) => { closeSheet(); call('queue', el.dataset.id, true); },
    'm-queue': (el) => { closeSheet(); call('queue', el.dataset.id, false); },
    'm-fav': (el) => { closeSheet(); call('toggleFav', el.dataset.id); },
    'm-share': (el) => { closeSheet(); call('shareTrack', el.dataset.id); },
    'm-delete': (el) => confirmDelete(el.dataset.id),
    'm-delete-yes': (el) => { closeSheet(); call('deleteTrack', el.dataset.id); },
    'paste-download': () => {
      const text = (call('readClipboard') || '').trim();
      if (!text) { toast('Panoda link yok. YouTube\'da Paylaş → Bağlantıyı kopyala'); return; }
      if (startDownload(text)) setTab('downloads');
    },
    'dl-link': () => { if (startDownload(state.search.q)) { state.search.q = ''; setTab('downloads'); } },
    'dl-result': (el) => {
      const r = (state.search.results || []).find((x) => x.videoId === el.dataset.vid);
      if (!r) return;
      call('download', r.url, JSON.stringify({ title: r.title, artist: r.channel, thumb: r.thumb }));
    },
    'result': (el) => {
      const vid = el.dataset.vid;
      if (libById.has(vid)) { playList([vid], 0, false); return; }
      const st = dlStatus(vid);
      if (ACTIVE.includes(st.s)) { toast('İndiriliyor…'); return; }
      actions['dl-result'](el);
    },
    'retry-search': () => runSearch(state.search.q),
    'clear-search': () => { state.search = { q: '', results: null, loading: false, error: '', reqId: '' }; mountSearch(); $('#q')?.focus(); },
    'recent': (el) => { $('#q').value = el.dataset.q; $('[data-action="clear-search"]').hidden = false; runSearch(el.dataset.q); },
    'clear-recent': () => { try { localStorage.removeItem('efk.recent'); } catch (e) { /* yok say */ } fillSearch(); },
    'dl-cancel': (el) => call('cancelDownload', el.dataset.job),
    'dl-retry': (el) => call('retryDownload', el.dataset.job),
    'dl-remove': (el) => call('removeDownload', el.dataset.job),
    'dl-clear': () => call('clearDownloads'),
    'update-engine': () => { call('updateEngine'); toast('Motor güncellemesi kontrol ediliyor…'); },
    'set-quality': (el) => { call('setSetting', 'quality', el.dataset.v); state.settings.quality = el.dataset.v; settingsSheet(); },
    'toggle-auto': () => { state.settings.autoUpdate = !state.settings.autoUpdate; call('setSetting', 'autoUpdate', String(state.settings.autoUpdate)); settingsSheet(); },
    'yt-login': () => { closeSheet(); call('loginYouTube'); },
    'yt-logout': () => { call('logoutYouTube'); state.settings.cookies = false; settingsSheet(); },
    'rescan': () => { call('rescan'); toast('Müzik klasörü taranıyor…'); },
    'check-update': () => { call('checkAppUpdate', true); toast('Kontrol ediliyor…'); },
    'open-url': (el) => call('openUrl', el.dataset.url),
  };

  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-action]');
    if (!el || el.disabled) return;
    const fn = actions[el.dataset.action];
    if (fn) { e.preventDefault(); fn(el, e); }
  });

  view.addEventListener('scroll', () => $('.topbar').classList.toggle('scrolled', view.scrollTop > 4), { passive: true });

  // ---------- Yerelden gelen olaylar ----------
  function setLibrary(list) {
    state.library = list || [];
    libById = new Map(state.library.map((t) => [t.id, t]));
  }

  function badge() {
    const n = state.downloads.filter((j) => ACTIVE.includes(j.status)).length;
    const b = $('#dl-badge');
    b.hidden = !n;
    b.textContent = n;
  }

  window.efk = {
    onEvent(type, data) {
      switch (type) {
        case 'library':
          setLibrary(data);
          refresh();
          updatePlayerUI();
          break;
        case 'downloads':
          state.downloads = data || [];
          badge();
          if (state.tab === 'downloads' || state.tab === 'search') refresh();
          break;
        case 'downloaded':
          toast('İndirildi: ' + (data.title || 'şarkı'));
          break;
        case 'engine':
          state.engine = data || {};
          if (state.tab === 'downloads' || state.tab === 'search') refresh();
          if (state.sheet === 'settings') settingsSheet();
          break;
        case 'settings':
          state.settings = data || state.settings;
          if (state.sheet === 'settings') settingsSheet();
          break;
        case 'player': {
          state.player = Object.assign(state.player, data || {});
          const key = `${state.player.id}|${state.player.playing}`;
          updatePlayerUI();
          if (key !== lastPlayerKey) { lastPlayerKey = key; refresh(); }
          break;
        }
        case 'pos':
          state.player.pos = data.pos;
          if (data.dur) state.player.dur = data.dur;
          updatePosition();
          break;
        case 'search':
          if (data.reqId !== state.search.reqId) return;
          state.search.loading = false;
          state.search.error = data.error || '';
          state.search.results = data.results || null;
          if (state.tab === 'search') fillSearch();
          break;
        case 'toast':
          toast(data.msg);
          break;
        case 'navigate':
          if (data.tab === 'player') openPlayer();
          else { closeSheet(); if (state.playerOpen) closePlayer(); setTab(data.tab); }
          break;
        case 'appUpdate':
          state.appUpdate = data;
          if (state.tab === 'home') fillHome();
          break;
      }
    },
    back() {
      if (state.sheet) { closeSheet(); return true; }
      if (state.playerOpen) { closePlayer(); return true; }
      if (state.tab !== 'home') { setTab('home'); return true; }
      return false;
    },
    setInsets(top, bottom) {
      const r = document.documentElement.style;
      r.setProperty('--sat', top + 'px');
      r.setProperty('--sab', bottom + 'px');
    },
  };

  // ---------- Başlangıç ----------
  function boot() {
    const raw = call('getState');
    if (raw) {
      try {
        const s = JSON.parse(raw);
        setLibrary(s.library);
        state.downloads = s.downloads || [];
        state.engine = s.engine || {};
        state.settings = s.settings || state.settings;
        state.app = s.app || {};
        if (s.player) state.player = Object.assign(state.player, s.player);
      } catch (e) { console.error(e); }
    }
    lastPlayerKey = `${state.player.id}|${state.player.playing}`;
    badge();
    setTab('home');
    updatePlayerUI();
    setTimeout(() => call('checkAppUpdate', false), 1500);
  }
  boot();
})();
