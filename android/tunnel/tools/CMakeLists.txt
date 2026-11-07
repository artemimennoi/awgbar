# SPDX-License-Identifier: Apache-2.0
#
# Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
#
# Trimmed from amneziawg-android: only the userspace backend, no root (wg-quick) tools.
#
# libwg-go.so is the tunnel library GoBackend loads: awgcore, our own core in Rust, by default,
# or amneziawg-go, the fallback, with -DAWG_CORE=go (Gradle: -PawgCore=go).

cmake_minimum_required(VERSION 3.4.1)
project("AmneziaWG")
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
add_link_options(LINKER:--build-id=none)
add_link_options(-Wl,-z,max-page-size=16384)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

set(AWG_CORE "awgcore" CACHE STRING "The tunnel library: awgcore or go")

if(AWG_CORE STREQUAL "awgcore")
add_custom_target(libwg-go.so WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/awgcore" COMMENT "Building awgcore" VERBATIM COMMAND "${ANDROID_HOST_PREBUILTS}/bin/make"
    ANDROID_ARCH_NAME=${ANDROID_ARCH_NAME}
    ANDROID_PLATFORM_LEVEL=${ANDROID_PLATFORM_LEVEL}
    ANDROID_TOOLCHAIN_ROOT=${ANDROID_TOOLCHAIN_ROOT}
    AWGCORE_SRC=${AWGCORE_SRC}
    GRADLE_USER_HOME=${GRADLE_USER_HOME}
    DESTDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
)
elseif(AWG_CORE STREQUAL "go")
add_custom_target(libwg-go.so WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/libwg-go" COMMENT "Building amneziawg-go" VERBATIM COMMAND "${ANDROID_HOST_PREBUILTS}/bin/make"
    ANDROID_ARCH_NAME=${ANDROID_ARCH_NAME}
    ANDROID_PACKAGE_NAME=${ANDROID_PACKAGE_NAME}
    GRADLE_USER_HOME=${GRADLE_USER_HOME}
    CC=${CMAKE_C_COMPILER}
    CFLAGS=${CMAKE_C_FLAGS}
    LDFLAGS=${CMAKE_SHARED_LINKER_FLAGS}
    SYSROOT=${CMAKE_SYSROOT}
    TARGET=${CMAKE_C_COMPILER_TARGET}
    DESTDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
    BUILDDIR=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/../generated-src
)
else()
message(FATAL_ERROR "AWG_CORE must be awgcore or go, not ${AWG_CORE}")
endif()
