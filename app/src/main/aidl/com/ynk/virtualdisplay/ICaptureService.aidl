package com.ynk.virtualdisplay;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
interface ICaptureService {
    ParcelFileDescriptor captureDisplay(int displayId, out Bundle metadata);
    void destroy();
}
