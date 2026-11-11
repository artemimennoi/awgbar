# AWG Bar for Fedora Workstation: the helper (built by linux/Makefile, not here) and the GNOME Shell
# extension. Built by build-rpm.sh in a container of the oldest Fedora the package targets, which
# stages the files in SOURCES beforehand, so there is nothing to compile here.
#
# The binaries live in /usr/libexec/awgbar, not /usr/lib/awgbar as in the .deb: SELinux labels
# /usr/lib as lib_t, from which systemd starts a service in its own init_t domain; /usr/libexec is
# bin_t, and the helper runs unconfined_service_t like any other third-party root service.

%global uuid awgbar@artemimennoi.github.io
# The binaries come stripped and without build ids from the Go and Rust builds.
%global debug_package %{nil}
%global __brp_strip %{nil}
# No %%changelog: the repository's history is the changelog.
%global source_date_epoch_from_changelog 0

Name:           awgbar
Version:        %{pkg_version}
Release:        %{pkg_release}
Summary:        AmneziaWG VPN client in the GNOME top bar
License:        MIT
URL:            https://github.com/artemimennoi/awgbar
ExclusiveArch:  x86_64

Requires:       systemd
Requires:       polkit
Requires:       dbus
Requires:       gnome-shell >= 49

%description
AWG Bar connects to AmneziaWG servers from the GNOME top bar: import an
AmneziaVPN key (vpn://...) or an AmneziaWG .conf and press the shield.

A small root service runs the tunnel (%{tunnel}) and watches sleep, wake-up
and network changes, so the VPN comes back by itself after the laptop wakes up
or moves to another network.

The menu is a GNOME Shell extension; enable it once per user with
gnome-extensions enable %{uuid}

%prep

%build

%install
install -Dm755 %{_sourcedir}/awgbar-helper %{buildroot}%{_libexecdir}/awgbar/awgbar-helper
if [ -f %{_sourcedir}/awgcore ]; then
    install -Dm755 %{_sourcedir}/awgcore %{buildroot}%{_libexecdir}/awgbar/awgcore
fi
install -Dm644 %{_sourcedir}/awgbar-helper.service %{buildroot}%{_unitdir}/awgbar-helper.service
install -Dm644 %{_sourcedir}/io.github.artemimennoi.awgbar.policy \
    %{buildroot}%{_datadir}/polkit-1/actions/io.github.artemimennoi.awgbar.policy
install -Dm644 %{_sourcedir}/awgbar.svg %{buildroot}%{_datadir}/icons/hicolor/scalable/apps/awgbar.svg
mkdir -p %{buildroot}%{_datadir}/gnome-shell/extensions
cp -r %{_sourcedir}/%{uuid} %{buildroot}%{_datadir}/gnome-shell/extensions/
mkdir -p %{buildroot}%{_licensedir}/%{name}
cp -r %{_sourcedir}/licenses/. %{buildroot}%{_licensedir}/%{name}/

%post
systemctl daemon-reload >/dev/null 2>&1 || :
if [ "$1" -eq 1 ]; then
    systemctl enable --now awgbar-helper.service >/dev/null 2>&1 || :
else
    # On an upgrade the restart reconnects the tunnel that was up; told beforehand, the old
    # helper keeps its rules and DNS in place, so nothing leaks in between.
    %{_libexecdir}/awgbar/awgbar-helper --prepare-restart >/dev/null 2>&1 || :
    systemctl restart awgbar-helper.service >/dev/null 2>&1 || :
fi

%preun
# Stopping the helper disconnects the VPN and undoes its routes and DNS. Upgrades keep it
# running; %%post restarts it with the new binary.
if [ "$1" -eq 0 ]; then
    systemctl disable --now awgbar-helper.service >/dev/null 2>&1 || :
    # Rules, firewall and DNS a crashed helper may have left behind.
    %{_libexecdir}/awgbar/awgbar-helper --cleanup >/dev/null 2>&1 || :
fi

%postun
systemctl daemon-reload >/dev/null 2>&1 || :

%files
%license %{_licensedir}/%{name}
%dir %{_libexecdir}/awgbar
%{_libexecdir}/awgbar/awgbar-helper
%if "%{tunnel_core}" == "awgcore"
%{_libexecdir}/awgbar/awgcore
%endif
%{_unitdir}/awgbar-helper.service
%{_datadir}/polkit-1/actions/io.github.artemimennoi.awgbar.policy
%{_datadir}/icons/hicolor/scalable/apps/awgbar.svg
%{_datadir}/gnome-shell/extensions/%{uuid}
