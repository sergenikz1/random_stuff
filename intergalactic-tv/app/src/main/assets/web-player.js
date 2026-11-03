function (action, seconds) {
    var videos = [];
    function scan(w) {
        try {
            w.document.querySelectorAll('video').forEach(function (v) { videos.push(v); });
            for (var i = 0; i < w.frames.length; i++) scan(w.frames[i]);
        } catch (e) { /* Cross-origin embeds retain their own controls. */ }
    }
    scan(window);
    videos.sort(function (a, b) {
        return b.getBoundingClientRect().width * b.getBoundingClientRect().height -
            a.getBoundingClientRect().width * a.getBoundingClientRect().height;
    });
    if (action === 'restore') {
        document.querySelectorAll('[data-intergalactic-style]').forEach(function (e) {
            e.style.cssText = e.getAttribute('data-intergalactic-style');
            e.removeAttribute('data-intergalactic-style');
        });
        return true;
    }
    if (action === 'expand') {
        var target = videos.find(function (v) { return v.ownerDocument === document; }) ||
            Array.from(document.querySelectorAll('iframe')).sort(function (a, b) {
                return b.clientWidth * b.clientHeight - a.clientWidth * a.clientHeight;
            })[0];
        if (!target) return false;
        // Keep ancestors visible: sites often clip transformed descendants.
        for (var e = target; e && e !== document.body; e = e.parentElement) {
            if (!e.hasAttribute('data-intergalactic-style')) e.setAttribute('data-intergalactic-style', e.style.cssText);
            e.style.setProperty('transform', 'none', 'important');
            e.style.setProperty('overflow', 'visible', 'important');
            e.style.setProperty('z-index', '2147483647', 'important');
        }
        target.style.setProperty('position', 'fixed', 'important');
        target.style.setProperty('inset', '0', 'important');
        target.style.setProperty('width', '100vw', 'important');
        target.style.setProperty('height', '100vh', 'important');
        target.style.setProperty('background', 'black', 'important');
        if (target.tagName === 'VIDEO') { target.controls = true; target.focus(); }
        return true;
    }
    if (!videos.length) return false;
    var video = videos[0];
    if (action === 'toggle') {
        if (video.paused) video.play().catch(function () {}); else video.pause();
    } else if (action === 'play') {
        video.play().catch(function () {});
    } else if (action === 'pause') {
        videos.forEach(function (v) { v.pause(); });
    } else if (action === 'seek') {
        try {
            var upper = isFinite(video.duration) ? video.duration : Infinity;
            video.currentTime = Math.max(0, Math.min(upper, video.currentTime + seconds));
        } catch (e) { return false; }
    }
    return true;
}
