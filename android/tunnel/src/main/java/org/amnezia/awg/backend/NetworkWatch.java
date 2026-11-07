/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.backend;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import org.amnezia.awg.util.NonNullForAll;

import androidx.annotation.Nullable;

import static org.amnezia.awg.GoBackend.awgNetworkChanged;

/**
 * Tells the tunnel library when an underlying network comes, goes, changes its addresses or
 * routes, or is unblocked again (after Doze): awgcore then has its peers hear from it at once,
 * so they learn its new address. An app cannot watch this natively since Android 11 (no
 * NETLINK_ROUTE bind, no RTM_GETLINK for apps targeting API 30 and later). Capability changes,
 * such as signal strength, are left out: they come often and change no path.
 */
@NonNullForAll
final class NetworkWatch extends ConnectivityManager.NetworkCallback {
    private static final String TAG = "AmneziaWG/NetworkWatch";
    private final ConnectivityManager cm;
    private final int handle;

    private NetworkWatch(final ConnectivityManager cm, final int handle) {
        this.cm = cm;
        this.handle = handle;
    }

    @Nullable
    static NetworkWatch start(final Context context, final int handle) {
        final ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null)
            return null;
        final NetworkWatch w = new NetworkWatch(cm, handle);
        final NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        try {
            cm.registerNetworkCallback(request, w);
        } catch (final RuntimeException e) {
            Log.e(TAG, "Cannot watch the networks", e);
            return null;
        }
        return w;
    }

    static void stop(@Nullable final NetworkWatch w) {
        if (w == null)
            return;
        try {
            w.cm.unregisterNetworkCallback(w);
        } catch (final RuntimeException ignored) {
        }
    }

    @Override
    public void onAvailable(final Network network) {
        awgNetworkChanged(handle);
    }

    @Override
    public void onLost(final Network network) {
        awgNetworkChanged(handle);
    }

    @Override
    public void onLinkPropertiesChanged(final Network network, final LinkProperties linkProperties) {
        awgNetworkChanged(handle);
    }

    @Override
    public void onBlockedStatusChanged(final Network network, final boolean blocked) {
        if (!blocked)
            awgNetworkChanged(handle);
    }
}
