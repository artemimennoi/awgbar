package org.amnezia.awg;

import androidx.annotation.Nullable;

public class GoBackend {
    @Nullable
    public static native String awgGetConfig(int handle);

    public static native int awgGetSocketV4(int handle);

    public static native int awgGetSocketV6(int handle);

    public static native void awgTurnOff(int handle);

    public static native int awgTurnOn(String ifName, int tunFd, String settings);

    // An underlying network came, went or changed. awgcore has its peers hear from it at once;
    // amneziawg-go ignores it.
    public static native void awgNetworkChanged(int handle);

    public static native String awgVersion();
}
