(function () {
    if (window.__intergalacticEpisodes) return;
    var config = window.__intergalacticEpisodeConfig || {}, epoch = null, applied = false;
    var running = false, completed = false, blocked = new Set(), pending = [], lastCatalog = '';
    function send(value) {
        value.frame = location.href;
        try { IntergalacticVideoProbe.postMessage(JSON.stringify(value)); } catch (_) {}
    }
    function key(url) { try { var u = new URL(url); return u.origin + u.pathname; } catch (_) { return ''; } }
    function episodeNumber(value, fallback) {
        var match = String(value).match(/(?:episode|серия|серию|эпизод)\s*(\d+)/i) || String(value).match(/(\d+)\s*(?:серия|эпизод)/i);
        return match ? +match[1] : number(value, fallback);
    }
    function number(value, fallback) { var n = String(value).match(/\d+/); return n ? Math.min(5000, +n[0]) : fallback; }
    function role(node) {
        var name = [node.id, node.name, node.className, node.getAttribute('aria-label')].join(' ');
        if (/season|сезон/i.test(name)) return 'season';
        if (/episode|серии|серия|эпизод|series/i.test(name)) return 'episode';
        return '';
    }
    function selects(type) { return Array.from(document.querySelectorAll('select')).filter(function (s) { return role(s) === type; }); }
    function options(select) { return select ? Array.from(select.options).filter(function (o) { return !o.disabled && !/выбер|select|choose/i.test(o.textContent); }) : []; }
    function controls() {
        var ss = selects('season')[0], es = selects('episode')[0];
        var season = ss ? number(ss.options[ss.selectedIndex] && ss.options[ss.selectedIndex].textContent, ss.selectedIndex + 1) : 1;
        var sv = ss ? ss.value : '';
        var result = [];
        options(ss).forEach(function (o, i) { result.push({season: number(o.textContent, i + 1), episode: 0,
            seasonValue: o.value, episodeValue: '', label: 'Сезон ' + number(o.textContent, i + 1)}); });
        options(es).forEach(function (o, i) { var e = number(o.textContent, i + 1); result.push({season: season, episode: e,
            seasonValue: sv, episodeValue: o.value, label: 'Сезон ' + season + ' · Серия ' + e}); });
        // Explicit episode controls inside player/episode containers; never general navigation buttons.
        document.querySelectorAll('[data-episode],.episodes button,.episodes a,.episode-list button,.episode-list a,' +
            '[class*="episode"] [data-id],a[href]').forEach(function (node) {
            if (result.length >= 160) return;
            var text = (node.textContent || '').trim();
            var explicit = node.hasAttribute('data-episode') || /episode|серия|серию|эпизод/i.test(text) ||
                (/^\d+$/.test(text) && node.closest('.episodes,.episode-list'));
            if (!explicit || /войти|купить|подпис|login|buy/i.test(text)) return;
            var e = episodeNumber(node.getAttribute('data-episode') || text, 0);
            if (!e) return;
            var href = null;
            if (node.tagName === 'A' && node.getAttribute('href') && node.getAttribute('href')[0] !== '#') {
                try { var u = new URL(node.href); if (!/^https?:$/.test(u.protocol) || u.host !== location.host) return; href = u.href; } catch (_) { return; }
            }
            var s = number(node.getAttribute('data-season'), season);
            result.push({season:s, episode:e, seasonValue:sv, episodeValue:'button:' + e,
                href:href, label:'Сезон ' + s + ' · Серия ' + e});
        });
        return {ss:ss, es:es, items:result.slice(0,160)};
    }
    function change(select, value) {
        select.value = value;
        select.dispatchEvent(new Event('input', {bubbles:true}));
        select.dispatchEvent(new Event('change', {bubbles:true}));
    }
    function pause(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }
    async function apply() {
        var target = config.target;
        if (!target || running || completed || key(target.frame) !== key(location.href)) return;
        var c = controls();
        if (!c.items.length) return;
        running = true;
        try {
            document.querySelectorAll('video,source').forEach(function (v) {
                if (v.src) blocked.add(v.src); if (v.currentSrc) blocked.add(v.currentSrc);
            });
            if (target.seasonValue && c.ss) {
                if (!options(c.ss).some(function(o){ return o.value === target.seasonValue; })) return;
                if (c.ss.value !== target.seasonValue) { change(c.ss, target.seasonValue); await pause(1400); }
            }
            if (!target.episode) { completed = true; return; }
            c = controls();
            if (c.es && !target.episodeValue.startsWith('button:')) {
                if (!options(c.es).some(function(o){ return o.value === target.episodeValue; })) return;
                // Force a real selection change even when this episode is initially selected.
                if (c.es.value === target.episodeValue) {
                    var other = options(c.es).find(function(o){ return o.value !== target.episodeValue; });
                    if (other) { change(c.es, other.value); await pause(500); }
                }
                document.querySelectorAll('video,source').forEach(function(v){ if(v.src) blocked.add(v.src); if(v.currentSrc) blocked.add(v.currentSrc); });
                epoch = config.requestId;
                change(c.es, target.episodeValue);
            } else {
                var button = Array.from(document.querySelectorAll('[data-episode],.episodes button,.episodes a,.episode-list button,.episode-list a,[class*="episode"] [data-id]'))
                    .find(function(n){ return episodeNumber(n.getAttribute('data-episode') || n.textContent, 0) === target.episode; });
                if (!button) return;
                epoch = config.requestId; button.click();
            }
            await pause(1500);
            applied = true; completed = true;
            send({kind:'episodeApplied', requestId:epoch});
            pending.splice(0).forEach(function(v){ send(v); });
            if (window.__intergalacticProbe) window.__intergalacticProbe.scan();
        } finally { running = false; }
    }
    window.__intergalacticEpisodes = {
        epoch:function(){ return epoch; },
        media:function(value, requestEpoch, network){
            if (!config.target || config.target.href) return true;
            if (!epoch || (network && requestEpoch !== epoch) || (!network && blocked.has(value.url))) return false;
            value.requestId = epoch;
            if (!applied) { pending.push(value); return false; }
            return true;
        }
    };
    function scan() {
        var c = controls(), payload = JSON.stringify(c.items);
        if (payload !== lastCatalog && c.items.length) {
            lastCatalog = payload;
            send({kind:'episodes', items:c.items});
        }
        apply();
    }
    setInterval(scan, 650);
    window.addEventListener('DOMContentLoaded', scan);
})();
