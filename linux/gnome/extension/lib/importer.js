// Imports the configs in a text through the helper, the way the Qt app's Store::importNext does:
// a backup can hold several servers, so the ones the helper already has are skipped and the rest
// get free names. Shared by the menu (clipboard) and the preferences window (files).
import {request} from './helper.js';
import {parse, tunnelName} from './keyimport.js';

// Resolves to {imported: [names], duplicate: name or '', error: message or '', list: the last
// list response or null, titles: Map name → display name}.
export async function importText(client, text, suggestedName) {
    const result = {imported: [], duplicate: '', error: '', list: null, titles: new Map()};
    const {items, error} = parse(text, suggestedName);
    if (!items.length) {
        result.error = error;
        return result;
    }
    // Names the helper has right now, not as of the last poll: it refuses to replace one.
    const list = await client.send(request('list'));
    if (!list.ok) {
        result.error = list.error;
        return result;
    }
    const taken = new Set();
    for (const t of list.json.tunnels ?? []) {
        taken.add(t.name);
        result.titles.set(t.name, t.title || t.name);
    }
    result.list = list.json;
    for (const item of items) {
        const name = tunnelName(item.name, taken);
        taken.add(name);
        // eslint-disable-next-line no-await-in-loop
        const reply = await client.send({command: 'importConfig', name, config: item.config, title: item.name});
        if (reply.ok) {
            result.imported.push(name);
            result.list = reply.json;
        } else if (reply.code.startsWith('duplicate:')) {
            result.duplicate ||= reply.code.slice('duplicate:'.length);
        } else {
            result.error = reply.error;
        }
    }
    return result;
}
