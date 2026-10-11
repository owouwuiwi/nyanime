const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { JSDOM } = require('jsdom');
const source = readFileSync('../../app/src/main/assets/animeschedule-setup.js', 'utf8');
const labels = { name: 'Connection name', login: 'Sign in', username: 'Username or email',
    create: 'Create connection', select: 'Use connection', register: 'Create account', confirmPassword: 'Confirm password' };
function page(path, html, host = 'https://animeschedule.net') {
    const dom = new JSDOM(`<header>Navigation</header><main>${html}</main><footer>Footer</footer>`,
        { url: host + path, runScripts: 'outside-only' });
    dom.window.eval(`${source}(${JSON.stringify(labels)})`);
    return dom;
}
const account = `<div class="form-container"><form><label for="username-email">Username</label>
    <input id="username-email"><input id="password" type="password"><button id="submit">Login</button>
    <div class="g-recaptcha"><iframe src="https://www.google.com/recaptcha/test"></iframe></div></form></div>`;
const create = `<div id="settings-container"><div class="error hidden">Server error</div><form id="create-app-form">
    <div class="input-wrapper"><label for="name">Name</label><input id="name" required></div>
    <div class="input-wrapper-custom"><textarea id="description"></textarea></div>
    <div class="input-wrapper-custom"><input id="redirect-uri"></div>
    <div class="input-wrapper-custom"><input id="animelist-scope-checkbox" type="checkbox">
    <input id="stats-scope-checkbox" type="checkbox"></div>
    <div><input id="api-terms-acceptance-checkbox" type="checkbox" required><a href="/api-terms-of-use">Terms</a></div>
    <button id="submit">Create</button></form></div>`;
function app(token) { return `<div class="app-container"><h2>Test connection</h2>
    <span class="app-token">${token}</span><span class="app-secret">synthetic-oauth-secret</span>
    <button onclick="deleteApplication(this)">Delete</button></div>`; }
