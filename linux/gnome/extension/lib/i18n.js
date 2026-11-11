// The extension's gettext domain, for modules shared by the Shell and the preferences window.
// Both bind it to the extension's locale directory (metadata.json's gettext-domain).
import Gettext from 'gettext';

const domain = Gettext.domain('awgbar');

export const _ = domain.gettext;

// "%s" placeholders, in order; String.prototype.format exists only in the Shell.
export function format(text, ...args) {
    return text.replace(/%s/g, () => args.shift() ?? '');
}
