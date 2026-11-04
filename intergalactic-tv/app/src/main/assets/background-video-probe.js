(function () {
    if (window.__intergalacticProbe) { window.__intergalacticProbe.scan(); return; }
    var seen = new Set(), clicked = new WeakSet(), stopped = false;
    function send(value) {
        if (stopped || !window.IntergalacticVideoProbe) return;
        value.frame = location.href;
        try { window.IntergalacticVideoProbe.postMessage(JSON.stringify(value)); } catch (_) {}
    }
    function absolute(raw) {
        if (typeof raw !== "string" || !raw.trim()) return null;
        try { var u = new URL(raw, location.href); return /^https?:$/.test(u.protocol) ? u.href : null; }
        catch (_) { return null; }
    }
    function meta(key) {
        var node = document.querySelector('meta[property="' + key + '"],meta[name="' + key + '"]');
        return node ? node.content : '';
    }
    function media(raw, mime, video, requestEpoch, network) {
        var url = absolute(raw);
        if (!url || seen.has(url) || seen.size >= 40) return;
        var value = {kind: 'media', url: url, mime: mime || '', title: meta('og:title') || document.title,
            poster: absolute((video && video.poster) || meta('og:image')) || '',
            duration: video && isFinite(video.duration) ? video.duration : 0};
        if (window.__intergalacticEpisodes && !window.__intergalacticEpisodes.media(value, requestEpoch, network)) return;
        seen.add(url); send(value);
    }
    // Installed at document start in every frame, before site player code. Never play background audio.
    try {
        var proto = HTMLMediaElement.prototype;
        ['muted', 'volume'].forEach(function (key) {
            var descriptor = Object.getOwnPropertyDescriptor(proto, key);
            if (descriptor && descriptor.set) Object.defineProperty(proto, key, {
                configurable: true, get: descriptor.get,
                set: function () { descriptor.set.call(this, key === 'muted' ? true : 0); }
            });
        });
        var originalPlay = proto.play;
        proto.play = function () { this.muted = true; this.volume = 0; return originalPlay.apply(this, arguments); };
    } catch (_) {}
    function scan() {
        if (stopped || !document.documentElement) return;
        send({kind: 'metadata', title: meta('og:title') || document.title,
            poster: absolute(meta('og:image')) || ''});
        document.querySelectorAll('video').forEach(function (video) {
            video.muted = true; video.volume = 0;
            if (!video.__igObserved) {
                video.__igObserved = true;
                video.addEventListener('encrypted', function () { send({kind: 'drm'}); });
                ['loadedmetadata', 'loadstart', 'canplay', 'playing'].forEach(function (event) {
                    video.addEventListener(event, scan);
                });
            }
            if (video.currentSrc) media(video.currentSrc, '', video);
            if (video.getAttribute('src')) media(video.src, '', video);
            video.querySelectorAll('source[src]').forEach(function (source) { media(source.src, source.type, video); });
            if (video.paused) { var p = video.play(); if (p && p.catch) p.catch(function () {}); }
        });
        ['og:video', 'og:video:url', 'og:video:secure_url', 'twitter:player:stream'].forEach(function (key) {
            var url = absolute(meta(key));
            if (url && /\.(m3u8|mpd|mp4|webm)(?:[?#]|$)/i.test(url)) media(url, meta('og:video:type'));
        });
        function jsonVideo(value, depth) {
            if (!value || typeof value !== 'object' || depth > 12) return;
            if (String(value['@type']).indexOf('VideoObject') >= 0 && value.contentUrl) media(value.contentUrl, value.encodingFormat);
            Object.keys(value).forEach(function (key) { jsonVideo(value[key], depth + 1); });
        }
        document.querySelectorAll('script[type="application/ld+json"]').forEach(function (node) {
            try { jsonVideo(JSON.parse(node.textContent), 0); } catch (_) {}
        });
        // Known video-player controls only: never follow links, log in, or click purchase/consent forms.
        document.querySelectorAll('.vjs-big-play-button,.jw-display-icon-container .jw-icon-display,' +
            '.plyr__control--overlaid,button[data-plyr="play"],button[aria-label="Play"],' +
            'button[aria-label="Воспроизвести"]').forEach(function (button) {
            if (!clicked.has(button)) { clicked.add(button); button.click(); }
        });
        document.querySelectorAll('iframe[data-src][allowfullscreen]').forEach(function(frame) {
            if (!frame.getAttribute('src')) { var lazy = absolute(frame.getAttribute('data-src')); if (lazy) frame.src = lazy; }
        });
        document.querySelectorAll('iframe[src]').forEach(function (frame) {
            var url = absolute(frame.src);
            if (url && (frame.hasAttribute('allowfullscreen') || /(?:player|embed|video)/i.test(url)))
                send({kind: 'frame', url: url});
        });
    }
    function observeResponse(url, type, requestEpoch) {
        if (/mpegurl|dash\+xml|^video\//i.test(type || '') || /\.(m3u8|mpd|mp4|webm)(?:[?#]|$)/i.test(url))
            media(url, type, null, requestEpoch, true);
    }
    var fetchOriginal = window.fetch;
    if (fetchOriginal) window.fetch = function () {
        var requestEpoch = window.__intergalacticEpisodes && window.__intergalacticEpisodes.epoch();
        return fetchOriginal.apply(this, arguments).then(function (response) {
            observeResponse(response.url, response.headers.get('content-type'), requestEpoch); return response;
        });
    };
    var openOriginal = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function () {
        this.__igEpoch = null;
        this.addEventListener('load', function () { observeResponse(this.responseURL, this.getResponseHeader('content-type'), this.__igEpoch); });
        return openOriginal.apply(this, arguments);
    };
    var sendOriginal = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.send = function () {
        this.__igEpoch = window.__intergalacticEpisodes && window.__intergalacticEpisodes.epoch();
        return sendOriginal.apply(this, arguments);
    };
    function silence(node) {
        if (node instanceof HTMLMediaElement) { node.muted = true; node.volume = 0; }
        if (node.querySelectorAll) node.querySelectorAll('video,audio').forEach(function (v) {
            v.muted = true; v.volume = 0;
        });
    }
    var observer = new MutationObserver(function (changes) {
        changes.forEach(function (change) { change.addedNodes.forEach(silence); });
    });
    observer.observe(document, {childList: true, subtree: true});
    var timer = setInterval(scan, 650);
    window.__intergalacticProbe = {scan: scan, stop: function () {
        stopped = true; clearInterval(timer); observer.disconnect();
        document.querySelectorAll('video,audio').forEach(function (v) { v.pause(); });
    }};
    window.addEventListener('DOMContentLoaded', scan);
    scan();
})();
