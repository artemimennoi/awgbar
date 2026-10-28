#pragma once

#include <QString>

// In-app language switch, like the macOS app: keys are the English strings, the Russian table
// lives in l10n.cpp. The default follows the system language.
namespace L10n {
enum class Language { English, Russian };
Language language();
void setLanguage(Language language);
} // namespace L10n

QString L(const char *english);
