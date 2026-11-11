// AWG Bar for GNOME: a top bar indicator in front of awgbar-helper, the root service that runs
// the tunnel. Its menu is laid out like the macOS menu and the Qt app's window, with GNOME
// Shell's own widgets: a header with the status, the round power button, the tunnels, Bypass, one
// info line, and add and settings at the bottom. The extension holds no privileges and no tunnel
// state of its own.
import Clutter from 'gi://Clutter';
import Gio from 'gi://Gio';
import GObject from 'gi://GObject';
import Graphene from 'gi://Graphene';
import Pango from 'gi://Pango';
import St from 'gi://St';

import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Dialog from 'resource:///org/gnome/shell/ui/dialog.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as ModalDialog from 'resource:///org/gnome/shell/ui/modalDialog.js';
import * as PanelMenu from 'resource:///org/gnome/shell/ui/panelMenu.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

import {_, format} from './lib/i18n.js';
import {State, drawLogo} from './lib/logo.js';
import {Helper, Store} from './lib/store.js';

const VERTICAL = Clutter.Orientation.VERTICAL;

// Decimal units, as the Qt app shows them.
function bytes(n) {
    const units = [_('B'), _('KB'), _('MB'), _('GB'), _('TB')];
    let v = n;
    let i = 0;
    while (v >= 1000 && i < units.length - 1) {
        v /= 1000;
        i++;
    }
    const digits = i === 0 || v >= 100 ? 0 : 1;
    return `${v.toLocaleString(undefined, {minimumFractionDigits: digits, maximumFractionDigits: digits})} ${units[i]}`;
}

// "1.2.3.4:51820" → "1.2.3.4", "[2001:db8::1]:51820" → "2001:db8::1".
function host(endpoint) {
    const colon = endpoint.lastIndexOf(':');
    return (colon < 0 ? endpoint : endpoint.slice(0, colon)).replace(/[[\]]/g, '');
}

function label(text, styleClass, props = {}) {
    const l = new St.Label({text, style_class: styleClass, y_align: Clutter.ActorAlign.CENTER, ...props});
    l.clutter_text.ellipsize = Pango.EllipsizeMode.END;
    return l;
}

function iconButton(iconName, accessibleName, styleClass = 'icon-button awgbar-icon-button') {
    return new St.Button({
        style_class: styleClass,
        can_focus: true,
        accessible_name: accessibleName,
        y_align: Clutter.ActorAlign.CENTER,
        child: new St.Icon({icon_name: iconName}),
    });
}