test('login keeps original form, challenge and errors while hiding only surrounding navigation', () => {
    const dom = page('/login', `<div class="error">Wrong password</div>${account}`);
    const d = dom.window.document;
    assert.equal(dom.window.nyanimeScheduleSetup.state().cropped, true);
    assert.ok(d.querySelector('header').hasAttribute('data-ny-hidden'));
    assert.ok(!d.querySelector('.error').hasAttribute('data-ny-hidden'));
    assert.ok(!d.querySelector('.g-recaptcha').hasAttribute('data-ny-hidden'));
    assert.equal(d.querySelector('#submit').textContent, 'Sign in');
    assert.equal(d.querySelector('#password').value, '');
    dom.window.close();
});
test('full website restores layout without reloading or losing entered values', () => {
    const dom = page('/login', account), w = dom.window;
    w.document.querySelector('#username-email').value = 'unsent-draft';
    w.nyanimeScheduleSetup.full(true);
    assert.equal(w.nyanimeScheduleSetup.state().cropped, false);
    assert.ok(!w.document.documentElement.hasAttribute('data-ny-schedule'));
    assert.equal(w.document.querySelector('#username-email').value, 'unsent-draft');
    w.nyanimeScheduleSetup.full(false);
    assert.equal(w.nyanimeScheduleSetup.state().cropped, true);
    w.close();
});
test('original form padding cannot add width beyond the guided viewport', () => {
    const dom = page('/login', `<style>.form-container { width:30em; }
        .form { display:flex; flex-direction:column; padding:0 2em 2em; }</style>${account.replace('<form>', '<form class="form">')}`);
    const w=dom.window, form=w.document.querySelector('form');
    const style=w.getComputedStyle(form);
    assert.equal(style.boxSizing, 'border-box');
    assert.equal(style.paddingLeft, '0px');
    assert.equal(style.paddingRight, '0px');
    assert.equal(style.maxWidth, '100%');
    w.nyanimeScheduleSetup.full(true);
    assert.equal(w.getComputedStyle(form).paddingLeft, '2em');
    w.close();
});
test('registration confirmation headings remain visible when there is no input form', () => {
    const dom = page('/signup', '<div class="form-container"><div class="form-title-container"><h1>Check your email</h1></div></div>');
    assert.ok(!dom.window.document.querySelector('[data-ny-root]').hasAttribute('data-ny-form'));
    dom.window.close();
});
test('registration preserves mandatory confirmation, agreement and challenge without checking or submitting', () => {
    const dom = page('/signup', account.replace('<button', '<input id="password-confirmation" type="password"><input name="terms-agreement" type="checkbox" required><button'));
    const w = dom.window;
    assert.equal(w.nyanimeScheduleSetup.state().stage, 'ACCOUNT');
    assert.equal(w.document.querySelector('[name=terms-agreement]').checked, false);
    assert.equal(w.document.querySelector('#submit').textContent, 'Create account');
    assert.ok(w.document.querySelector('#password-confirmation'));
    w.close();
});
test('create form supplies only a name; OAuth scopes and API agreement remain unselected', () => {
    const dom = page('/users/Test/settings/api/create', create), w = dom.window;
    assert.equal(w.document.querySelector('#name').value, 'Nyanime');
    assert.equal(w.document.querySelector('#api-terms-acceptance-checkbox').checked, false);
    assert.equal(w.document.querySelector('#stats-scope-checkbox').checked, false);
    assert.ok(w.document.querySelector('#description').parentElement.hasAttribute('data-ny-hidden'));
    assert.ok(!w.document.querySelector('#api-terms-acceptance-checkbox').parentElement.hasAttribute('data-ny-hidden'));
    w.close();
});
test('password reset and verification resend keep their own actions and headings', () => {
    for (const [path, stage, action] of [['/login?stage=reset-password', 'RESET', 'Reset'],
        ['/signup?stage=resend-email', 'VERIFY', 'Resend']]) {
        const dom = page(path, `<div class="form-container"><div class="form-title-container"><h1>${action}</h1></div>
            <form><input id="email" type="email"><button id="submit">${action}</button></form></div>`), w = dom.window;
        assert.equal(w.nyanimeScheduleSetup.state().stage, stage);
        assert.equal(w.document.querySelector('#submit').textContent, action);
        assert.equal(w.document.querySelector('[data-ny-root]').getAttribute('data-ny-kind'), stage);
        w.close();
    }
});
test('already selected scope and existing redirect are not silently hidden', () => {
    const dom = page('/users/Test/settings/api/create', create.replace('id="stats-scope-checkbox"', 'id="stats-scope-checkbox" checked')
        .replace('<input id="redirect-uri">', '<input id="redirect-uri"><span class="uri">https://callback.invalid/</span>'));
    const d = dom.window.document;
    assert.ok(!d.querySelector('#stats-scope-checkbox').parentElement.hasAttribute('data-ny-hidden'));
    assert.ok(!d.querySelector('#redirect-uri').parentElement.hasAttribute('data-ny-hidden'));
    dom.window.close();
});
test('structural polling never exports a Bearer token or OAuth secret', () => {
    const dom = page('/users/Test/settings/api', `<div id="created-apps-wrapper">${app('synthetic-bearer-token')}</div>`), w = dom.window;
    const state = JSON.stringify(w.nyanimeScheduleSetup.state());
    assert.equal(w.nyanimeScheduleSetup.state().tokenAvailable, true);
    assert.ok(!state.includes('synthetic-bearer-token'));
    assert.ok(!state.includes('synthetic-oauth-secret'));
    assert.equal(w.nyanimeScheduleSetup.token(), 'synthetic-bearer-token');
    w.close();
});
test('multiple applications require selection before import; app secret is never imported', () => {
    const dom = page('/users/Test/settings/api', `<div id="created-apps-wrapper">${app('synthetic-first-token')}${app('synthetic-second-token')}</div>`), w = dom.window;
    assert.equal(w.nyanimeScheduleSetup.state().tokenAvailable, false);
    assert.equal(w.nyanimeScheduleSetup.token(), '');
    w.document.querySelectorAll('.ny-schedule-select')[1].click();
    assert.equal(w.nyanimeScheduleSetup.token(), 'synthetic-second-token');
    w.close();
});
test('empty application list exposes a same-origin create route without submitting it', () => {
    const dom = page('/users/Test/settings/api', '<div id="created-apps-wrapper"><a id="create-app-button" href="/users/Test/settings/api/create">Create</a></div>'), w = dom.window;
    assert.equal(w.nyanimeScheduleSetup.state().target, '/users/Test/settings/api/create');
    assert.equal(w.nyanimeScheduleSetup.token(), '');
    w.nyanimeScheduleSetup.full(true);
    assert.equal(w.nyanimeScheduleSetup.state().target, '');
    w.close();
});
test('unrecognized markup keeps the page intact, and other origins cannot install the adapter', () => {
    const unknown = page('/login', '<article>Changed login form</article>');
    assert.equal(unknown.window.nyanimeScheduleSetup.state().cropped, false);
    assert.ok(!unknown.window.document.documentElement.hasAttribute('data-ny-schedule'));
    unknown.window.close();
    const foreign = page('/login', account, 'https://other.invalid');
    assert.equal(foreign.window.nyanimeScheduleSetup, undefined);
    foreign.window.close();
});
