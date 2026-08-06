package com.limelight.binding.video;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import com.limelight.binding.audio.AudioDiagnosticsLogger;
import com.limelight.nvstream.jni.MoonBridge;

/** Captures low-overhead network context beside the audio timeline. */
final class NetworkDiagnostics {
    static final double UNAVAILABLE_RATE = -1;
    private static final double ENET_PACKET_LOSS_SCALE = 1 << 16;

    private final Context context;
    private boolean hasTrafficBaseline;
    private long lastTrafficTimestampMs;
    private long lastRxBytes;
    private long lastTxBytes;
    private long lastRxPackets;
    private long lastTxPackets;

    NetworkDiagnostics(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext != null ? applicationContext : context;
    }

    void record(AudioDiagnosticsLogger logger, VideoStats stats,
                int width, int height, int targetFps, int configuredVideoKbps) {
        long sampleDurationMs = Math.max(1,
                SystemClock.uptimeMillis() - stats.measurementStartTimestamp);

        long videoBytes = stats.totalVideoBytes;
        int videoFrames = stats.totalFrames;
        int videoFramesReceived = stats.totalFramesReceived;
        int videoFramesLost = stats.framesLost;
        int videoFrameLossEvents = stats.frameLossEvents;

        logger.recordDeferred("network_stats", () -> captureFields(
                sampleDurationMs,
                videoBytes,
                videoFrames,
                videoFramesReceived,
                videoFramesLost,
                videoFrameLossEvents,
                width,
                height,
                targetFps,
                configuredVideoKbps));
    }

    private Object[] captureFields(long sampleDurationMs,
                                   long videoBytes,
                                   int videoFrames,
                                   int videoFramesReceived,
                                   int videoFramesLost,
                                   int videoFrameLossEvents,
                                   int width,
                                   int height,
                                   int targetFps,
                                   int configuredVideoKbps) {
        long nowMs = SystemClock.elapsedRealtime();

        long rttInfo = MoonBridge.getEstimatedRttInfo();
        long rttMs = rttInfo == -1 ? -1 : rttInfo >>> 32;
        long rttVarianceMs = rttInfo == -1 ? -1 : rttInfo & 0xFFFFFFFFL;

        long controlLossInfo = MoonBridge.getEstimatedControlPacketLossInfo();
        double controlPacketLossPercent = controlLossInfo == -1 ? UNAVAILABLE_RATE :
                calculateEnetPacketLossPercent(controlLossInfo >>> 32);
        double controlPacketLossVariancePercent = controlLossInfo == -1 ? UNAVAILABLE_RATE :
                calculateEnetPacketLossPercent(controlLossInfo & 0xFFFFFFFFL);

        int uid = Process.myUid();
        long rxBytes = TrafficStats.getUidRxBytes(uid);
        long txBytes = TrafficStats.getUidTxBytes(uid);
        long rxPackets = TrafficStats.getUidRxPackets(uid);
        long txPackets = TrafficStats.getUidTxPackets(uid);

        long trafficDurationMs = hasTrafficBaseline ? nowMs - lastTrafficTimestampMs : 0;
        double appRxKbps = calculateRate(rxBytes, lastRxBytes, trafficDurationMs, 8);
        double appTxKbps = calculateRate(txBytes, lastTxBytes, trafficDurationMs, 8);
        double appRxPacketsPerSecond = calculateRate(
                rxPackets, lastRxPackets, trafficDurationMs, 1_000);
        double appTxPacketsPerSecond = calculateRate(
                txPackets, lastTxPackets, trafficDurationMs, 1_000);

        // UID packet counters are unavailable on some vendor builds even when byte counters work.
        // Keep bandwidth sampling active in that case and report packet rates as unavailable.
        hasTrafficBaseline = countersSupported(rxBytes, txBytes);
        lastTrafficTimestampMs = nowMs;
        lastRxBytes = rxBytes;
        lastTxBytes = txBytes;
        lastRxPackets = rxPackets;
        lastTxPackets = txPackets;

        NetworkContext network = captureNetworkContext();
        return new Object[] {
                "sampleDurationMs", sampleDurationMs,
                "rttAvailable", rttInfo != -1,
                "rttMs", rttMs,
                "rttVarianceMs", rttVarianceMs,
                "controlPacketLossAvailable", controlLossInfo != -1,
                "controlPacketLossPercent", round(controlPacketLossPercent),
                "controlPacketLossVariancePercent", round(controlPacketLossVariancePercent),
                "videoWidth", width,
                "videoHeight", height,
                "targetFps", targetFps,
                "configuredVideoKbps", configuredVideoKbps,
                "videoPayloadKbps", round(calculateKbps(videoBytes, sampleDurationMs)),
                "videoFrames", videoFrames,
                "videoFramesReceived", videoFramesReceived,
                "videoFramesLostAfterRecovery", videoFramesLost,
                "videoFrameLossEvents", videoFrameLossEvents,
                "videoFrameLossPercentAfterRecovery", round(calculateLossPercent(
                        videoFramesLost, videoFrames)),
                "appRxKbps", round(appRxKbps),
                "appTxKbps", round(appTxKbps),
                "appRxPacketsPerSecond", round(appRxPacketsPerSecond),
                "appTxPacketsPerSecond", round(appTxPacketsPerSecond),
                "networkTransport", network.transport,
                "networkVpn", network.vpn,
                "wifiRssiDbm", network.wifiRssiDbm,
                "wifiLinkSpeedMbps", network.wifiLinkSpeedMbps,
                "wifiRxLinkSpeedMbps", network.wifiRxLinkSpeedMbps,
                "wifiTxLinkSpeedMbps", network.wifiTxLinkSpeedMbps,
                "wifiFrequencyMhz", network.wifiFrequencyMhz,
                "wifiStandard", network.wifiStandard,
                "rawUdpPacketLossAvailable", false,
                "wifiRetryCountAvailable", false
        };
    }

