
package com.example.google.play.apkx;

/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.google.android.vending.expansion.zipfile.ZipResourceFile;
import com.google.android.vending.expansion.zipfile.ZipResourceFile.ZipEntryRO;
import com.google.android.vending.expansion.downloader.Constants;
import com.google.android.vending.expansion.downloader.DownloadProgressInfo;
import com.google.android.vending.expansion.downloader.DownloaderClientMarshaller;
import com.google.android.vending.expansion.downloader.DownloaderServiceMarshaller;
import com.google.android.vending.expansion.downloader.Helpers;
import com.google.android.vending.expansion.downloader.IDownloaderClient;
import com.google.android.vending.expansion.downloader.IDownloaderService;
import com.google.android.vending.expansion.downloader.IStub;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Messenger;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.design.widget.Snackbar;
import androidx.core.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.CRC32;

/**
 * This is sample code for a project built against the downloader library. It
 * implements the IDownloaderClient that the client marshaler will talk to as
 * messages are delivered from the DownloaderService.
 */
public class SampleDownloaderActivity extends AppCompatActivity implements IDownloaderClient, ActivityCompat.OnRequestPermissionsResultCallback {
    private static final String LOG_TAG = "LVLDownloader";
    private ProgressBar mPB;

    private TextView mStatusText;
    private TextView mProgressFraction;
    private TextView mProgressPercent;
    private TextView mAverageSpeed;
    private TextView mTimeRemaining;

    private View mDashboard;
    private View mCellMessage;

    private Button mPauseButton;
    private Button mWiFiSettingsButton;

    private boolean mStatePaused;
    private int mState;

    private IDownloaderService mRemoteService;

    private IStub mDownloaderClientStub;

    private static final int PERMISSION_STORAGE_READ_REQUEST_CODE = 1;
    private static final int PERMISSION_STORAGE_WRITE_REQUEST_CODE = 2;


    private void setState(int newState) {
        if (mState != newState) {
            mState = newState;
            mStatusText.setText(Helpers.getDownloaderStringResourceIDFromState(newState));
        }
    }

    private void setButtonPausedState(boolean paused) {
        mStatePaused = paused;
        int stringResourceID = paused ? R.string.text_button_resume :
                R.string.text_button_pause;
        mPauseButton.setText(stringResourceID);
    }

    /**
     * This is a little helper class that demonstrates simple testing of an
     * Expansion APK file delivered by Market. You may not wish to hard-code
     * things such as file lengths into your executable... and you may wish to
     * turn this code off during application development.
     */
    private static class XAPKFile {
        public final boolean mIsMain;
        public final int mFileVersion;
        public final long mFileSize;

        XAPKFile(boolean isMain, int fileVersion, long fileSize) {
            mIsMain = isMain;
            mFileVersion = fileVersion;
            mFileSize = fileSize;
        }
    }

    private static final XAPKFile[] xAPKS = {
            new XAPKFile(true, 3, 687801613L)
            // main file only
    };

    /**
     * Go through each of the Expansion APK files defined in the project and
     * determine if the files are present and match the required size. Free
     * applications should definitely consider doing this, as this allows the
     * application to be launched for the first time without having a network
     * connection present. Paid applications that use LVL should probably do at
     * least one LVL check that requires the network to be present, so this is
     * not as necessary.
     *
     * @return true if they are present.
     */
    boolean xAPKFilesDelivered() {
        for (XAPKFile xf : xAPKS) {
            String fileName = Helpers.getExpansionAPKFileName(this, xf.mIsMain, xf.mFileVersion);
            if (!Helpers.doesFileExist(this, fileName, xf.mFileSize, false))
                return false;
        }
        return true;
    }

    boolean xAPKFilesReadable() {
        for (XAPKFile xf : xAPKS) {
            String fileName = Helpers.getExpansionAPKFileName(this, xf.mIsMain, xf.mFileVersion);
            if ( Helpers.getFileStatus(this, fileName) == Helpers.FS_CANNOT_READ ) {
                return false;
            }
        }
        return true;
    }
    /**
     * Calculating a moving average for the validation speed so we don't get
     * jumpy calculations for time etc.
     */
    static private final float SMOOTHING_FACTOR = 0.005f;

    /**
     * Used by the async task
     */
    private boolean mCancelValidation;

