package com.blackboxstream.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.IOException;
import java.net.InetAddress;

final class WifiRoute {
    final InetAddress address;
    final int prefix;
    final Network network;
    WifiRoute(InetAddress address, int prefix, Network network) { this.address = address; this.prefix = prefix; this.network = network; }

    static WifiRoute find(Context context, InetAddress peer) throws IOException {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) throw unavailable();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            LinkProperties links = connectivity.getLinkProperties(network);
            if (caps == null || links == null || !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
            for (LinkAddress link : links.getLinkAddresses()) {
                InetAddress ip = link.getAddress();
                int prefix = link.getPrefixLength();
                if (LanShare.privateV4(ip, false) && prefix > 0 && prefix < 32
                        && (peer == null || LanShare.sameNetwork(ip, peer, prefix))) return new WifiRoute(ip, prefix, network);
            }
        }
        throw unavailable();
    }

    private static IOException unavailable() { return new IOException("Connect both devices to the same private Wi-Fi or Ethernet network. The invitation has to match this local network."); }
}