// The macOS menu's power button: the app's shield on a round Shell button, with a soft halo
// breathing behind it while connected (slowly) or busy (quickly), at the macOS pace.
const PowerButton = GObject.registerClass({
    Signals: {'clicked': {}},
}, class PowerButton extends St.Widget {
    _init() {
        super._init({layout_manager: new Clutter.BinLayout(), style_class: 'awgbar-power-area', x_expand: true});
        this._state = State.OFF;

        this._halo = new St.Widget({
            style_class: 'awgbar-halo',
            x_align: Clutter.ActorAlign.CENTER,
            y_align: Clutter.ActorAlign.CENTER,
            pivot_point: new Graphene.Point({x: 0.5, y: 0.5}),
        });
        this.add_child(this._halo);

        this._shield = new St.DrawingArea({style_class: 'awgbar-shield'});
        this._shield.connect('repaint', area => this._repaint(area));
        this._button = new St.Button({
            style_class: 'button awgbar-power',
            can_focus: true,
            x_align: Clutter.ActorAlign.CENTER,
            y_align: Clutter.ActorAlign.CENTER,
            child: this._shield,
        });
        this._button.connect('clicked', () => this.emit('clicked'));
        // The shield follows the button's text color (hover, insensitive, light or dark Shell).
        this._button.connect('style-changed', () => this._shield.queue_repaint());
        this.add_child(this._button);
        // Breathes only while it is on screen (the menu is open): nothing to see otherwise, no
        // reason to wake up. Clutter doesn't animate an actor that isn't mapped yet anyway.
        this._halo.connect('notify::mapped', () => this._updateBreathing());
    }

    get button() {
        return this._button;
    }

    _repaint(area) {
        const cr = area.get_context();
        const [width, height] = area.get_surface_size();
        const fg = this._button.get_theme_node().get_foreground_color();
        const ink = [fg.red / 255, fg.green / 255, fg.blue / 255, fg.alpha / 255];
        if (!this._button.reactive && this._state === State.OFF)
            ink[3] *= 0.5;
        drawLogo(cr, this._state, width, height, ink);
        cr.$dispose();
    }

    setState(state, enabled, accessibleName) {
        this._button.reactive = enabled;
        this._button.can_focus = enabled;
        this._button.accessible_name = accessibleName;
        if (state !== this._state) {
            this._state = state;
            this._halo.remove_style_class_name('busy');
            this._halo.remove_style_class_name('on');
            if (state !== State.OFF)
                this._halo.add_style_class_name(state);
            this._updateBreathing();
        }
        this._shield.queue_repaint();
    }

    _updateBreathing() {
        // Off, the halo has no color (stylesheet.css) and stays still.
        this._halo.remove_all_transitions();
        if (this._state === State.OFF || !this._halo.mapped) {
            this._halo.set_scale(1, 1);
            return;
        }
        // With animations off (the accessibility setting, or software rendering) the halo stays
        // at its fullest instead of vanishing under the button.
        if (!St.Settings.get().enable_animations) {
            this._halo.set_scale(1.14, 1.14);
            return;
        }
        // One breath in and out: 4.4 s connected, 1.4 s busy (MenuView.swift's Halo).
        this._halo.set_scale(0.94, 0.94);
        this._halo.ease({
            scale_x: 1.14,
            scale_y: 1.14,
            duration: this._state === State.BUSY ? 700 : 2200,
            mode: Clutter.AnimationMode.EASE_IN_OUT_SINE,
            repeatCount: -1,
            autoReverse: true,
        });
    }
});

const RenameDialog = GObject.registerClass(
class RenameDialog extends ModalDialog.ModalDialog {
    _init(title, onRename) {
        super._init({styleClass: 'awgbar-dialog'});
        const content = new Dialog.MessageDialogContent({title: _('Rename Tunnel')});
        const row = new St.BoxLayout({style_class: 'awgbar-dialog-row'});
        row.add_child(label(_('Name:'), 'awgbar-dialog-label'));
        this._entry = new St.Entry({style_class: 'awgbar-dialog-entry', text: title, can_focus: true, x_expand: true});
        row.add_child(this._entry);
        content.add_child(row);
        this.contentLayout.add_child(content);

        const rename = () => {
            const text = this._entry.text.trim();
            this.close();
            if (text && text !== title)
                onRename(text);
        };
        this._entry.clutter_text.connect('activate', rename);
        this.setButtons([
            {label: _('Cancel'), action: () => this.close(), key: Clutter.KEY_Escape},
            {label: _('Rename'), action: rename, default: true},
        ]);
        this.setInitialKeyFocus(this._entry.clutter_text);
        this._entry.clutter_text.set_selection(0, -1);
    }
});

const DeleteDialog = GObject.registerClass(
class DeleteDialog extends ModalDialog.ModalDialog {
    _init(title, onDelete) {
        super._init({styleClass: 'awgbar-dialog'});
        this.contentLayout.add_child(new Dialog.MessageDialogContent({
            title: format(_('Delete tunnel %s?'), title),
            description: _('The config will be deleted permanently.'),
        }));
        this.setButtons([
            {label: _('Cancel'), action: () => this.close(), key: Clutter.KEY_Escape, default: true},
            {
                label: _('Delete'),
                action: () => {
                    this.close();
                    onDelete();
                },
            },
        ]);
    }
});

