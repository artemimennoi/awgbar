# AWG Bar for Linux: the helper (Go, built here) and the app (C++/Qt, built in a container of
# the oldest distribution each package targets). Needs Go and Docker.
#
#   make deb             both packages for ARCH (amd64 by default)
#   make deb ARCH=arm64
#   make test            helper unit tests, run in a Linux container
#
# Two packages per architecture, so that KDE's Breeze style applies (it exists only for the
# Qt the desktop itself uses):
#   qt5  Ubuntu 22.04 and 24.04, Debian 12 (Plasma 5)   built on Ubuntu 22.04
#   qt6  Debian 13, Ubuntu 25.04 and newer (Plasma 6)   built on Debian 13

VERSION := $(shell cat ../VERSION)
ARCH    ?= amd64
BUILD   := build
DIST    := $(BUILD)/dist

QT5_IMAGE := ubuntu:22.04
QT6_IMAGE := debian:13

# Modules linked into the helper; their license texts ship in the package.
GO_LICENSES := github.com/amnezia-vpn/amneziawg-go/v3 github.com/godbus/dbus/v5 github.com/vishvananda/netlink \
               github.com/vishvananda/netns github.com/google/nftables github.com/mdlayher/netlink \
               github.com/mdlayher/socket golang.org/x/crypto golang.org/x/net golang.org/x/sys

.PHONY: all helper licenses deb deb-qt5 deb-qt6 test clean

all: deb

helper:
	mkdir -p $(BUILD)
	CGO_ENABLED=0 GOOS=linux GOARCH=$(ARCH) go build -trimpath -ldflags='-s -w' -o $(BUILD)/awgbar-helper-$(ARCH) ./helper

licenses:
	rm -rf $(BUILD)/licenses && mkdir -p $(BUILD)/licenses
	for m in $(GO_LICENSES); do \
		dir=$$(go list -m -f '{{.Dir}}' $$m) && \
		cp "$$(ls "$$dir"/LICENSE* | head -1)" "$(BUILD)/licenses/$$(echo $$m | tr / _).txt" || exit 1; \
	done

deb: deb-qt5 deb-qt6

deb-qt5: helper licenses
	docker run --rm --platform linux/$(ARCH) -v "$(CURDIR)/..:/src" $(QT5_IMAGE) \
		bash /src/linux/packaging/build-deb.sh qt5 $(VERSION) /src/linux/$(DIST)

deb-qt6: helper licenses
	docker run --rm --platform linux/$(ARCH) -v "$(CURDIR)/..:/src" $(QT6_IMAGE) \
		bash /src/linux/packaging/build-deb.sh qt6 $(VERSION) /src/linux/$(DIST)

test:
	mkdir -p $(BUILD)
	CGO_ENABLED=0 GOOS=linux GOARCH=$(ARCH) go test -c -o $(BUILD)/helper.test ./helper
	docker run --rm --platform linux/$(ARCH) -v "$(CURDIR)/$(BUILD):/t" -w /t debian:12 ./helper.test

clean:
	rm -rf $(BUILD)
