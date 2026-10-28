#pragma once

#include <QString>

// "Launch at Login" through XDG autostart. The package installs
// /etc/xdg/autostart/awgbar.desktop; a user turns it off with an override in
// ~/.config/autostart that says Hidden=true, which every desktop honours.
namespace Autostart {
bool isEnabled();
// Returns an error message, empty on success.
QString setEnabled(bool enabled);
} // namespace Autostart
