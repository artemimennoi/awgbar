# Builds AWGBar.app with its own amneziawg-go, awg (wg), awg-quick and a self-contained bash.
# Needs: Swift (Command Line Tools are enough), Go, git, curl.

APP        := build/AWGBar.app
RES        := $(APP)/Contents/Resources
DEPS       := .deps
AWG_GO     := $(DEPS)/amneziawg-go
AWG_TOOLS  := $(DEPS)/amneziawg-tools
BASH_VER   := 5.2.37
BASH_SHA   := 9599b22ecd1d5787ad7d3b7bf0c59f312b3396d1e281175dd1f8a4014da621ff
BASH_DIR   := $(DEPS)/bash-$(BASH_VER)

AWG_GO_REV    ?= b5928efb6ca19f0153958460c3d141f04abc5c2e
AWG_TOOLS_REV ?= ee0f0a9aa34ff0a0da4b3433b9512781cfe02843

VERSION    := $(shell /usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' Resources/Info.plist)
DIST       := build/dist

.PHONY: all app deps install uninstall dist clean distclean

all: app

define fetch_rev
	rm -rf $(1) && mkdir -p $(1)
	git -C $(1) init -q
	git -C $(1) fetch -q --depth 1 $(2) $(3)
	git -C $(1) checkout -q FETCH_HEAD
endef

$(AWG_GO)/amneziawg-go:
	$(call fetch_rev,$(AWG_GO),https://github.com/amnezia-vpn/amneziawg-go,$(AWG_GO_REV))
	$(MAKE) -C $(AWG_GO)

$(AWG_TOOLS)/src/wg:
	$(call fetch_rev,$(AWG_TOOLS),https://github.com/amnezia-vpn/amneziawg-tools,$(AWG_TOOLS_REV))
	$(MAKE) -C $(AWG_TOOLS)/src wg

# Homebrew's bash links Homebrew libraries and lives in a user-writable prefix;
# the helper runs awg-quick as root, so it gets its own bash linked only against system libraries.
$(BASH_DIR)/bash:
	mkdir -p $(DEPS)
	curl -fsSL -o $(DEPS)/bash-$(BASH_VER).tar.gz https://ftp.gnu.org/gnu/bash/bash-$(BASH_VER).tar.gz
	echo "$(BASH_SHA)  $(DEPS)/bash-$(BASH_VER).tar.gz" | shasum -a 256 -c -
	tar -xzf $(DEPS)/bash-$(BASH_VER).tar.gz -C $(DEPS)
	cd $(BASH_DIR) && ./configure --disable-nls --without-bash-malloc >/dev/null && $(MAKE) -j8 >/dev/null

deps: $(AWG_GO)/amneziawg-go $(AWG_TOOLS)/src/wg $(BASH_DIR)/bash

app: deps
	swift build -c release
	rm -rf $(APP)
	mkdir -p $(APP)/Contents/MacOS $(RES)/bin $(RES)/helper
	cp Resources/Info.plist $(APP)/Contents/
	cp .build/release/AWGBar $(APP)/Contents/MacOS/
	cp .build/release/awgbar-helper $(RES)/helper/local.awgbar.helper
	cp Resources/install-helper.sh Resources/uninstall-helper.sh $(RES)/
	cp -R Resources/ru.lproj Resources/AppIcon.icns $(RES)/
	cp $(AWG_GO)/amneziawg-go $(AWG_TOOLS)/src/wg $(BASH_DIR)/bash $(RES)/bin/
	# Fixes leftover /var/run/wireguard paths, keeps awg-quick from blocking under launchd,
	# and routes split-tunnel exclusions like endpoints. See the comments in the patch.
	patch -s -o $(RES)/bin/awg-quick $(AWG_TOOLS)/src/wg-quick/darwin.bash Resources/awg-quick-darwin.patch
	chmod 0755 $(RES)/bin/* $(RES)/*.sh
	codesign --force --sign - $(RES)/bin/* $(RES)/helper/local.awgbar.helper
	codesign --force --sign - $(APP)
	@echo "built $(APP)"

# Same place the DMG installs to, so updating replaces the app instead of adding a second copy.
# /Applications is writable by admin users; 0.0.3 and older installed into ~/Applications.
install: app
	rm -rf $(HOME)/Applications/AWGBar.app
	rm -rf /Applications/AWGBar.app
	cp -R $(APP) /Applications/
	@echo "installed to /Applications/AWGBar.app — open it and press Install Helper"

# Release assets: the app, plus the corresponding source of the GPL parts it ships.
dist: app
	rm -rf $(DIST) && mkdir -p $(DIST)/src $(DIST)/dmg
	# Standard drag-to-Applications disk image.
	cp -R $(APP) $(DIST)/dmg/
	ln -s /Applications $(DIST)/dmg/Applications
	hdiutil create -quiet -volname "awgbar $(VERSION)" -srcfolder $(DIST)/dmg -ov -format UDZO $(DIST)/awgbar-$(VERSION)-macos-arm64.dmg
	rm -rf $(DIST)/dmg
	git -C $(AWG_TOOLS) archive --prefix=amneziawg-tools/ -o "$(CURDIR)/$(DIST)/src/amneziawg-tools-$(AWG_TOOLS_REV).tar.gz" HEAD
	git -C $(AWG_GO) archive --prefix=amneziawg-go/ -o "$(CURDIR)/$(DIST)/src/amneziawg-go-$(AWG_GO_REV).tar.gz" HEAD
	cp $(DEPS)/bash-$(BASH_VER).tar.gz Resources/awg-quick-darwin.patch THIRD_PARTY_NOTICES.md $(DIST)/src/
	tar -czf $(DIST)/awgbar-$(VERSION)-third-party-sources.tar.gz -C $(DIST) src
	rm -rf $(DIST)/src
	cd $(DIST) && shasum -a 256 *.dmg *.tar.gz > SHA256SUMS
	@ls -la $(DIST)

uninstall:
	sudo bash Resources/uninstall-helper.sh
	rm -rf /Applications/AWGBar.app $(HOME)/Applications/AWGBar.app

clean:
	rm -rf build .build

distclean: clean
	rm -rf $(DEPS)
