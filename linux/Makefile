# AWG Bar for Linux: the helper (Go, built here), the userspace tunnel awgcore (Rust) and the app
# (C++/Qt), the last two built in a container of the oldest distribution the packages target.
# Needs Go and Docker, and GitHub credentials that can read artemimennoi/awgcore (private).
#
#   make deb             both packages, amd64 (the only Linux architecture shipped)
#   make deb USERSPACE=amneziawg-go     the fallback: amneziawg-go in the helper, no awgcore
#   make rpm             the Fedora package: the helper and the GNOME Shell extension (gnome/),
#                        no Qt app; USERSPACE works the same
#   make test            helper unit tests, run in a Linux container
#
# Two packages per architecture, so that KDE's Breeze style applies (it exists only for the
# Qt the desktop itself uses):
#   qt5  Ubuntu 22.04 and 24.04, Debian 12 (Plasma 5)   built on Ubuntu 22.04
#   qt6  Debian 13, Ubuntu 25.04 and newer (Plasma 6)   built on Debian 13

VERSION := $(shell cat ../VERSION)
ARCH    ?= amd64
ifneq ($(ARCH),amd64)
$(error Linux packages are amd64 only)
endif
BUILD   := build
DIST    := $(BUILD)/dist
# The package's version: VERSION, unless a local build gives its own (one for testers that has
# to install over a release sorts after it: 0.2.2+awgcore.<rev>).
PKG_VERSION ?= $(VERSION)

QT5_IMAGE := ubuntu:22.04
QT6_IMAGE := debian:13
# Fedora Workstation 43 (GNOME 49) to 45 (GNOME 51), built on the oldest.
RPM_IMAGE := fedora:43

# The userspace tunnel. awgcore, our own core in Rust, runs as a process of its own that the
# helper starts and talks UAPI to. It is built for x86-64-v3 (AVX2); on a CPU without it the helper
# refuses to connect and the app says why. USERSPACE=amneziawg-go builds a package without awgcore
# whose helper has amneziawg-go built in (Go tag amneziawg_go), as CI does: it has no credentials
# for awgcore. The default helper has no amneziawg-go in it.
USERSPACE   ?= awgcore
AWGCORE_URL ?= https://github.com/artemimennoi/awgcore
AWGCORE_REV ?= 30c55273823f9df00c4cc22632442668998d8e1e
AWGCORE_SRC := $(BUILD)/awgcore-src/$(AWGCORE_REV)
# The Rust toolchain awgcore pins, downloaded once per architecture and kept between builds.
RUST_CACHE  := $(BUILD)/rust-$(ARCH)
ifeq ($(USERSPACE),awgcore)
CORE_DEP := $(BUILD)/awgcore-$(ARCH)
GO_TAGS  :=
else ifeq ($(USERSPACE),amneziawg-go)
CORE_DEP :=
GO_TAGS  := amneziawg_go
else
$(error USERSPACE must be awgcore or amneziawg-go)
endif

# Modules linked into the helper (this build's tags decide which); their license texts ship in
# the package.
GO_MODULES = GOOS=linux GOARCH=$(ARCH) go list -deps -tags '$(GO_TAGS)' \
             -f '{{with .Module}}{{if not .Main}}{{.Path}}{{end}}{{end}}' ./helper | sort -u

.PHONY: all helper licenses awgcore deb deb-qt5 deb-qt6 rpm test clean

all: deb

helper:
	mkdir -p $(BUILD)
	CGO_ENABLED=0 GOOS=linux GOARCH=$(ARCH) go build -trimpath -ldflags='-s -w' -tags '$(GO_TAGS)' \
		-o $(BUILD)/awgbar-helper-$(ARCH) ./helper

# One shallow fetch of the pinned commit; the stamp names it, so changing AWGCORE_REV fetches again.
$(AWGCORE_SRC)/.fetched:
	mkdir -p $(AWGCORE_SRC)
	git -C $(AWGCORE_SRC) init -q
	git -C $(AWGCORE_SRC) fetch -q --depth 1 $(AWGCORE_URL) $(AWGCORE_REV)
	git -C $(AWGCORE_SRC) checkout -q -f FETCH_HEAD
	test "$$(git -C $(AWGCORE_SRC) rev-parse HEAD)" = "$(AWGCORE_REV)"
	touch $@

# Vendored crates and a pinned toolchain: cargo builds offline from the checkout. Built on Ubuntu
# 22.04 (glibc 2.35), so the one binary runs under both packages. The revision goes into
# `awgcore --version`.
awgcore: $(BUILD)/awgcore-$(ARCH)

$(BUILD)/awgcore-$(ARCH): $(AWGCORE_SRC)/.fetched packaging/build-awgcore.sh
	mkdir -p $(RUST_CACHE)
	docker run --rm --platform linux/$(ARCH) -v "$(CURDIR)/$(AWGCORE_SRC):/awgcore" \
		-v "$(CURDIR)/$(RUST_CACHE):/rust" -v "$(CURDIR)/$(BUILD):/out" \
		-v "$(CURDIR)/packaging:/packaging:ro" -e AWGCORE_REV=$(shell printf %.7s $(AWGCORE_REV)) \
		$(QT5_IMAGE) bash /packaging/build-awgcore.sh /out/awgcore-$(ARCH)

licenses:
	rm -rf $(BUILD)/licenses && mkdir -p $(BUILD)/licenses
	for m in $$($(GO_MODULES)); do \
		dir=$$(go list -m -f '{{.Dir}}' $$m) && \
		cp "$$(ls "$$dir"/LICENSE* | head -1)" "$(BUILD)/licenses/$$(echo $$m | tr / _).txt" || exit 1; \
	done

deb: deb-qt5 deb-qt6

deb-qt5: helper licenses $(CORE_DEP)
	docker run --rm --platform linux/$(ARCH) -v "$(CURDIR)/..:/src" $(QT5_IMAGE) \
		bash /src/linux/packaging/build-deb.sh qt5 $(PKG_VERSION) /src/linux/$(DIST) $(USERSPACE)

deb-qt6: helper licenses $(CORE_DEP)
	docker run --rm --platform linux/$(ARCH) -v "$(CURDIR)/..:/src" $(QT6_IMAGE) \
		bash /src/linux/packaging/build-deb.sh qt6 $(PKG_VERSION) /src/linux/$(DIST) $(USERSPACE)

rpm: helper licenses $(CORE_DEP)
	docker run --rm --platform linux/$(ARCH) -v "$(CURDIR)/..:/src" $(RPM_IMAGE) \
		bash /src/linux/packaging/rpm/build-rpm.sh $(PKG_VERSION) /src/linux/$(DIST) $(USERSPACE)

test:
	mkdir -p $(BUILD)
	CGO_ENABLED=0 GOOS=linux GOARCH=$(ARCH) go test -c -o $(BUILD)/helper.test ./helper
	docker run --rm --platform linux/$(ARCH) -v "$(CURDIR)/$(BUILD):/t" -w /t debian:12 ./helper.test

clean:
	rm -rf $(BUILD)
