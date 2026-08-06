package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NetworkDiagnosticsTest {
    @Test
    public void calculatesVideoPayloadBitrateInKilobitsPerSecond() {
        assertEquals(8_000.0, NetworkDiagnostics.calculateKbps(1_000_000, 1_000), 0.001);
        assertEquals(NetworkDiagnostics.UNAVAILABLE_RATE,
                NetworkDiagnostics.calculateKbps(1_000, 0), 0.001);
    }

    @Test
    public void calculatesAppTrafficBitrateFromCounterDelta() {
        assertEquals(8_000.0,
                NetworkDiagnostics.calculateRate(2_000_000, 1_000_000, 1_000, 8),
                0.001);
    }

    @Test
    public void calculatesPostRecoveryFrameLossPercentage() {
        assertEquals(2.5, NetworkDiagnostics.calculateLossPercent(3, 120), 0.001);
        assertEquals(0.0, NetworkDiagnostics.calculateLossPercent(0, 0), 0.001);
    }

    @Test
    public void convertsEnetPacketLossScaleToPercent() {
        assertEquals(50.0, NetworkDiagnostics.calculateEnetPacketLossPercent(1 << 15), 0.001);
    }

    @Test
    public void videoStatsCarriesPayloadBytesAcrossWindows() {
        VideoStats first = new VideoStats();
        first.totalVideoBytes = 1234;
        VideoStats second = new VideoStats();
        second.totalVideoBytes = 5678;

        VideoStats combined = new VideoStats();
        combined.add(first);
        combined.add(second);
        assertEquals(6912, combined.totalVideoBytes);

        combined.clear();
        assertEquals(0, combined.totalVideoBytes);
    }
}
