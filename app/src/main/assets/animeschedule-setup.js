/* Scoped enhancement of the original forms. Never reads passwords or submits a form. */
(function (labels) {
    'use strict';
    if (window.nyanimeScheduleSetup) return window.nyanimeScheduleSetup.state();
    const ownOrigin = location.protocol === 'https:' && location.hostname === 'animeschedule.net' && !location.port;
    if (!ownOrigin) return { stage: 'OTHER', cropped: false };
    const apiPath = /^\/users\/[^/]+\/settings\/api\/?$/;
    const createPath = /^\/users\/[^/]+\/settings\/api\/create\/?$/;
    let root = null, stage = 'OTHER', selected = null, full = false, queued = false;
    const safePath = (href) => {
        try {
            const url = new URL(href, location.href);
            return url.origin === location.origin ? url.pathname : '';
        } catch (_) { return ''; }
    };
    const style = document.createElement('style');
    style.id = 'nyanime-schedule-style';
    style.textContent = `
        html[data-ny-schedule] { color-scheme: dark; background: #111114; }
        html[data-ny-schedule] body { margin: 0 !important; background: #111114 !important; }
        html[data-ny-schedule] [data-ny-hidden] { display: none !important; }
        html[data-ny-schedule] [data-ny-path] { display: block !important; position: static !important;
            width: auto !important; min-width: 0 !important; max-width: none !important;
            height: auto !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important;
            transform: none !important; border: 0 !important; background: transparent !important; }
        html[data-ny-schedule] [data-ny-root] { box-sizing: border-box !important; width: 100% !important;
            max-width: 600px !important; min-width: 0 !important; margin: 0 auto !important;
            padding: 20px 16px 32px !important; border: 0 !important; box-shadow: none !important;
            background: transparent !important; color: #f5f5f7 !important; }
        html[data-ny-schedule] [data-ny-root] form { box-sizing: border-box !important;
            width: 100% !important; min-width: 0 !important; max-width: 100% !important;
            padding: 0 !important; margin: 0 !important; }
        html[data-ny-schedule] [data-ny-root] { overflow-wrap: anywhere; }
        html[data-ny-schedule] [data-ny-root] .password-input-wrapper { width: 100%; min-width: 0; }
        html[data-ny-schedule] [data-ny-root] .input-wrapper,
        html[data-ny-schedule] [data-ny-root] .input-wrapper-custom { margin: 0 0 20px !important; }
        html[data-ny-schedule] [data-ny-root] input:not([type=checkbox]):not([type=hidden]):not([type=file]),
        html[data-ny-schedule] [data-ny-root] textarea { box-sizing: border-box !important; font-size: 16px !important;
            width: 100% !important; min-width: 0 !important; min-height: 52px; border-radius: 14px;
            background: #242429; color: #fff; }
        html[data-ny-schedule] [data-ny-root] button,
        html[data-ny-schedule] [data-ny-root] a { min-height: 44px; }
        html[data-ny-schedule] [data-ny-root] #submit,
        html[data-ny-schedule] .ny-schedule-select { width: 100%; min-height: 52px;
            border-radius: 16px; background: #f5f5f7; color: #141418; font-size: 16px; font-weight: 600; }
        html[data-ny-schedule] [data-ny-kind=LOGIN][data-ny-form] .form-title-container,
        html[data-ny-schedule] [data-ny-kind=ACCOUNT][data-ny-form] .form-title-container { display: none; }
        html[data-ny-schedule] [data-ny-root] #login-links,
        html[data-ny-schedule] [data-ny-root] #sign-up-links { line-height: 2.2; padding: 12px 0;
            white-space: normal; max-width: 100%; }
        html[data-ny-schedule] [data-ny-root] .app-token,
        html[data-ny-schedule] [data-ny-root] .app-secret { display: none !important; }
        html[data-ny-schedule] [data-ny-root] .app-container { max-width: 100%; box-sizing: border-box;
            border-radius: 16px; margin: 12px 0; overflow-wrap: anywhere; }
        html[data-ny-schedule] .ny-schedule-select { margin-top: 12px; }
        html[data-ny-schedule] .ny-schedule-select[aria-pressed=true] { background: #cfddff; }
        html[data-ny-schedule] [data-ny-root] .g-recaptcha { transform-origin: left top; }
    `;
    document.head.appendChild(style);
    // Preserve challenge overlays and error/consent dialogs outside the form's ancestor chain.
    const auxiliary = (node) => node.matches('script, style, link, #as-notice-layer, .error, .status-text-container') ||
        !!node.querySelector('iframe[src*="recaptcha"], iframe[src*="hcaptcha"], #as-notice-panel, .error, .status-text-container');
    const hidden = (node) => { if (node) node.setAttribute('data-ny-hidden', ''); };
    const reset = () => {
        document.querySelectorAll('[data-ny-path], [data-ny-hidden], [data-ny-root], [data-ny-kind], [data-ny-form]').forEach(n => {
            n.removeAttribute('data-ny-path'); n.removeAttribute('data-ny-hidden'); n.removeAttribute('data-ny-root');
            n.removeAttribute('data-ny-kind');
            n.removeAttribute('data-ny-form');
        });
    };
    function crop() {
        reset();
        if (!root || full) { document.documentElement.removeAttribute('data-ny-schedule'); return; }
        document.documentElement.setAttribute('data-ny-schedule', '');
        root.setAttribute('data-ny-root', '');
        root.setAttribute('data-ny-kind', stage);
        if (root.querySelector('form')) root.setAttribute('data-ny-form', '');
        for (let n = root; n && n !== document.body; n = n.parentElement) {
            const parent = n.parentElement;
            if (!parent) break;
            parent.setAttribute('data-ny-path', '');
            Array.from(parent.children).filter(s => s !== n && !auxiliary(s)).forEach(hidden);
        }
        if (stage === 'CREATE') {
            const form = document.querySelector('#create-app-form');
            const name = form.querySelector('#name');
            if (name && !name.value) { name.value = 'Nyanime'; name.dispatchEvent(new Event('input', { bubbles: true })); }
            const optional = ['#avatar-input-field', '#description', '#redirect-uri'];
            optional.forEach(selector => {
                const field = form.querySelector(selector);
                if (field && !field.value && !field.files?.length &&
                    !(selector === '#redirect-uri' && form.querySelector('.uri'))) hidden(field.closest('.input-wrapper, .input-wrapper-custom'));
            });
            const scopes = Array.from(form.querySelectorAll('#animelist-scope-checkbox, #stats-scope-checkbox'));
            if (scopes.length === 2 && scopes.every(n => !n.checked)) hidden(scopes[0].closest('.input-wrapper-custom'));
            const label = form.querySelector('label[for=name]');
            if (label) label.textContent = labels.name;
            form.querySelectorAll('label[for=api-terms-acceptance]').forEach(n => n.htmlFor = 'api-terms-acceptance-checkbox');
            const submit = form.querySelector('#submit');
            if (submit && !submit.classList.contains('loading-spinner') && !submit.disabled) submit.textContent = labels.create;
        }
        if (stage === 'LOGIN') {
            const label = root.querySelector('label[for=username-email]');
            if (label) label.textContent = labels.username;
            const submit = root.querySelector('#submit');
            if (submit && !submit.classList.contains('loading-spinner') && !submit.disabled) submit.textContent = labels.login;
        }
        if (stage === 'ACCOUNT' && root.querySelector('#password-confirmation')) {
            const label = root.querySelector('label[for=password-confirmation]');
            if (label) label.textContent = labels.confirmPassword;
            const submit = root.querySelector('#submit');
            if (submit && !submit.classList.contains('loading-spinner') && !submit.disabled) submit.textContent = labels.register;
        }
        if (stage === 'TOKEN') {
            // The original page may expose destructive maintenance controls. They remain in full-site mode.
            root.querySelectorAll('[onclick*="deleteApplication"], [onclick*="regenerateApplication"]').forEach(hidden);
            const apps = Array.from(root.querySelectorAll('.app-container')).filter(n => n.querySelector('.app-token'));
            if (apps.length === 1) selected = apps[0];
            apps.forEach(app => {
                if (app.querySelector('.ny-schedule-select')) return;
                const button = document.createElement('button');
                button.type = 'button'; button.className = 'ny-schedule-select'; button.textContent = labels.select;
                button.addEventListener('click', () => { selected = app; updateSelection(); });
                app.appendChild(button);
            });
            updateSelection();
        }
        const captcha = root.querySelector('.g-recaptcha');
        if (captcha && root.clientWidth > 0) captcha.style.transform = `scale(${Math.min(1, (root.clientWidth - 32) / 304)})`;
    }
    function updateSelection() {
        root?.querySelectorAll('.ny-schedule-select').forEach(b =>
            b.setAttribute('aria-pressed', String(b.closest('.app-container') === selected)));
    }
    function adapt() {
        if (createPath.test(location.pathname)) {
            root = document.querySelector('#create-app-form')?.parentElement || null; stage = 'CREATE';
        } else if (apiPath.test(location.pathname)) {
            root = document.querySelector('#created-apps-wrapper'); stage = 'TOKEN';
        } else if (['/login', '/signup'].includes(location.pathname)) {
            root = document.querySelector('.form-container');
            const action = new URLSearchParams(location.search).get('stage');
            stage = action === 'reset-password' ? 'RESET' : action === 'resend-email' ? 'VERIFY' :
                location.pathname === '/login' ? 'LOGIN' : 'ACCOUNT';
        }
        crop();
    }
    function state() {
        const settings = Array.from(document.querySelectorAll('a[href]')).map(a => safePath(a.href))
            .find(path => /^\/users\/[^/]+\/settings(?:\/api)?$/.test(path));
        const api = settings ? settings.replace(/\/api$/, '') + '/api' : '';
        let target = '';
        if (!full && stage === 'OTHER' && location.pathname === '/' && api) target = api;
        if (!full && root && stage === 'TOKEN' && !root.querySelector('.app-token')) {
            const create = root.querySelector('#create-app-button');
            const path = create && safePath(create.href);
            if (createPath.test(path || '')) target = path;
        }
        return { stage, cropped: !!root && !full, api, target,
            tokenAvailable: !!selected?.isConnected && !!selected.querySelector('.app-token')?.textContent.trim() };
    }
    window.nyanimeScheduleSetup = {
        state,
        full: function (value) { full = !!value; crop(); return state(); },
        // Called only by the explicit native Connect action, never by polling or page-load callbacks.
        token: function () {
            if (!apiPath.test(location.pathname) || !selected?.isConnected || !root?.contains(selected)) return '';
            const token = selected.querySelector('.app-token')?.textContent.trim() || '';
            return token.length >= 16 && token.length <= 8192 && !/\s/.test(token) ? token : '';
        }
    };
    adapt();
    // AJAX errors, late CAPTCHA containers and application changes retain the same original handlers.
    const observer = new MutationObserver(() => {
        if (queued) return;
        queued = true;
        setTimeout(() => { observer.disconnect(); adapt(); queued = false;
            observer.observe(document.body, { childList: true, subtree: true }); }, 100);
    });
    observer.observe(document.body, { childList: true, subtree: true });
    return state();
})