    static double calculateKbps(long bytes, long durationMs) {
        if (bytes < 0 || durationMs <= 0) {
            return UNAVAILABLE_RATE;
        }
        return bytes * 8.0 / durationMs;
    }

    static double calculateLossPercent(long lost, long total) {
        if (lost < 0 || total <= 0) {
            return 0;
        }
        return lost * 100.0 / total;
    }

    static double calculateEnetPacketLossPercent(long scaledPacketLoss) {
        if (scaledPacketLoss < 0) {
            return UNAVAILABLE_RATE;
        }
        return scaledPacketLoss * 100.0 / ENET_PACKET_LOSS_SCALE;
    }

    static double calculateRate(long current, long previous,
                                long durationMs, long unitsPerSecond) {
        if (current == TrafficStats.UNSUPPORTED || previous == TrafficStats.UNSUPPORTED ||
                current < previous || durationMs <= 0) {
            return UNAVAILABLE_RATE;
        }
        return (current - previous) * (double) unitsPerSecond / durationMs;
    }

    private static boolean countersSupported(long... counters) {
        for (long counter : counters) {
            if (counter == TrafficStats.UNSUPPORTED) {
                return false;
            }
        }
        return true;
    }

    private static double round(double value) {
        if (value < 0) {
            return value;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    @SuppressWarnings("deprecation")
    private NetworkContext captureNetworkContext() {
        NetworkContext result = new NetworkContext();
        ConnectivityManager connectivityManager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        try {
            if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network activeNetwork = connectivityManager.getActiveNetwork();
                NetworkCapabilities capabilities =
                        connectivityManager.getNetworkCapabilities(activeNetwork);
                if (capabilities != null) {
                    result.vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        result.transport = "wifi";
                    }
                    else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        result.transport = "ethernet";
                    }
                    else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        result.transport = "cellular";
                    }
                    else {
                        result.transport = "other";
                    }
                }
            }
            else if (connectivityManager != null) {
                NetworkInfo info = connectivityManager.getActiveNetworkInfo();
                if (info != null) {
                    switch (info.getType()) {
                        case ConnectivityManager.TYPE_WIFI:
                            result.transport = "wifi";
                            break;
                        case ConnectivityManager.TYPE_ETHERNET:
                            result.transport = "ethernet";
                            break;
                        case ConnectivityManager.TYPE_MOBILE:
                            result.transport = "cellular";
                            break;
                        default:
                            result.transport = "other";
                            break;
                    }
                }
            }
        }
        catch (RuntimeException ignored) {
            // Network state is diagnostic-only and must never interrupt streaming.
        }

        if (!"wifi".equals(result.transport)) {
            return result;
        }

        try {
            WifiManager wifiManager =
                    (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiInfo wifiInfo = wifiManager == null ? null : wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                result.wifiRssiDbm = wifiInfo.getRssi();
                result.wifiLinkSpeedMbps = wifiInfo.getLinkSpeed();
                result.wifiFrequencyMhz = wifiInfo.getFrequency();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    result.wifiRxLinkSpeedMbps = wifiInfo.getRxLinkSpeedMbps();
                    result.wifiTxLinkSpeedMbps = wifiInfo.getTxLinkSpeedMbps();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    result.wifiStandard = wifiInfo.getWifiStandard();
                }
            }
        }
        catch (RuntimeException ignored) {
            // Some vendor builds restrict Wi-Fi details even with ACCESS_WIFI_STATE.
        }

        return result;
    }

    private static final class NetworkContext {
        String transport = "unknown";
        boolean vpn;
        int wifiRssiDbm = -1;
        int wifiLinkSpeedMbps = -1;
        int wifiRxLinkSpeedMbps = -1;
        int wifiTxLinkSpeedMbps = -1;
        int wifiFrequencyMhz = -1;
        int wifiStandard = -1;
    }
}
