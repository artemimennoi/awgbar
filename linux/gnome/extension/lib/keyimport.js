// Accepts an AmneziaVPN key (vpn://...), AmneziaVPN JSON (a decoded key, a connection export or a
// settings backup) or a plain AmneziaWG .conf text. The same rules as KeyImport.swift and the Qt
// app's keyimport.cpp.
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';

import {_} from './i18n.js';

const hasCI = (text, needle) => text.toLowerCase().includes(needle.toLowerCase());

function parseJson(text) {
    try {
        const value = JSON.parse(text);
        if (value !== null && typeof value === 'object')
            return {value};
    } catch {
    }
    return null;
}

class Walker {
    constructor(suggestedName) {
        this.suggestedName = suggestedName;
        this.result = [];
        this.seen = new Set();
        this.sawAPIConfig = false;
    }

    // AmneziaVPN keeps the ready wg-quick text in containers[].awg.last_config.config, where
    // last_config is itself JSON encoded as a string. Keys, exports and backups nest that at
    // different depths, so walk the whole document, unwrap JSON found in strings, and take every
    // string that is a config. The title is the nearest server `description`.
    walk(node, title) {
        if (Array.isArray(node)) {
            for (const item of node)
                this.walk(item, title);
        } else if (node !== null && typeof node === 'object') {
            if ('api_config' in node || 'auth_data' in node)
                this.sawAPIConfig = true;
            const text = v => (typeof v === 'string' ? v : '');
            const t = text(node.description) || title || text(node.hostName);
            for (const key of Object.keys(node).sort())
                this.walk(node[key], t);
        } else if (typeof node === 'string') {
            const text = node.trim();
            // JSON first: an encoded server list contains "[Interface]" too. A config itself
            // starts with "[Interface]" and never parses as JSON.
            const nested = (text.startsWith('{') || text.startsWith('[')) && parseJson(text);
            if (nested) {
                this.walk(nested.value, title);
            } else if (hasCI(text, '[Interface]') && hasCI(text, '[Peer]')) {
                if (!this.seen.has(text)) {
                    this.seen.add(text);
                    this.result.push({name: title || this.suggestedName, config: `${text}\n`});
                }
            }
        }
    }
}

function configs(doc, suggestedName) {
    const walker = new Walker(suggestedName);
    walker.walk(doc, '');
    if (walker.result.length)
        return {items: walker.result};
    return {
        items: [],
        error: walker.sawAPIConfig
            ? _("This is an Amnezia Premium/Free key: its config is issued by Amnezia's server at connect time and cannot be imported")
            : _('No ready AmneziaWG config inside. If this is an admin key or a backup, connect once in AmneziaVPN and share the connection in AmneziaWG format'),
    };
}

// qUncompress: a 4-byte big-endian length, then a zlib stream.
function inflate(data) {
    const converter = new Gio.ZlibDecompressor({format: Gio.ZlibCompressorFormat.ZLIB});
    const input = new Gio.ConverterInputStream({
        base_stream: Gio.MemoryInputStream.new_from_bytes(new GLib.Bytes(data.subarray(4))),
        converter,
    });
    const chunks = [];
    let size = 0;
    for (;;) {
        const bytes = input.read_bytes(65536, null);
        const n = bytes.get_size();
        if (n === 0)
            break;
        size += n;
        // A real key unpacks to a few KB.
        if (size > 1 << 20)
            throw new Error('too large');
        chunks.push(bytes.toArray());
    }
    const out = new Uint8Array(size);
    let offset = 0;
    for (const c of chunks) {
        out.set(c, offset);
        offset += c.length;
    }
    return out;
}

