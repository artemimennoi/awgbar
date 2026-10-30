cmake_minimum_required(VERSION 3.16)
# One version for all AWG Bar clients, in the repository's VERSION file.
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/../../VERSION" AWGBAR_RELEASE LIMIT_COUNT 1)
project(awgbar VERSION ${AWGBAR_RELEASE} LANGUAGES CXX)

# Built against the Qt the distribution's Plasma uses, so the Breeze style applies:
# Qt 5 for Plasma 5 (Ubuntu 22.04/24.04, Debian 12), Qt 6 for Plasma 6 (Debian 13, Ubuntu 25.04+).
set(AWGBAR_QT "6" CACHE STRING "Qt major version to build against (5 or 6)")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt${AWGBAR_QT} REQUIRED COMPONENTS Widgets Network)

add_executable(awgbar
    src/main.cpp
    src/helperclient.cpp
    src/store.cpp
    src/keyimport.cpp
    src/glyph.cpp
    src/window.cpp
    src/powerbutton.cpp
    src/tray.cpp
    src/l10n.cpp
    src/autostart.cpp
)
target_compile_definitions(awgbar PRIVATE
    AWGBAR_VERSION="${PROJECT_VERSION}"
    QT_NO_CAST_FROM_ASCII
    QT_DISABLE_DEPRECATED_BEFORE=0x050F00
)
target_compile_options(awgbar PRIVATE -Wall -Wextra)
target_link_libraries(awgbar PRIVATE Qt${AWGBAR_QT}::Widgets Qt${AWGBAR_QT}::Network)

install(TARGETS awgbar RUNTIME DESTINATION bin)
