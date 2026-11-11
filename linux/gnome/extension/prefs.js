// The extension's preferences window: adding tunnels from files (the Shell itself has no file
// chooser) or the clipboard, and the settings the Qt app keeps in its settings menu. It is a
// separate process and a client of awgbar-helper of its own, authorised the same way.
import Adw from 'gi://Adw';
import Gdk from 'gi://Gdk';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Gtk from 'gi://Gtk';

import {ExtensionPreferences} from 'resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js';

import {HelperClient, request} from './lib/helper.js';
import {_, format} from './lib/i18n.js';
import {importText} from './lib/importer.js';

// Errors and notices fade out after the same 6 s as in the menu.
const toastSeconds = 6;
const maxFileSize = 1 << 20;

function host(endpoint) {
    const colon = endpoint.lastIndexOf(':');
    return (colon < 0 ? endpoint : endpoint.slice(0, colon)).replace(/[[\]]/g, '');
}

function readFile(file) {
    return new Promise(resolve => {
        file.load_contents_async(null, (f, result) => {
            try {
                const [, contents] = f.load_contents_finish(result);
                if (contents.length > maxFileSize)
                    resolve(null);
                else
                    resolve(new TextDecoder().decode(contents));
            } catch {
                resolve(null);
            }
        });
    });
}

class Window {
    constructor(window, settings) {
        this._window = window;
        this._settings = settings;
        this._client = new HelperClient();
        this._busy = false;
        this._usable = false;
        this._syncingDebug = false;
        // Opened by the menu's "From File…": the window exists for the file chooser only.
        this._forFile = false;

        window.set_default_size(480, 640);
        window.set_search_enabled(false);
        const page = new Adw.PreferencesPage({title: 'AWG Bar', icon_name: 'network-vpn-symbolic'});
        window.add(page);

        this._problem = new Adw.PreferencesGroup({visible: false});
        this._problemRow = new Adw.ActionRow({subtitle_selectable: true, use_markup: false});
        this._problemRow.add_css_class('error');
        this._problem.add(this._problemRow);
        page.add(this._problem);

        const add = new Adw.PreferencesGroup({
            title: _('Add Tunnel'),
            description: _('Copy a vpn://… key or choose a .conf file'),
        });
        this._fileRow = new Adw.ActionRow({title: _('From File…'), activatable: true});
        this._fileRow.add_prefix(new Gtk.Image({icon_name: 'document-open-symbolic'}));
        this._fileRow.connect('activated', () => this._chooseFiles());
        add.add(this._fileRow);
        this._clipboardRow = new Adw.ActionRow({title: _('From Clipboard'), activatable: true});
        this._clipboardRow.add_prefix(new Gtk.Image({icon_name: 'edit-paste-symbolic'}));
        this._clipboardRow.connect('activated', () => this._importClipboard());
        add.add(this._clipboardRow);
        page.add(add);

        this._tunnels = new Adw.PreferencesGroup({title: _('Tunnels')});
        this._tunnelRows = [];
        page.add(this._tunnels);

        const settingsGroup = new Adw.PreferencesGroup();
        this._debugRow = new Adw.SwitchRow({title: _('Debug Log'), subtitle: '/var/log/awgbar-helper.log'});
        this._debugRow.connect('notify::active', () => {
            if (!this._syncingDebug)
                this._setDebugLog(this._debugRow.active);
        });
        settingsGroup.add(this._debugRow);
        page.add(settingsGroup);

        this._settingsId = settings.connect('changed::choose-file', () => this._maybeChooseFile());
        window.connect('close-request', () => {
            settings.disconnect(this._settingsId);
            this._client.destroy();
            return false;
        });
        this._refresh().then(() => this._maybeChooseFile());
    }

    _toast(message) {
        this._window.add_toast(new Adw.Toast({title: GLib.markup_escape_text(message, -1), timeout: toastSeconds}));
    }

    _setSensitive() {
        const usable = this._usable && !this._busy;
        for (const row of [this._fileRow, this._clipboardRow, this._debugRow])
            row.sensitive = usable;
    }

