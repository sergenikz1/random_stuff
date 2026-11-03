// Real Chromium executes the same document-start probe used by Android WebView.
// All media and sites are local fixtures; no third-party websites are needed by CI.
const { chromium } = require('playwright-core');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

const probe = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/background-video-probe.js'), 'utf8');
const sample = fs.readFileSync(path.join(__dirname, 'fixtures/sample.mp4'));
const servers = [];
async function server(handler) {
    const instance = http.createServer(handler);
    await new Promise(resolve => instance.listen(0, '127.0.0.1', resolve));
    servers.push(instance);
    return `http://127.0.0.1:${instance.address().port}`;
}
function html(response, body) { response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'}); response.end(body); }

(async () => {
    let browser;
    try {
        const player = await server((req, res) => {
            if (req.url.startsWith('/stream')) {
                res.writeHead(200, {'Content-Type': 'application/vnd.apple.mpegurl'});
                return res.end('#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST');
            }
            if (req.url === '/sample.mp4') { res.writeHead(200, {'Content-Type': 'video/mp4'}); return res.end(sample); }
            html(res, `<video id="film" width="640" height="360" autoplay src="/sample.mp4"></video>
                <script>window.parentIsProtected=false;try{parent.document.body}catch(e){window.parentIsProtected=true}
                setTimeout(function(){var v=document.getElementById('film');v.muted=false;v.volume=1;
                    fetch('/stream?token=fixture');},150);</script>`);
        });
        const site = await server((req, res) => {
            if (req.url === '/dynamic') return html(res, `<title>Фильм</title><meta property="og:image" content="/poster.jpg">
                <div id="mount"></div><script>setTimeout(function(){document.getElementById('mount').innerHTML=
                '<iframe allowfullscreen src="${player}/embed"></iframe>';},150)</script>`);
            if (req.url === '/static') return html(res, `<title>Обычное видео</title><video width="640" height="360" src="${player}/sample.mp4"></video>`);
            if (req.url === '/protected') return html(res, `<video id="v" src="${player}/sample.mp4"></video>
                <script>setTimeout(function(){document.getElementById('v').dispatchEvent(new Event('encrypted'))},700)</script>`);
            return html(res, '<title>Текстовая страница</title><p>Видео здесь нет</p>');
        });
        const executablePath = process.env.CHROMIUM_PATH || execFileSync('sh', ['-c',
            'command -v chromium || command -v google-chrome || command -v chromium-browser'], {encoding:'utf8'}).trim();
        browser = await chromium.launch({executablePath, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage']});
        const context = await browser.newContext();
        const messages = [];
        await context.exposeBinding('__nativeProbeMessage', (_source, message) => { messages.push(JSON.parse(message)); });
        await context.addInitScript(() => {
            window.IntergalacticVideoProbe = { postMessage: value => window.__nativeProbeMessage(value) };
        });
        await context.addInitScript({content: probe});
        const page = await context.newPage();

        await page.goto(site + '/dynamic');
        await page.waitForFunction(() => window.frames.length === 1);
        await new Promise((resolve, reject) => {
            const deadline = Date.now() + 6000;
            const timer = setInterval(() => {
                if (messages.some(m => m.kind === 'media' && m.url.includes('/stream?token=fixture'))) {
                    clearInterval(timer); resolve();
                } else if (Date.now() > deadline) { clearInterval(timer); reject(new Error('Cross-origin HLS was not discovered')); }
            }, 50);
        });
        const frame = page.frames().find(f => f.url().startsWith(player));
        assert.ok(frame, 'iframe on another origin is active');
        assert.equal(await frame.evaluate(() => window.parentIsProtected), true, 'origin protection remains intact');
        assert.deepEqual(await frame.evaluate(() => ({muted: document.querySelector('video').muted,
            volume: document.querySelector('video').volume})), {muted:true, volume:0}, 'background playback stays silent');
        const hls = messages.find(m => m.kind === 'media' && m.url.includes('/stream?token=fixture'));
        assert.equal(hls.frame, player + '/embed', 'native playback gets the iframe referer');
        assert.ok(/mpegurl/i.test(hls.mime), 'extensionless HLS keeps its MIME type');
        assert.ok(!messages.some(m => m.kind === 'media' && m.url.startsWith('blob:')), 'blob URLs are never passed to the native player');
        console.log('PASS: dynamically added cross-origin player, manifest requests, silent autoplay and iframe referer');

        messages.length = 0;
        await page.goto(site + '/static');
        await page.waitForTimeout(800);
        assert.ok(messages.some(m => m.kind === 'media' && m.url === player + '/sample.mp4'));
        assert.equal(await page.locator('video').evaluate(v => v.muted), true);
        console.log('PASS: direct HTML5 video becomes a media candidate');

        messages.length = 0;
        await page.goto(site + '/text'); await page.waitForTimeout(800);
        assert.ok(!messages.some(m => m.kind === 'media'), 'ordinary websites are not videos');
        console.log('PASS: text-only sites produce no video candidates');

        messages.length = 0;
        await page.goto(site + '/protected'); await page.waitForTimeout(1000);
        assert.ok(messages.some(m => m.kind === 'drm'), 'encrypted frames are reported for native rejection');
        await page.evaluate(() => window.__intergalacticProbe.stop());
        console.log('PASS: DRM signal and probe cleanup');
        await context.close();
    } finally {
        if (browser) await browser.close();
        await Promise.all(servers.map(instance => new Promise(resolve => instance.close(resolve))));
    }
})().catch(error => { console.error(error); process.exitCode = 1; });