// vpn:// is base64url of Qt's qCompress output.
function decodeVpnKey(key) {
    const b64 = key.slice('vpn://'.length).replace(/\s/g, '');
    if (!/^[A-Za-z0-9_-]*={0,2}$/.test(b64) || b64.replace(/=+$/, '').length % 4 === 1)
        return {error: _('Broken key: not base64')};
    let standard = b64.replace(/=+$/, '').replace(/-/g, '+').replace(/_/g, '/');
    standard += '='.repeat((4 - standard.length % 4) % 4);
    let data = GLib.base64_decode(standard);
    if (data.length > 6 && data[4] === 0x78) {
        const claimed = ((data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3]) >>> 0;
        if (claimed <= 1 << 20) {
            try {
                const inflated = inflate(data);
                if (inflated.length)
                    data = inflated;
            } catch {
            }
        }
    }
    let text;
    try {
        text = new TextDecoder('utf-8', {fatal: false}).decode(data);
    } catch {
        text = '';
    }
    const doc = parseJson(text);
    return doc ? {doc: doc.value} : {error: _('Broken key: cannot unpack it')};
}

// Returns {items: [{name, config}]} or {items: [], error}.
export function parse(text, suggestedName) {
    const trimmed = text.trim();
    if (trimmed.startsWith('vpn://')) {
        const key = decodeVpnKey(trimmed);
        return key.error ? {items: [], error: key.error} : configs(key.doc, suggestedName);
    }
    const looksLikeJSON = trimmed.startsWith('{') || trimmed.startsWith('[');
    // A .conf starts with "[Interface]" too, so only a successful parse makes it JSON.
    const doc = looksLikeJSON && parseJson(trimmed);
    if (doc)
        return configs(doc.value, suggestedName);
    if (hasCI(trimmed, '[Interface]'))
        return {items: [{name: suggestedName, config: `${trimmed}\n`}]};
    return {
        items: [],
        error: looksLikeJSON
            ? _('The file is not valid JSON')
            : _('Expected a vpn://… key, AmneziaVPN JSON or an AmneziaWG config'),
    };
}

// Enough of Cyrillic for readable ids: "Резерв | #202 · NUE" → "Rezerv-202-NUE".
const transliteration = (() => {
    const from = 'абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґ';
    const to = ['a', 'b', 'v', 'g', 'd', 'e', 'e', 'zh', 'z', 'i', 'y', 'k', 'l', 'm', 'n', 'o', 'p', 'r',
        's', 't', 'u', 'f', 'kh', 'ts', 'ch', 'sh', 'shch', '', 'y', '', 'e', 'yu', 'ya', 'i', 'i', 'ye', 'g'];
    const table = new Map();
    [...from].forEach((ch, i) => {
        table.set(ch, to[i]);
        table.set(ch.toUpperCase(), to[i] ? to[i][0].toUpperCase() + to[i].slice(1) : '');
    });
    return table;
})();

const tunnelNamePattern = /^[a-zA-Z0-9_=+.-]{1,15}$/;

// A tunnel id from a display name: [a-zA-Z0-9_=+.-]{1,15}, unique among `taken` (a Set).
export function tunnelName(title, taken) {
    let latin = '';
    for (const ch of title)
        latin += transliteration.has(ch) ? transliteration.get(ch) : ch;
    latin = latin.normalize('NFD');

    // Anything outside the id charset becomes one "-".
    let base = '';
    for (const ch of latin) {
        if (/^[A-Za-z0-9_=+.]$/.test(ch))
            base += ch;
        else if (/^\p{Mn}$/u.test(ch))
            continue; // diacritics left over from the decomposition
        else if (base && !base.endsWith('-'))
            base += '-';
    }
    base = base.replace(/-+$/, '').slice(0, 15).replace(/-+$/, '');
    if (!base)
        base = 'awg';

    let name = base;
    for (let n = 2; taken.has(name); n++) {
        const suffix = `-${n}`;
        name = base.slice(0, 15 - suffix.length) + suffix;
    }
    if (!tunnelNamePattern.test(name))
        throw new Error(`bad tunnel name ${name}`);
    return name;
}
