// The menu's view of the helper, polled every few seconds, and the actions it offers: the Qt
// app's Store and the macOS app's TunnelStore.
import GLib from 'gi://GLib';

import {HelperClient, request} from './helper.js';
import {importText} from './importer.js';
import {_, format} from './i18n.js';

export const Helper = Object.freeze({
    CHECKING: 'checking',
    READY: 'ready',
    UNREACHABLE: 'unreachable',
    DENIED: 'denied',
    OUTDATED: 'outdated',
});

// The protocol version this extension speaks; older helpers get a "reinstall" notice.
const requiredHelperVersion = 1;
const pollSeconds = 3;
// Errors and notices fade out like a notification; a new one restarts the clock.
const messageSeconds = 6;

export class Store {
    constructor(settings) {
        this._settings = settings;
        this._client = new HelperClient();
        this._listeners = [];
        this._refreshing = false;
        this._destroyed = false;
        this._messageTimer = 0;
        this._importing = Promise.resolve();

        this.helper = Helper.CHECKING;
        this.helperProblem = '';
        this.tunnels = [];
        this.selected = settings.get_string('selected');
        this.stats = null;
        this.excluded = [];
        this.debugLog = false;
        this.busy = false;
        this.error = '';
        this.errorLog = '';
        this.notice = '';
        // Traffic counters are only asked for while the menu is open.
        this.wantStats = false;

        this._poll = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, pollSeconds, () => {
            this.refresh();
            return GLib.SOURCE_CONTINUE;
        });
        this.refresh();
    }

    destroy() {
        this._destroyed = true;
        this._listeners = [];
        GLib.source_remove(this._poll);
        if (this._messageTimer)
            GLib.source_remove(this._messageTimer);
        this._client.destroy();
    }

    onChanged(callback) {
        this._listeners.push(callback);
    }

    _changed() {
        if (this._destroyed)
            return;
        for (const callback of this._listeners)
            callback();
    }

    get usable() {
        return this.helper === Helper.READY || this.helper === Helper.OUTDATED;
    }

    get active() {
        return this.tunnels.find(t => t.active) ?? null;
    }

    get selectedTunnel() {
        return this.tunnels.find(t => t.name === this.selected) ?? null;
    }

    // Switching tunnels needs the VPN off first.
    get selectionLocked() {
        return this.busy || this.active !== null;
    }

    _send(req) {
        return this._client.send(req);
    }

    _setMessage(error, log, notice) {
        this.error = error;
        this.errorLog = log;
        this.notice = notice;
        if (this._messageTimer) {
            GLib.source_remove(this._messageTimer);
            this._messageTimer = 0;
        }
        if (error || notice) {
            this._messageTimer = GLib.timeout_add_seconds(GLib.PRIORITY_DEFAULT, messageSeconds, () => {
                this._messageTimer = 0;
                this._setMessage('', '', '');
                return GLib.SOURCE_REMOVE;
            });
        }
        this._changed();
    }

    setError(message, log = '') {
        this._setMessage(message, log, '');
    }

    setNotice(message) {
        this._setMessage('', '', message);
    }

    dismiss() {
        this._setMessage('', '', '');
    }

    _unusable(reply) {
        this.helper = reply.code === 'denied' ? Helper.DENIED : Helper.UNREACHABLE;
        this.helperProblem = reply.error;
        this.tunnels = [];
        this.stats = null;
    }

    async refresh() {
        if (this._refreshing || this.busy || this._destroyed)
            return;
        this._refreshing = true;
        try {
            if (!this.usable) {
                const version = await this._send(request('version'));
                if (!version.ok) {
                    this._unusable(version);
                    return;
                }
                this.helper = (version.json.version ?? 0) < requiredHelperVersion ? Helper.OUTDATED : Helper.READY;
            }
            const list = await this._send(request('list'));
            if (!list.ok) {
                if (list.transportError || list.code === 'denied')
                    this._unusable(list);
                return;
            }
            this._apply(list.json);
            const on = this.active;
            if (on && this.wantStats) {
                const reply = await this._send(request('stats', on.name));
                const s = reply.ok ? reply.json.stats : null;
                this.stats = s ? {endpoint: s.endpoint ?? '', rxBytes: s.rxBytes ?? 0, txBytes: s.txBytes ?? 0} : null;
            } else if (!on) {
                this.stats = null;
            }
        } finally {
            this._refreshing = false;
            this._changed();
        }
    }

    _apply(json) {
        // The helper leaves out empty lists; debugLog is in every list response.
        if ('tunnels' in json || 'debugLog' in json) {
            this.tunnels = (json.tunnels ?? []).map(t => ({
                name: t.name,
                title: t.title ?? '',
                endpoint: t.endpoint ?? '',
                active: t.active === true,
                get displayName() {
                    return this.title || this.name;
                },
            }));
        }
        this.excluded = json.excluded ?? [];
        this.debugLog = json.debugLog === true;

        const on = this.active;
        if (on)
            this.selected = on.name;
        else if (!this.selectedTunnel)
            this.selected = this.tunnels[0]?.name ?? '';
        this._settings.set_string('selected', this.selected);
    }

    // Runs a call that changes something with the busy state on, and surfaces its error.
    async _perform(req) {
        this.busy = true;
        this._setMessage('', '', '');
        const reply = await this._send(req);
        if (this._destroyed)
            return;
        this.busy = false;
        if (reply.ok)
            this._apply(reply.json);
        else
            this.setError(reply.error, reply.json.log ?? '');
        this._changed();
        this.refresh();
    }

    toggle() {
        const on = this.active;
        if (on)
            this._perform(request('down', on.name));
        else if (this.selected)
            this._perform(request('up', this.selected));
    }

    // Returns false when the choice is locked (connected or busy).
    select(name) {
        if (this.selectionLocked)
            return false;
        this.selected = name;
        this._settings.set_string('selected', name);
        this._changed();
        return true;
    }

    rename(name, title) {
        this._perform({...request('rename', name), title});
    }

    remove(name) {
        this._perform(request('delete', name));
    }

    setExcluded(text) {
        const items = text.split(/[,;\s]+/).filter(s => s);
        // Saving an unchanged list would do nothing but flash the busy state.
        if (items.join('\n') === this.excluded.join('\n'))
            return;
        this._perform({...request('setExcluded'), excluded: items});
    }

    setDebugLog(enabled) {
        this._perform({...request('setDebugLog'), enabled});
    }

    // Several texts are imported one after another.
    importText(text, suggestedName) {
        this._importing = this._importing.then(() => this._import(text, suggestedName));
    }

    async _import(text, suggestedName) {
        if (this._destroyed)
            return;
        this.busy = true;
        this._setMessage('', '', '');
        const result = await importText(this._client, text, suggestedName);
        if (this._destroyed)
            return;
        this.busy = false;
        if (result.list)
            this._apply(result.list);
        // The new tunnel is the one Connect brings up next; while connected the choice stays.
        if (result.imported.length) {
            if (!this.active)
                this.selected = result.imported.at(-1);
        } else if (result.duplicate) {
            // Nothing new: point at the copy we already have.
            if (!this.active)
                this.selected = result.duplicate;
            const title = this.tunnels.find(t => t.name === result.duplicate)?.displayName ??
                result.titles.get(result.duplicate) ?? result.duplicate;
            this.setNotice(format(_('Already added as %s'), title));
        }
        if (result.error)
            this.setError(result.error);
        this._settings.set_string('selected', this.selected);
        this._changed();
        this.refresh();
    }
}
