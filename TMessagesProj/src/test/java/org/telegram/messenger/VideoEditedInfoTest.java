package org.telegram.messenger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class VideoEditedInfoTest {

    @Test
    public void roundVideoWithoutEditsDoesNotNeedConversion() {
        VideoEditedInfo info = roundVideoInfo(640, 360, 640, 360);

        assertFalse(info.needConvert());
    }

    @Test
    public void resizedRoundVideoNeedsConversion() {
        VideoEditedInfo info = roundVideoInfo(1920, 1080, 640, 360);

        assertTrue(info.needConvert());
    }

    @Test
    public void trimmedRoundVideoNeedsConversion() {
        VideoEditedInfo info = roundVideoInfo(640, 360, 640, 360);
        info.estimatedDuration = 60_000;
        info.endTime = 60_000_000;

        assertTrue(info.needConvert());
    }

    @Test
    public void squareCropForRoundVideoNeedsConversion() {
        VideoEditedInfo info = roundVideoInfo(640, 360, 640, 360);
        info.cropState = new MediaController.CropState();
        info.cropState.cropPw = 360f / 640f;
        info.cropState.transformWidth = 360;
        info.cropState.transformHeight = 360;

        assertTrue(info.needConvert());
    }

    private static VideoEditedInfo roundVideoInfo(int originalWidth, int originalHeight, int resultWidth, int resultHeight) {
        VideoEditedInfo info = new VideoEditedInfo();
        info.roundVideo = true;
        info.startTime = -1;
        info.endTime = -1;
        info.originalWidth = originalWidth;
        info.originalHeight = originalHeight;
        info.resultWidth = resultWidth;
        info.resultHeight = resultHeight;
        return info;
    }
}
