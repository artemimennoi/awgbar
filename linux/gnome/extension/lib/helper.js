// One request per connection to awgbar-helper: a JSON line out, a JSON line back, as the Qt
// app's helperclient.cpp does. See linux/helper/protocol.go for the messages. Shared by the
// extension and its preferences window, which are separate processes; both are clients the
// helper authorises on its own (peer credentials, then polkit).
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';

import {_} from './i18n.js';

export function socketPath() {
    return GLib.getenv('AWGBAR_SOCKET') || '/run/awgbar/helper.sock';
}

export class HelperReply {
    constructor(json, transportError = '') {
        this.json = json ?? {};
        // Set when the helper couldn't be reached at all, as opposed to a request it refused.
        this.transportError = transportError;
    }

    get ok() {
        return !this.transportError && this.json.ok === true;
    }

    get error() {
        if (this.transportError)
            return this.transportError;
        return this.json.error || _('Helper error');
    }

    get code() {
        return this.json.code ?? '';
    }
}

function parse(line) {
    try {
        const json = JSON.parse(line.trim());
        if (json && typeof json === 'object' && !Array.isArray(json))
            return new HelperReply(json);
    } catch {
    }
    return new HelperReply(null, _('The helper sent a broken reply'));
}

export class HelperClient {
    constructor() {
        this._pending = new Set();
    }

    // `up` can take a while (name lookup, a polkit password prompt), hence the long default.
    // Never rejects: a failure comes back as a reply with transportError set.
    send(request, timeoutMs = 120000) {
        return new Promise(resolve => {
            const call = {cancellable: new Gio.Cancellable(), timer: 0, connection: null};
            const finish = reply => {
                if (!this._pending.delete(call))
                    return;
                if (call.timer)
                    GLib.source_remove(call.timer);
                call.cancellable.cancel();
                call.connection?.close(null);
                resolve(reply);
            };
            this._pending.add(call);
            call.timer = GLib.timeout_add(GLib.PRIORITY_DEFAULT, timeoutMs, () => {
                call.timer = 0;
                finish(new HelperReply(null, _('The helper did not answer in time')));
                return GLib.SOURCE_REMOVE;
            });

            const address = Gio.UnixSocketAddress.new(socketPath());
            new Gio.SocketClient().connect_async(address, call.cancellable, (client, result) => {
                try {
                    call.connection = client.connect_finish(result);
                } catch (e) {
                    finish(new HelperReply(null, e.message));
                    return;
                }
                const data = new TextEncoder().encode(`${JSON.stringify(request)}\n`);
                this._write(call, call.connection.get_output_stream(), data, error => {
                    if (error) {
                        finish(new HelperReply(null, error.message));
                        return;
                    }
                    this._read(call, finish);
                });
            });
        });
    }

    _write(call, stream, data, done) {
        stream.write_bytes_async(new GLib.Bytes(data), GLib.PRIORITY_DEFAULT, call.cancellable, (s, result) => {
            let written;
            try {
                written = s.write_bytes_finish(result);
            } catch (e) {
                done(e);
                return;
            }
            if (written < data.length)
                this._write(call, stream, data.subarray(written), done);
            else
                done(null);
        });
    }

    _read(call, finish) {
        const input = new Gio.DataInputStream({
            base_stream: call.connection.get_input_stream(),
            close_base_stream: false,
        });
        input.read_line_async(GLib.PRIORITY_DEFAULT, call.cancellable, (stream, result) => {
            let line;
            try {
                [line] = stream.read_line_finish_utf8(result);
            } catch (e) {
                finish(new HelperReply(null, e.message));
                return;
            }
            if (line === null || line === '')
                finish(new HelperReply(null, _('The helper closed the connection')));
            else
                finish(parse(line));
        });
    }

    // Drops every request in flight; their promises never resolve.
    destroy() {
        for (const call of [...this._pending]) {
            this._pending.delete(call);
            if (call.timer)
                GLib.source_remove(call.timer);
            call.cancellable.cancel();
            call.connection?.close(null);
        }
    }
}

export function request(command, name) {
    const r = {command};
    if (name)
        r.name = name;
    return r;
}