const Indicator = GObject.registerClass(
class Indicator extends PanelMenu.Button {
    _init(extension) {
        super._init(0.5, 'AWG Bar');
        this._extension = extension;
        this._settings = extension.getSettings();
        this._dialog = null;
        this._popup = null;
        // Secondary menus (⋮, +) float over the open menu with a grab of their own, so Escape or
        // a click outside closes only them, as a context menu in the Shell's own entries does.
        this._popupManager = new PopupMenu.PopupMenuManager(this);
        this._tunnelSignature = null;

        this._icon = new St.Icon({style_class: 'system-status-icon'});
        this._icons = {};
        for (const state of Object.values(State)) {
            this._icons[state] = Gio.FileIcon.new(
                extension.dir.get_child('icons').get_child(`awgbar-${state}-symbolic.svg`));
        }
        this.add_child(this._icon);

        this.menu.box.add_style_class_name('awgbar-menu');
        this._build();

        this._store = new Store(this._settings);
        this._store.onChanged(() => this._sync());
        this.menu.connect('open-state-changed', (_menu, open) => {
            // Also emitted while the menu is destroyed with the indicator.
            if (!this._store)
                return;
            this._store.wantStats = open;
            if (open) {
                this._store.refresh();
            } else {
                // Like closing the macOS menu: an unsaved Bypass edit and a secondary menu go away.
                this._revertBypass();
                this._popup?.close();
            }
        });
        this._sync();
    }

    _section(child) {
        const section = new PopupMenu.PopupMenuSection();
        section.actor.add_child(child);
        this.menu.addMenuItem(section);
        return section;
    }

    _build() {
        const content = new St.BoxLayout({orientation: VERTICAL, style_class: 'awgbar-content', x_expand: true});
        this._section(content);

        // Header: the app's name, then a status dot and what the VPN is doing.
        const header = new St.BoxLayout({style_class: 'awgbar-header', x_expand: true});
        header.add_child(label('AWG Bar', 'awgbar-title'));
        header.add_child(new St.Widget({x_expand: true}));
        this._dot = new St.Widget({style_class: 'awgbar-dot', y_align: Clutter.ActorAlign.CENTER});
        header.add_child(this._dot);
        this._status = label('', 'awgbar-status');
        header.add_child(this._status);
        content.add_child(header);

        // The power button, the tunnels and Bypass; a helper problem takes their place.
        this._main = new St.BoxLayout({orientation: VERTICAL, style_class: 'awgbar-main', x_expand: true});
        this._power = new PowerButton();
        this._power.connect('clicked', () => this._store.toggle());
        this._main.add_child(this._power);

        this._tunnelBox = new St.BoxLayout({orientation: VERTICAL, style_class: 'awgbar-tunnel-box', x_expand: true});
        this._tunnelScroll = new St.ScrollView({
            style_class: 'awgbar-tunnels',
            hscrollbar_policy: St.PolicyType.NEVER,
            vscrollbar_policy: St.PolicyType.AUTOMATIC,
            overlay_scrollbars: true,
            x_expand: true,
            child: this._tunnelBox,
        });
        this._main.add_child(this._tunnelScroll);

        this._empty = new St.BoxLayout({orientation: VERTICAL, style_class: 'awgbar-empty', x_expand: true});
        this._emptyTitle = label(_('No tunnels'), 'awgbar-empty-title', {x_align: Clutter.ActorAlign.CENTER});
        this._emptyHint = new St.Label({
            text: _('Copy a vpn://… key or choose a .conf file'),
            style_class: 'awgbar-empty-hint',
            x_align: Clutter.ActorAlign.CENTER,
        });
        this._emptyHint.clutter_text.line_wrap = true;
        this._emptyHint.clutter_text.ellipsize = Pango.EllipsizeMode.NONE;
        this._emptyHint.clutter_text.line_alignment = Pango.Alignment.CENTER;
        const importRow = new St.BoxLayout({style_class: 'awgbar-import-row', x_align: Clutter.ActorAlign.CENTER});
        this._emptyClipboard = new St.Button({style_class: 'button', label: _('From Clipboard'), can_focus: true});
        this._emptyClipboard.connect('clicked', () => this._importFromClipboard());
        this._emptyFile = new St.Button({style_class: 'button', label: _('From File…'), can_focus: true});
        this._emptyFile.connect('clicked', () => this._importFromFile());
        importRow.add_child(this._emptyClipboard);
        importRow.add_child(this._emptyFile);
        this._empty.add_child(this._emptyTitle);
        this._empty.add_child(this._emptyHint);
        this._empty.add_child(importRow);
        this._main.add_child(this._empty);

        // Bypass: the subnets in a text field. Enter or the checkmark applies (an active tunnel
        // keeps running with new rules), Esc puts the saved list back; an empty list reads "Off".
        const bypass = new St.BoxLayout({style_class: 'awgbar-bypass', x_expand: true});
        bypass.add_child(label(_('Bypass'), 'awgbar-bypass-label'));
        this._bypass = new St.Entry({
            style_class: 'awgbar-bypass-entry',
            hint_text: _('Off'),
            can_focus: true,
            x_expand: true,
            y_align: Clutter.ActorAlign.CENTER,
        });
        this._bypass.clutter_text.connect('activate', () => this._applyBypass());
        this._bypass.clutter_text.connect('text-changed', () => this._syncBypassApply());
        this._bypass.clutter_text.connect('key-press-event', (_actor, event) => {
            if (event.get_key_symbol() === Clutter.KEY_Escape && this._bypassEdited()) {
                this._revertBypass();
                return Clutter.EVENT_STOP;
            }
            return Clutter.EVENT_PROPAGATE;
        });
        bypass.add_child(this._bypass);
        this._bypassApply = iconButton('object-select-symbolic', _('Apply'));
        this._bypassApply.connect('clicked', () => this._applyBypass());
        this._bypassApply.visible = false;
        bypass.add_child(this._bypassApply);
        this._main.add_child(bypass);
        content.add_child(this._main);

        // The helper isn't usable.
        this._problem = new St.BoxLayout({orientation: VERTICAL, style_class: 'awgbar-problem', x_expand: true});
        this._problemTitle = new St.Label({style_class: 'awgbar-problem-title'});
        this._problemTitle.clutter_text.line_wrap = true;
        this._problemHint = new St.Label({style_class: 'awgbar-problem-hint'});
        this._problemHint.clutter_text.line_wrap = true;
        this._problemCommand = new St.Entry({
            style_class: 'awgbar-problem-command',
            text: 'sudo systemctl enable --now awgbar-helper',
            can_focus: true,
        });
        this._problemCommand.clutter_text.editable = false;
        this._problemDetail = new St.Label({style_class: 'awgbar-problem-detail'});
        this._problemDetail.clutter_text.line_wrap = true;
        for (const w of [this._problemTitle, this._problemHint, this._problemCommand, this._problemDetail])
            this._problem.add_child(w);
        content.add_child(this._problem);

        // One line for what matters most right now: an error or notice, an outdated service, the
        // traffic, or where Connect goes. Always the same height, so the menu doesn't jump.
        const info = new St.BoxLayout({style_class: 'awgbar-info', x_expand: true});
        this._infoIcon = new St.Icon({style_class: 'awgbar-info-icon', y_align: Clutter.ActorAlign.CENTER});
        info.add_child(this._infoIcon);
        this._infoText = label('', 'awgbar-info-text', {x_expand: true});
        info.add_child(this._infoText);
        this._traffic = label('', 'awgbar-traffic');
        info.add_child(this._traffic);
        this._copyLog = new St.Button({style_class: 'button awgbar-copy-log', label: _('Copy Log'), can_focus: true});
        this._copyLog.connect('clicked', () => this._copyErrorLog());
        info.add_child(this._copyLog);
        this._dismiss = iconButton('window-close-symbolic', _('Close'), 'icon-button awgbar-icon-button awgbar-dismiss');
        this._dismiss.connect('clicked', () => this._store.dismiss());
        info.add_child(this._dismiss);
        content.add_child(info);

        // Add a tunnel on the left, settings on the right.
        const footer = new St.BoxLayout({style_class: 'awgbar-footer', x_expand: true});
        this._add = iconButton('list-add-symbolic', _('Add Tunnel'));
        this._add.connect('clicked', () => this._togglePopup(this._add, [
            {text: _('From Clipboard'), icon: 'edit-paste-symbolic', action: () => this._importFromClipboard()},
            {text: _('From File…'), icon: 'document-open-symbolic', action: () => this._importFromFile()},
        ], 0));
        footer.add_child(this._add);
        footer.add_child(new St.Widget({x_expand: true}));
        this._menuButton = iconButton('emblem-system-symbolic', _('Settings'));
        this._menuButton.connect('clicked', () => {
            this.menu.close();
            this._extension.openPreferences();
        });
        footer.add_child(this._menuButton);
        content.add_child(footer);
    }

    // A small menu anchored to `source`, floating over the open menu with its own shadow; the
    // main menu stays open and doesn't move. Clicking the same button again closes it.
    // `alignment` places the button along the small menu's edge: 0 at its left, 1 at its right.
    _togglePopup(source, items, alignment) {
        const reopen = this._popup?.sourceActor !== source;
        this._popup?.close();
        if (!reopen)
            return;
        const popup = new PopupMenu.PopupMenu(source, alignment, St.Side.TOP);
        popup.actor.add_style_class_name('awgbar-popup');
        for (const {text, icon, action} of items) {
            const item = new PopupMenu.PopupImageMenuItem(text, icon);
            item.connect('activate', () => action());
            popup.addMenuItem(item);
        }
        Main.uiGroup.add_child(popup.actor);
        popup.actor.hide();
        this._popupManager.addMenu(popup);
        popup.connect('menu-closed', () => {
            if (this._popup === popup)
                this._popup = null;
            popup.destroy();
        });
        this._popup = popup;
        popup.open();
        // From the keyboard, straight to the first item; with the mouse, Down gets there.
        const event = Clutter.get_current_event();
        const type = event?.type();
        if (type === Clutter.EventType.KEY_PRESS || type === Clutter.EventType.KEY_RELEASE)
            popup.actor.navigate_focus(null, St.DirectionType.TAB_FORWARD, false);
        else
            popup.actor.grab_key_focus();
    }

    _sync() {
        const store = this._store;
        const active = store.active;
        const busy = store.busy;
        const state = busy ? State.BUSY : active ? State.ON : State.OFF;
        const usable = store.usable;

        this._icon.gicon = this._icons[state];

        // Header
        this._dot.remove_style_class_name('busy');
        this._dot.remove_style_class_name('on');
        if (busy)
            this._dot.add_style_class_name('busy');
        else if (active)
            this._dot.add_style_class_name('on');
        const status = busy ? _('Please wait…')
            : active ? format(_('Connected · %s'), active.displayName) : _('Disconnected');
        this._status.text = status;
        this.accessible_name = `AWG Bar — ${status}`;

        // Power button
        this._power.setState(state, usable && !busy && (active !== null || store.selectedTunnel !== null),
            active ? _('Disconnect') : _('Connect'));

        // Helper problems replace the power button, the tunnels and Bypass.
        const problem = [Helper.UNREACHABLE, Helper.DENIED, Helper.UNSUPPORTED].includes(store.helper);
        this._main.visible = !problem;
        this._problem.visible = problem;
        if (problem) {
            const unreachable = store.helper === Helper.UNREACHABLE;
            const unsupported = store.helper === Helper.UNSUPPORTED;
            this._problemTitle.text = unreachable ? _('The AWG Bar service is not running')
                : unsupported ? _('This processor is not supported') : _('Not allowed to control the VPN');
            this._problemHint.text = unsupported
                ? _('AWG Bar needs a processor with AVX2 (x86-64-v3): Intel Core from 2013 (4th generation) on, or AMD from 2015 on. Many Pentium, Celeron and Atom processors lack it.')
                : _('Start it with:');
            this._problemHint.visible = unreachable || unsupported;
            this._problemCommand.visible = unreachable;
            this._problemDetail.text = unsupported ? format(_('Missing: %s'), store.helperProblem) : store.helperProblem;
        }

        this._syncTunnels();
        this._tunnelScroll.visible = store.tunnels.length > 0;
        this._empty.visible = store.tunnels.length === 0;
        for (const b of [this._emptyClipboard, this._emptyFile]) {
            b.reactive = !busy;
            b.can_focus = !busy;
        }

        // Bypass. Only touch the field while the user isn't typing in it.
        this._bypass.reactive = !busy;
        if (!this._bypassFocused()) {
            this._bypass.text = store.excluded.join(', ');
            this._bypassApply.visible = false;
        }

        // Info line
        let info = '';
        let kind = '';
        this._traffic.text = '';
        if (store.error) {
            info = store.error;
            kind = 'error';
            this._infoIcon.icon_name = 'dialog-error-symbolic';
        } else if (store.notice) {
            info = store.notice;
            kind = 'notice';
            this._infoIcon.icon_name = 'help-about-symbolic';
        } else if (store.helper === Helper.OUTDATED) {
            info = _('The AWG Bar service is outdated; reinstall the package');
            kind = 'warning';
            this._infoIcon.icon_name = 'dialog-warning-symbolic';
        } else if (active && store.stats) {
            const s = store.stats;
            info = host(s.endpoint || active.endpoint);
            kind = 'stats';
            this._traffic.text = `↓ ${bytes(s.rxBytes)}    ↑ ${bytes(s.txBytes)}`;
        } else {
            // Where the power button will connect to, dimmed, in the same spot as the connected address.
            const t = active ?? store.selectedTunnel;
            if (t) {
                info = host(t.endpoint);
                kind = 'endpoint';
            }
        }
        this._infoText.text = info;
        for (const k of ['error', 'notice', 'warning', 'stats', 'endpoint'])
            this._infoText.remove_style_class_name(k);
        if (kind)
            this._infoText.add_style_class_name(kind);
        this._infoIcon.visible = kind === 'error' || kind === 'notice' || kind === 'warning';
        for (const k of ['error', 'notice', 'warning'])
            this._infoIcon.remove_style_class_name(k);
        if (this._infoIcon.visible)
            this._infoIcon.add_style_class_name(kind);
        this._traffic.visible = this._traffic.text !== '';
        this._copyLog.visible = kind === 'error' && store.errorLog !== '';
        this._dismiss.visible = kind === 'error' || kind === 'notice';

        // Footer
        this._add.reactive = usable && !busy;
        this._add.can_focus = this._add.reactive;
    }

    // Rows are rebuilt only when something in them changed, so polling doesn't reset hover or focus.
    _syncTunnels() {
        const store = this._store;
        const locked = store.selectionLocked;
        const signature = JSON.stringify([store.selected, locked, store.busy,
            store.tunnels.map(t => [t.name, t.displayName])]);
        if (signature === this._tunnelSignature)
            return;
        this._tunnelSignature = signature;
        this._tunnelBox.destroy_all_children();
        for (const t of store.tunnels)
            this._addTunnelRow(t, locked, store.busy);
    }

    _addTunnelRow(tunnel, locked, busy) {
        const selected = tunnel.name === this._store.selected;
        const box = new St.BoxLayout({style_class: 'awgbar-row-box', x_expand: true});
        // Another tunnel while connected: it can't be chosen, but its ⋮ still works. While busy the
        // whole list is dimmed, its radio dots included, as on macOS.
        const dimmed = busy || locked && !selected;
        const ornament = new St.Icon({
            style_class: 'popup-menu-ornament',
            icon_name: selected ? 'ornament-dot-checked-symbolic' : 'ornament-dot-unchecked-symbolic',
            opacity: dimmed ? 115 : 255,
        });
        box.add_child(ornament);
        box.add_child(label(tunnel.displayName, 'awgbar-row-label', {x_expand: true, opacity: dimmed ? 115 : 255}));
        const more = iconButton('view-more-symbolic', _('More'), 'icon-button awgbar-icon-button awgbar-more');
        more.reactive = !busy;
        const actions = () => this._togglePopup(more, [
            {text: _('Rename…'), icon: 'document-edit-symbolic', action: () => this._rename(tunnel)},
            {text: _('Delete…'), icon: 'user-trash-symbolic', action: () => this._delete(tunnel)},
        ], 1);
        more.connect('clicked', actions);
        box.add_child(more);

        const row = new St.Button({
            style_class: 'popup-menu-item awgbar-row awgbar-tunnel',
            can_focus: !busy,
            x_expand: true,
            child: box,
            accessible_name: tunnel.displayName,
            // A right click opens ⋮'s menu, as the macOS menu's context menu does.
            button_mask: St.ButtonMask.ONE | St.ButtonMask.THREE,
        });
        row.connect('clicked', (_row, button) => {
            if (this._store.busy)
                return;
            if (button === 3) {
                actions();
                return;
            }
            if (selected)
                return;
            if (!this._store.select(tunnel.name) && this._store.active)
                this._store.setNotice(_('Disconnect to switch tunnels'));
        });
        this._tunnelBox.add_child(row);
    }

    _openDialog(dialog) {
        this.menu.close();
        this._dialog?.close();
        this._dialog = dialog;
        dialog.connect('closed', () => {
            if (this._dialog === dialog)
                this._dialog = null;
        });
        dialog.open();
    }

    _rename(tunnel) {
        this._openDialog(new RenameDialog(tunnel.displayName, text => this._store.rename(tunnel.name, text)));
    }

    _delete(tunnel) {
        this._openDialog(new DeleteDialog(tunnel.displayName, () => this._store.remove(tunnel.name)));
    }

    _bypassFocused() {
        return global.stage.get_key_focus() === this._bypass.clutter_text;
    }

    _bypassEdited() {
        return this._bypass.text !== this._store.excluded.join(', ');
    }

    _syncBypassApply() {
        this._bypassApply.visible = this._bypassEdited();
    }

    // The helper checks the list; a bad subnet comes back as an error.
    _applyBypass() {
        this._store.setExcluded(this._bypass.text);
        this._bypassApply.visible = false;
        global.stage.set_key_focus(null);
    }

    _revertBypass() {
        this._bypass.text = this._store.excluded.join(', ');
        this._bypassApply.visible = false;
        if (this._bypassFocused())
            global.stage.set_key_focus(null);
    }

    _copyErrorLog() {
        let text = this._store.error;
        if (this._store.errorLog)
            text += `\n\n${this._store.errorLog}`;
        St.Clipboard.get_default().set_text(St.ClipboardType.CLIPBOARD, text);
    }

    _importFromClipboard() {
        St.Clipboard.get_default().get_text(St.ClipboardType.CLIPBOARD, (_clipboard, text) => {
            if (this._store)
                this._store.importText(text ?? '', 'awg');
        });
    }

    // The Shell has no file chooser; the preferences window opens one (prefs.js).
    _importFromFile() {
        this.menu.close();
        this._settings.set_boolean('choose-file', true);
        this._extension.openPreferences();
    }

    _onDestroy() {
        this._popup?.destroy();
        this._popup = null;
        this._dialog?.close();
        this._dialog = null;
        // The menu goes first: closing it still talks to the store.
        super._onDestroy();
        this._store.destroy();
        this._store = null;
    }
});

export default class AwgBarExtension extends Extension {
    enable() {
        this._indicator = new Indicator(this);
        Main.panel.addToStatusArea(this.uuid, this._indicator);
    }

    // Disabling (the screen locking, logging out, the user turning it off) leaves the VPN as it
    // is: the helper keeps the tunnel, and the indicator shows it again when enabled.
    disable() {
        this._indicator?.destroy();
        this._indicator = null;
    }
}
