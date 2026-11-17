// npm install --no-save playwright@1.56.1; node scripts/browser-smoke.cjs /path/to/synthetic.mp4
const {chromium} = require('playwright');
(async () => {
    const browser = await chromium.launch({headless: true, executablePath: process.env.CHROMIUM_PATH});
    const page = await browser.newPage();
    await page.goto(process.env.BASE || 'http://localhost:8090');
    await page.locator('#user').fill('browser-test');
    await page.locator('#login').click();
    await page.locator('#title').fill('Browser synthetic playback');
    await page.locator('#file').setInputFiles(process.argv[2] || '/tmp/streaming-fixture.mp4');
    await page.locator('#upload').click();
    await page.waitForFunction(() => document.querySelector('#status').textContent.includes('Upload complete'), null, {timeout: 60000});
    let ready = false;
    for (let i = 0; i < 60; i++) {
        await page.locator('#mine').click();
        await page.waitForTimeout(1000);
        if ((await page.locator('article').first().textContent()).includes('READY')) {
            ready = true;
            break;
        }
    }
    if (!ready) throw Error('Media not ready');
    let reviewed = false;
    if (!(await page.getByRole('button', {name: 'Publish', exact: true}).count())) {
        await page.locator('#user').fill('moderator');
        await page.locator('#login').click();
        await page.locator('#reviewQueue').click();
        await page.waitForTimeout(500);
        await page.getByRole('button', {name: 'Play', exact: true}).first().click();
        await page.waitForFunction(() => document.querySelector('video').currentTime > 1, null, {timeout: 30000});
        page.once('dialog', dialog => dialog.accept('Synthetic browser policy review'));
        await page.getByRole('button', {name: 'Approve', exact: true}).first().click();
        await page.waitForTimeout(500);
        await page.locator('#user').fill('browser-test');
        await page.locator('#login').click();
        await page.locator('#mine').click();
        await page.waitForTimeout(500);
        reviewed = true;
    }
    await page.getByRole('button', {name: 'Publish', exact: true}).first().click();
    await page.waitForTimeout(1000);
    await page.getByRole('button', {name: 'Play', exact: true}).first().click();
    await page.waitForFunction(() => document.querySelector('video').currentTime > 2, null, {timeout: 30000});
    const initial = await page.locator('video').evaluate(v => ({
        time: v.currentTime,
        width: v.videoWidth,
        height: v.videoHeight
    }));
    await page.locator('video').evaluate(v => {
        v.currentTime = 5
    });
    await page.waitForFunction(() => document.querySelector('video').currentTime > 5.5, null, {timeout: 15000});
    await page.locator('video').evaluate(v => v.pause());
    const qualities = await page.locator('#quality option').allTextContents();
    if (qualities.length < 3) throw Error('Expected auto and two quality options');
    console.log(JSON.stringify({result: 'PASS', decoded: initial, seek: true, reviewed, qualities}));
    await page.screenshot({path: '/tmp/streaming-player.png'});
    await browser.close();
})().catch(e => {
    console.error(e);
    process.exit(1)
});