    async _refresh() {
        const version = await this._client.send(request('version'));
        const list = version.ok ? await this._client.send(request('list')) : version;
        this._usable = list.ok;
        if (list.ok) {
            this._problem.visible = false;
            this._apply(list.json);
        } else {
            const unreachable = list.code !== 'denied';
            this._problemRow.title = unreachable
                ? _('The AWG Bar service is not running') : _('Not allowed to control the VPN');
            this._problemRow.subtitle = unreachable
                ? `${_('Start it with:')} sudo systemctl enable --now awgbar-helper\n${list.error}` : list.error;
            this._problem.visible = true;
            this._apply({});
        }
        this._setSensitive();
    }

    _apply(json) {
        for (const row of this._tunnelRows)
            this._tunnels.remove(row);
        this._tunnelRows = (json.tunnels ?? []).map(t => {
            const row = new Adw.ActionRow({title: t.title || t.name, subtitle: host(t.endpoint ?? ''), use_markup: false});
            if (t.active)
                row.add_suffix(new Gtk.Image({icon_name: 'network-vpn-symbolic', tooltip_text: _('Connected')}));
            return row;
        });
        if (!this._tunnelRows.length)
            this._tunnelRows = [new Adw.ActionRow({title: _('No tunnels'), use_markup: false})];
        for (const row of this._tunnelRows)
            this._tunnels.add(row);
        this._syncingDebug = true;
        this._debugRow.active = json.debugLog === true;
        this._syncingDebug = false;
    }

    async _setDebugLog(enabled) {
        const reply = await this._client.send({...request('setDebugLog'), enabled});
        if (reply.ok) {
            this._apply(reply.json);
        } else {
            this._toast(reply.error);
            await this._refresh();
        }
    }

    _maybeChooseFile() {
        if (!this._settings.get_boolean('choose-file'))
            return;
        this._settings.set_boolean('choose-file', false);
        this._forFile = true;
        this._chooseFiles();
    }

    // Imported one after another; a backup can hold several servers.
    async _import(texts) {
        this._busy = true;
        this._setSensitive();
        let problems = false;
        for (const {text, name} of texts) {
            if (text === null) {
                this._toast(format(_('Cannot read %s'), name));
                problems = true;
                continue;
            }
            // eslint-disable-next-line no-await-in-loop
            const result = await importText(this._client, text, name);
            if (result.list)
                this._apply(result.list);
            if (!result.imported.length && result.duplicate) {
                this._toast(format(_('Already added as %s'), result.titles.get(result.duplicate) ?? result.duplicate));
                problems = true;
            }
            if (result.error) {
                this._toast(result.error);
                problems = true;
            }
        }
        this._busy = false;
        await this._refresh();
        return !problems;
    }

    _chooseFiles() {
        const filter = new Gtk.FileFilter();
        for (const pattern of ['*.conf', '*.txt', '*.vpn', '*.json'])
            filter.add_pattern(pattern);
        filter.name = 'AmneziaWG';
        const all = new Gtk.FileFilter({name: '*'});
        all.add_pattern('*');
        const filters = new Gio.ListStore({item_type: Gtk.FileFilter});
        filters.append(filter);
        filters.append(all);
        const dialog = new Gtk.FileDialog({
            title: _('Choose an AmneziaWG .conf or a file with a vpn:// key'),
            modal: true,
            filters,
            default_filter: filter,
            initial_folder: Gio.File.new_for_path(GLib.get_home_dir()),
        });
        const forFile = this._forFile;
        this._forFile = false;
        dialog.open_multiple(this._window, null, async (d, result) => {
            let files;
            try {
                files = d.open_multiple_finish(result);
            } catch {
                // Cancelled.
                if (forFile)
                    this._window.close();
                return;
            }
            const texts = [];
            for (let i = 0; i < files.get_n_items(); i++) {
                const file = files.get_item(i);
                const base = file.get_basename();
                const dot = base.lastIndexOf('.');
                // eslint-disable-next-line no-await-in-loop
                texts.push({text: await readFile(file), name: dot > 0 ? base.slice(0, dot) : base});
            }
            if (await this._import(texts) && forFile)
                this._window.close();
        });
    }

    _importClipboard() {
        Gdk.Display.get_default().get_clipboard().read_text_async(null, async (clipboard, result) => {
            let text;
            try {
                text = clipboard.read_text_finish(result) ?? '';
            } catch {
                text = '';
            }
            await this._import([{text, name: 'awg'}]);
        });
    }
}

export default class AwgBarPreferences extends ExtensionPreferences {
    fillPreferencesWindow(window) {
        window._awgbar = new Window(window, this.getSettings());
    }
}
