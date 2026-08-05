package de.lazuli.api.cloudsync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadProgressPresenterTest {

    @Test
    void overallFractionIsMonotonicAcrossPhaseBoundaryAndReaches100PercentAtEnd() {
        DownloadProgressPresenter presenter = new DownloadProgressPresenter(100, 400);

        long now = 1_000_000L;
        int previousPercentage = -1;

        // READING_FROM_CLOUD phase progresses from 0 to 100.
        for (long processed = 0; processed <= 100; processed += 20) {
            presenter.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, processed, 100));
            now += 500;
            DownloadProgressPresenter.DownloadDisplayStats stats = presenter.currentStats(now);
            assertThat(stats.percentage()).isGreaterThanOrEqualTo(previousPercentage);
            previousPercentage = stats.percentage();
        }

        // EXTRACTING phase progresses from 0 to 400 (its real total, replacing the placeholder).
        for (long processed = 0; processed <= 400; processed += 80) {
            presenter.onProgress(new RestoreProgress(RestoreProgress.Phase.EXTRACTING, processed, 400));
            now += 1200;
            DownloadProgressPresenter.DownloadDisplayStats stats = presenter.currentStats(now);
            assertThat(stats.percentage()).isGreaterThanOrEqualTo(previousPercentage);
            previousPercentage = stats.percentage();
        }

        DownloadProgressPresenter.DownloadDisplayStats finalStats = presenter.currentStats(now + 1200);
        assertThat(finalStats.percentage()).isEqualTo(100);
        assertThat(finalStats.overallFraction()).isEqualTo(1.0f);
    }

    @Test
    void formatBytesRendersBinaryUnitsWithBoundaryCases() {
        assertThat(DownloadProgressPresenter.formatBytes(0)).isEqualTo("0 B");
        assertThat(DownloadProgressPresenter.formatBytes(999)).isEqualTo("999 B");
        assertThat(DownloadProgressPresenter.formatBytes(1023)).isEqualTo("1023 B");
        assertThat(DownloadProgressPresenter.formatBytes(1024)).isEqualTo("1.0 KB");
        assertThat(DownloadProgressPresenter.formatBytes(512)).isEqualTo("512 B");
        assertThat(DownloadProgressPresenter.formatBytes(1_048_576)).isEqualTo("1.0 MB");
        assertThat(DownloadProgressPresenter.formatBytes((long) (118.0 * 1024 * 1024))).isEqualTo("118.0 MB");
        assertThat(DownloadProgressPresenter.formatBytes(1_073_741_824L)).isEqualTo("1.0 GB");
        assertThat(DownloadProgressPresenter.formatBytes((long) (1.3 * 1024 * 1024 * 1024))).isEqualTo("1.3 GB");
        assertThat(DownloadProgressPresenter.formatBytes((long) (4.2 * 1024))).isEqualTo("4.2 KB");
    }

    @Test
    void etaTextIsCalculatingBeforeTwoSecondsOfDataThenSecondsThenMinutes() {
        DownloadProgressPresenter presenter = new DownloadProgressPresenter(1000, 1000);
        long start = 10_000_000L;
        presenter.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, 0, 1000));

        // Under 2s of elapsed data: always "Calculating..."
        assertThat(presenter.currentStats(start + 500).etaText()).isEqualTo("Calculating...");
        assertThat(presenter.currentStats(start + 1900).etaText()).isEqualTo("Calculating...");

        // At/after 2s, with meaningful progress made, expect a concrete ETA (seconds).
        presenter.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, 500, 1000));
        String eta = presenter.currentStats(start + 4000).etaText();
        assertThat(eta).matches("About \\d+s remaining");

        // A very slow rate (tiny progress over a long elapsed time) should format in minutes.
        DownloadProgressPresenter slow = new DownloadProgressPresenter(1_000_000, 1_000_000);
        long slowStart = 20_000_000L;
        slow.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, 0, 1_000_000));
        slow.currentStats(slowStart); // establishes startMillis at slowStart
        slow.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, 100, 1_000_000));
        String slowEta = slow.currentStats(slowStart + 5000).etaText();
        assertThat(slowEta).matches("About \\d+m remaining");
    }

    @Test
    void etaTextIsGatedToAtMostOncePerSecond() {
        DownloadProgressPresenter presenter = new DownloadProgressPresenter(1000, 1000);
        long start = 30_000_000L;
        presenter.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, 0, 1000));
        presenter.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, 500, 1000));

        String firstEta = presenter.currentStats(start + 3000).etaText();
        String secondEtaLessThan1sLater = presenter.currentStats(start + 3500).etaText();
        assertThat(secondEtaLessThan1sLater).isEqualTo(firstEta);

        // A third call >=1000ms after the first recomputation is allowed to differ (or match; only asserting it re-gates).
        presenter.onProgress(new RestoreProgress(RestoreProgress.Phase.READING_FROM_CLOUD, 999, 1000));
        String thirdEtaAtLeast1sLater = presenter.currentStats(start + 4000).etaText();
        assertThat(thirdEtaAtLeast1sLater).isNotNull();
    }
}