    /**
     * Go through each of the Expansion APK files and open each as a zip file.
     * Calculate the CRC for each file and return false if any fail to match.
     *
     * @return true if XAPKZipFile is successful
     */
    void validateXAPKZipFiles() {
        AsyncTask<Object, DownloadProgressInfo, Boolean> validationTask = new
                AsyncTask<Object, DownloadProgressInfo, Boolean>() {

                    @Override
                    protected void onPreExecute() {
                        mDashboard.setVisibility(View.VISIBLE);
                        mCellMessage.setVisibility(View.GONE);
                        mStatusText.setText(R.string.text_verifying_download);
                        mPauseButton.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                mCancelValidation = true;
                            }
                        });
                        mPauseButton.setText(R.string.text_button_cancel_verify);
                        super.onPreExecute();
                    }

                    @Override
                    protected Boolean doInBackground(Object... params) {
                        for (XAPKFile xf : xAPKS) {
                            String fileName = Helpers.getExpansionAPKFileName(
                                    SampleDownloaderActivity.this,
                                    xf.mIsMain, xf.mFileVersion);
                            if (!Helpers.doesFileExist(SampleDownloaderActivity.this, fileName,
                                    xf.mFileSize, false))
                                return false;
                            fileName = Helpers
                                    .generateSaveFileName(SampleDownloaderActivity.this, fileName);
                            ZipResourceFile zrf;
                            byte[] buf = new byte[1024 * 256];
                            try {
                                zrf = new ZipResourceFile(fileName);
                                ZipEntryRO[] entries = zrf.getAllEntries();
                                /**
                                 * First calculate the total compressed length
                                 */
                                long totalCompressedLength = 0;
                                for (ZipEntryRO entry : entries) {
                                    totalCompressedLength += entry.mCompressedLength;
                                }
                                float averageVerifySpeed = 0;
                                long totalBytesRemaining = totalCompressedLength;
                                long timeRemaining;
                                /**
                                 * Then calculate a CRC for every file in the
                                 * Zip file, comparing it to what is stored in
                                 * the Zip directory. Note that for compressed
                                 * Zip files we must extract the contents to do
                                 * this comparison.
                                 */
                                for (ZipEntryRO entry : entries) {
                                    if (-1 != entry.mCRC32) {
                                        long length = entry.mUncompressedLength;
                                        CRC32 crc = new CRC32();
                                        DataInputStream dis = null;
                                        try {
                                            dis = new DataInputStream(
                                                    zrf.getInputStream(entry.mFileName));

                                            long startTime = SystemClock.uptimeMillis();
                                            while (length > 0) {
                                                int seek = (int) (length > buf.length ? buf.length
                                                        : length);
                                                dis.readFully(buf, 0, seek);
                                                crc.update(buf, 0, seek);
                                                length -= seek;
                                                long currentTime = SystemClock.uptimeMillis();
                                                long timePassed = currentTime - startTime;
                                                if (timePassed > 0) {
                                                    float currentSpeedSample = (float) seek
                                                            / (float) timePassed;
                                                    if (0 != averageVerifySpeed) {
                                                        averageVerifySpeed = SMOOTHING_FACTOR
                                                                * currentSpeedSample
                                                                + (1 - SMOOTHING_FACTOR)
                                                                * averageVerifySpeed;
                                                    } else {
                                                        averageVerifySpeed = currentSpeedSample;
                                                    }
                                                    totalBytesRemaining -= seek;
                                                    timeRemaining = (long) (totalBytesRemaining / averageVerifySpeed);
                                                    this.publishProgress(
                                                            new DownloadProgressInfo(
                                                                    totalCompressedLength,
                                                                    totalCompressedLength
                                                                            - totalBytesRemaining,
                                                                    timeRemaining,
                                                                    averageVerifySpeed)
                                                            );
                                                }
                                                startTime = currentTime;
                                                if (mCancelValidation)
                                                    return true;
                                            }
                                            if (crc.getValue() != entry.mCRC32) {
                                                Log.e(Constants.TAG,
                                                        "CRC does not match for entry: "
                                                                + entry.mFileName);
                                                Log.e(Constants.TAG,
                                                        "In file: " + entry.getZipFileName());
                                                return false;
                                            }
                                        } finally {
                                            if (null != dis) {
                                                dis.close();
                                            }
                                        }
                                    }
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                                return false;
                            }
                        }
                        return true;
                    }

                    @Override
                    protected void onProgressUpdate(DownloadProgressInfo... values) {
                        onDownloadProgress(values[0]);
                        super.onProgressUpdate(values);
                    }

                    @Override
                    protected void onPostExecute(Boolean result) {
                        if (result) {
                            mDashboard.setVisibility(View.VISIBLE);
                            mCellMessage.setVisibility(View.GONE);
                            mStatusText.setText(R.string.text_validation_complete);
                            mPauseButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    startMovie();
                                }
                            });
                            mPauseButton.setText(android.R.string.ok);
                        } else {
                            mDashboard.setVisibility(View.VISIBLE);
                            mCellMessage.setVisibility(View.GONE);
                            mStatusText.setText(R.string.text_validation_failed);
                            mPauseButton.setOnClickListener(new View.OnClickListener() {
                                @Override
                                public void onClick(View view) {
                                    finish();
                                }
                            });
                            mPauseButton.setText(android.R.string.cancel);
                        }
                        super.onPostExecute(result);
                    }

                };
        validationTask.execute(new Object());
    }

    /**
     * If the download isn't present, we initialize the download UI. This ties
     * all of the controls into the remote service calls.
     */
    private void initializeDownloadUI() {
        mDownloaderClientStub = DownloaderClientMarshaller.CreateStub
                (this, SampleDownloaderService.class);
        setContentView(R.layout.main);

        mPB = (ProgressBar) findViewById(R.id.progressBar);
        mStatusText = (TextView) findViewById(R.id.statusText);
        mProgressFraction = (TextView) findViewById(R.id.progressAsFraction);
        mProgressPercent = (TextView) findViewById(R.id.progressAsPercentage);
        mAverageSpeed = (TextView) findViewById(R.id.progressAverageSpeed);
        mTimeRemaining = (TextView) findViewById(R.id.progressTimeRemaining);
        mDashboard = findViewById(R.id.downloaderDashboard);
        mCellMessage = findViewById(R.id.approveCellular);
        mPauseButton = (Button) findViewById(R.id.pauseButton);
        mWiFiSettingsButton = (Button) findViewById(R.id.wifiSettingsButton);

        mPauseButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mStatePaused) {
                    mRemoteService.requestContinueDownload();
                } else {
                    mRemoteService.requestPauseDownload();
                }
                setButtonPausedState(!mStatePaused);
            }
        });

        mWiFiSettingsButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });

        Button resumeOnCell = (Button) findViewById(R.id.resumeOverCellular);
        resumeOnCell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mRemoteService.setDownloadFlags(IDownloaderService.FLAGS_DOWNLOAD_OVER_CELLULAR);
                mRemoteService.requestContinueDownload();
                mCellMessage.setVisibility(View.GONE);
            }
        });

    }

    private void launchDownloader() {
        try {
            Intent launchIntent = SampleDownloaderActivity.this
                    .getIntent();
            Intent intentToLaunchThisActivityFromNotification = new Intent(
                    SampleDownloaderActivity
                            .this, SampleDownloaderActivity.this.getClass());
            intentToLaunchThisActivityFromNotification.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    |
                    Intent.FLAG_ACTIVITY_CLEAR_TOP);
            intentToLaunchThisActivityFromNotification.setAction(launchIntent.getAction());

            if (launchIntent.getCategories() != null) {
                for (String category : launchIntent.getCategories()) {
                    intentToLaunchThisActivityFromNotification.addCategory(category);
                }
            }

            if (DownloaderClientMarshaller.startDownloadServiceIfRequired(this,
                    PendingIntent.getActivity(SampleDownloaderActivity
                                    .this,
                            0, intentToLaunchThisActivityFromNotification,
                            PendingIntent.FLAG_UPDATE_CURRENT),
                    SampleDownloaderService.class) != DownloaderClientMarshaller.NO_DOWNLOAD_REQUIRED) {
                initializeDownloadUI();
                return;
            } // otherwise we fall through to starting the movie
        } catch (NameNotFoundException e) {
            Log.e(LOG_TAG, "Cannot find own package! MAYDAY!");
            e.printStackTrace();
        }
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initializeDownloadUI();

        /**
         * Before we do anything, are the files we expect already here and
         * delivered (presumably by Market) For free titles, this is probably
         * worth doing. (so no Market request is necessary)
         */
        if (!xAPKFilesDelivered()) {
            // does our OBB directory exist?
            if (Helpers.canWriteOBBFile(this))
                launchDownloader();
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // we need the write permission first
                requestStorageWritePermission();
            }
        } else if (!xAPKFilesReadable()){
            Log.e(LOG_TAG, "Cannot read APKx File.  Permission Perhaps?");
            if ( ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(LOG_TAG, "Need Permission!");
                requestStorageReadPermission();
            }
        } else {
            validateXAPKZipFiles();
        }
    }

    /**
     * Requests the {@link android.Manifest.permission#CAMERA} permission.
     * If an additional rationale should be displayed, the user has to launch the request from
     * a SnackBar that includes additional information.
     */
    private void requestStorageReadPermission() {
        // Permission has not been granted and must be requested.
        View rootLayout = findViewById(R.id.rootLayout);
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.READ_EXTERNAL_STORAGE)) {
            // Provide an additional rationale to the user if the permission was not granted
            // and the user would benefit from additional context for the use of the permission.
            // Display a SnackBar with a button to request the missing permission.
            Snackbar.make(rootLayout, R.string.read_permission_justification,
                    Snackbar.LENGTH_INDEFINITE).setAction("OK", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    // Request the permission
                    ActivityCompat.requestPermissions(SampleDownloaderActivity.this,
                            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                            PERMISSION_STORAGE_READ_REQUEST_CODE);
                }
            }).show();

        } else {
            Snackbar.make(rootLayout,
                    R.string.read_permission_requesting,
                    Snackbar.LENGTH_SHORT).show();
            // Request the permission. The result will be received in onRequestPermissionResult().
            ActivityCompat.requestPermissions(SampleDownloaderActivity.this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_STORAGE_READ_REQUEST_CODE);
        }
    }

    /**
     * Requests the {@link android.Manifest.permission#CAMERA} permission.
     * If an additional rationale should be displayed, the user has to launch the request from
     * a SnackBar that includes additional information.
     */
    private void requestStorageWritePermission() {
        // Permission has not been granted and must be requested.
        View rootLayout = findViewById(R.id.rootLayout);
        if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            // Provide an additional rationale to the user if the permission was not granted
            // and the user would benefit from additional context for the use of the permission.
            // Display a SnackBar with a button to request the missing permission.
            Snackbar.make(rootLayout, R.string.read_permission_justification,
                    Snackbar.LENGTH_INDEFINITE).setAction("OK", new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    // Request the permission
                    ActivityCompat.requestPermissions(SampleDownloaderActivity.this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            PERMISSION_STORAGE_WRITE_REQUEST_CODE);
                }
            }).show();

        } else {
            Snackbar.make(rootLayout,
                    R.string.read_permission_requesting,
                    Snackbar.LENGTH_SHORT).show();
            // Request the permission. The result will be received in onRequestPermissionResult().
            ActivityCompat.requestPermissions(SampleDownloaderActivity.this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_STORAGE_WRITE_REQUEST_CODE);
        }
    }

    /**
     * Connect the stub to our service on start.
     */
    @Override
    protected void onStart() {
        if (null != mDownloaderClientStub) {
            mDownloaderClientStub.connect(this);
        }
        super.onStart();
    }

    /**
     * Disconnect the stub from our service on stop
     */
    @Override
    protected void onStop() {
        if (null != mDownloaderClientStub) {
            mDownloaderClientStub.disconnect(this);
        }
        super.onStop();
    }

    /**
     * Critical implementation detail. In onServiceConnected we create the
     * remote service and marshaler. This is how we pass the client information
     * back to the service so the client can be properly notified of changes. We
     * must do this every time we reconnect to the service.
     */
    @Override
    public void onServiceConnected(Messenger m) {
        mRemoteService = DownloaderServiceMarshaller.CreateProxy(m);
        mRemoteService.onClientUpdated(mDownloaderClientStub.getMessenger());
    }

    /**
     * The download state should trigger changes in the UI --- it may be useful
     * to show the state as being indeterminate at times. This sample can be
     * considered a guideline.
     */
    @Override
    public void onDownloadStateChanged(int newState) {
        setState(newState);
        boolean showDashboard = true;
        boolean showCellMessage = false;
        boolean paused;
        boolean indeterminate;
        switch (newState) {
            case IDownloaderClient.STATE_IDLE:
                // STATE_IDLE means the service is listening, so it's
                // safe to start making calls via mRemoteService.
                paused = false;
                indeterminate = true;
                break;
            case IDownloaderClient.STATE_CONNECTING:
            case IDownloaderClient.STATE_FETCHING_URL:
                showDashboard = true;
                paused = false;
                indeterminate = true;
                break;
            case IDownloaderClient.STATE_DOWNLOADING:
                paused = false;
                showDashboard = true;
                indeterminate = false;
                break;

            case IDownloaderClient.STATE_FAILED_CANCELED:
            case IDownloaderClient.STATE_FAILED:
            case IDownloaderClient.STATE_FAILED_FETCHING_URL:
            case IDownloaderClient.STATE_FAILED_UNLICENSED:
                paused = true;
                showDashboard = false;
                indeterminate = false;
                break;
            case IDownloaderClient.STATE_PAUSED_NEED_CELLULAR_PERMISSION:
            case IDownloaderClient.STATE_PAUSED_WIFI_DISABLED_NEED_CELLULAR_PERMISSION:
                showDashboard = false;
                paused = true;
                indeterminate = false;
                showCellMessage = true;
                break;
            case IDownloaderClient.STATE_PAUSED_WIFI_DISABLED:
            case IDownloaderClient.STATE_PAUSED_NEED_WIFI:
                showDashboard = false;
                paused = true;
                indeterminate = false;
                showCellMessage = true;
                break;

            case IDownloaderClient.STATE_PAUSED_BY_REQUEST:
                paused = true;
                indeterminate = false;
                break;
            case IDownloaderClient.STATE_PAUSED_ROAMING:
            case IDownloaderClient.STATE_PAUSED_SDCARD_UNAVAILABLE:
                paused = true;
                indeterminate = false;
                break;
            case IDownloaderClient.STATE_COMPLETED:
                showDashboard = false;
                paused = false;
                indeterminate = false;
                validateXAPKZipFiles();
                return;
            default:
                paused = true;
                indeterminate = true;
                showDashboard = true;
        }
        int newDashboardVisibility = showDashboard ? View.VISIBLE : View.GONE;
        if (mDashboard.getVisibility() != newDashboardVisibility) {
            mDashboard.setVisibility(newDashboardVisibility);
        }
        int cellMessageVisibility = showCellMessage ? View.VISIBLE : View.GONE;
        if (mCellMessage.getVisibility() != cellMessageVisibility) {
            mCellMessage.setVisibility(cellMessageVisibility);
        }

        mPB.setIndeterminate(indeterminate);
        setButtonPausedState(paused);
    }

    /**
     * This starts the movie by starting the video player activity with a
     * content URI for our custom APK extension content provider.
     */
    private void startMovie() {
        // launch the movie player activity
        Uri mediaUri = Uri.withAppendedPath(SampleZipFileProvider.ASSET_URI,
                "big_buck_bunny_720p_surround.m4v");
        Intent intent = new Intent();
        intent.setData(mediaUri);
        intent.putExtra(Intent.EXTRA_TITLE, mediaUri.getLastPathSegment());
        intent.setClass(this, SampleVideoPlayerActivity.class);
        startActivity(intent);
        finish();
    }

    /**
     * Sets the state of the various controls based on the progressinfo object
     * sent from the downloader service.
     */
    @Override
    public void onDownloadProgress(DownloadProgressInfo progress) {
        mAverageSpeed.setText(getString(R.string.kilobytes_per_second,
                Helpers.getSpeedString(progress.mCurrentSpeed)));
        mTimeRemaining.setText(getString(R.string.time_remaining,
                Helpers.getTimeRemaining(progress.mTimeRemaining)));

        progress.mOverallTotal = progress.mOverallTotal;
        mPB.setMax((int) (progress.mOverallTotal >> 8));
        mPB.setProgress((int) (progress.mOverallProgress >> 8));
        mProgressPercent.setText(Long.toString(progress.mOverallProgress
                * 100 /
                progress.mOverallTotal) + "%");
        mProgressFraction.setText(Helpers.getDownloadProgressString
                (progress.mOverallProgress,
                        progress.mOverallTotal));
    }

    @Override
    protected void onDestroy() {
        this.mCancelValidation = true;
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_STORAGE_READ_REQUEST_CODE:
                // Request for storage read permission.
                if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission has been granted. Start validating our file.
                    validateXAPKZipFiles();
                } else {
                    // Permission request was denied.
                    Snackbar.make(findViewById(R.id.rootLayout), R.string.read_permission_denied,
                            Snackbar.LENGTH_SHORT)
                            .show();
                }
                break;
            case PERMISSION_STORAGE_WRITE_REQUEST_CODE:
                // Request for storage read permission.
                if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission has been granted. Start validating our file.
                    launchDownloader();
                    mDownloaderClientStub.connect(this);
                } else {
                    // Permission request was denied.
                    Snackbar.make(findViewById(R.id.rootLayout), R.string.read_permission_denied,
                            Snackbar.LENGTH_SHORT)
                            .show();
                }
                break;

        }
    }

}
