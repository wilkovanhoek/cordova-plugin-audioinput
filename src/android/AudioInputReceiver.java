package com.exelerus.cordova.audioinputcapture;

import android.Manifest;
import android.content.pm.PackageManager;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.content.Context;

import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

public class AudioInputReceiver extends Thread {
    private static final String LOG_TAG = "AudioInputReceiver";

    private final int RECORDING_BUFFER_FACTOR = 5;
    private int inputChannelConfig = AudioFormat.CHANNEL_IN_MONO;
    private int outputChannelConfig = AudioFormat.CHANNEL_OUT_DEFAULT;
    private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private int sampleRateInHz = 44100;
    private int audioSource = 0;

    private boolean startRecording = false;
    private boolean recording = false;
    private boolean finishRecording = false;

    private String folderPath = "";
    private String fileName = "";

    private boolean monitoring = false;
    private int monitorSampleRate = 1;

    private static final Map<String, String> sourceType2String;
    static {
        sourceType2String = new HashMap<String, String>();
        sourceType2String.put("a", "b");
        sourceType2String.put("c", "d");
    }

    // For the recording buffer
    private int minBufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, inputChannelConfig, audioFormat);
    private int recordingBufferSize = minBufferSize * RECORDING_BUFFER_FACTOR;

    // Used for reading from the AudioRecord buffer
    private int readBufferSize = minBufferSize;

    private AudioRecord recorder;
    private AudioTrack audioTrack;
    private Handler handler;
    private Message message;
    private Bundle messageBundle = new Bundle();

    public AudioInputReceiver() {
        recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, sampleRateInHz, inputChannelConfig, audioFormat,
                minBufferSize * RECORDING_BUFFER_FACTOR);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRateInHz, outputChannelConfig, audioFormat,
                recordingBufferSize, AudioTrack.MODE_STREAM);
    }

    public AudioInputReceiver(int sampleRate, int bufferSizeInBytes, int channels, String format, int audioSource,
            boolean monitoring, int monitorSampleRate) {
        this.monitoring = monitoring;
        this.monitorSampleRate = monitorSampleRate;
        Log.e(LOG_TAG, "monitorSampleRate: " + monitorSampleRate);
        sampleRateInHz = sampleRate;

        switch (channels) {
        case 2:
            inputChannelConfig = AudioFormat.CHANNEL_IN_STEREO;
            break;
        case 1:
        default:
            inputChannelConfig = AudioFormat.CHANNEL_IN_MONO;
            break;
        }
        if (format == "PCM_8BIT") {
            audioFormat = AudioFormat.ENCODING_PCM_8BIT;
        } else {
            audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        }

        readBufferSize = bufferSizeInBytes;

        // Get the minimum recording buffer size for the specified configuration
        minBufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, inputChannelConfig, audioFormat);

        // We use a recording buffer size larger than the one used for reading to avoid
        // buffer underrun.
        recordingBufferSize = readBufferSize * RECORDING_BUFFER_FACTOR;

        // Ensure that the given recordingBufferSize isn't lower than the minimum buffer
        // size allowed for the current configuration
        //
        if (recordingBufferSize < minBufferSize) {
            recordingBufferSize = minBufferSize;
        }
        Log.e(LOG_TAG, "minBufferSize: " + minBufferSize + " - readBufferSize: " + readBufferSize
                + " - recordingBufferSize: " + recordingBufferSize);

        recorder = new AudioRecord(audioSource, sampleRateInHz, inputChannelConfig, audioFormat, recordingBufferSize);
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRateInHz, outputChannelConfig, audioFormat,
                recordingBufferSize, AudioTrack.MODE_STREAM);
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public void setMonitoring(boolean monitoring) {
        this.monitoring = monitoring;
    }

    public void startRecording(String folderPath, String fileName, Context context) {
        this.folderPath = folderPath;
        try {
            File dir = new File(this.folderPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }
        } catch (Exception e) {
            Log.w("creating file error", e.toString());
        }
        // this.folderPath = context.getExternalStorageDirectory();
        this.fileName = fileName;
        this.startRecording = true;
    }

    public void finishRecording() {
        this.finishRecording = true;
    }

    public boolean isInitialised() {
        return recorder.getState() == AudioRecord.STATE_INITIALIZED;
    }

    public JSONArray getSourcesList(Context context) {
        JSONArray results = new JSONArray();

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                AudioDeviceInfo[] adi = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
                for (int i = 0; i < adi.length; i++) {
                    // make sure it's a input not an output (should be the case because we asked for
                    // them...) and add the device info to the results
                    if (adi[i].isSource()) {
                        JSONObject curDevice = new JSONObject();
                        curDevice.put("id", adi[i].getId());
                        curDevice.put("type", adi[i].getType());
                        results.put(curDevice);
                    }
                }
            } catch (Exception e) {
                // some exception handler code.
            }
        }
        return results;
    }

    @Override
    public void run() {
        File outputFile = null;
        FileOutputStream wavOut = null;

        int numReadBytes = 0;
        long total = 0;
        byte audioBuffer[] = new byte[readBufferSize];

        synchronized (this) {
            try {
                recorder.startRecording();
                audioTrack.play();

                while (!isInterrupted()) {
                    if (this.startRecording) {
                        this.startRecording = false;
                        this.recording = true;
                        outputFile = new File(folderPath, fileName);
                        if (!outputFile.exists()) {
                            try {
                                outputFile.createNewFile();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        try {
                            wavOut = new FileOutputStream(outputFile);
                            writeWavHeader(wavOut, inputChannelConfig, sampleRateInHz, audioFormat);
                        } catch (IOException e) {
                            Log.e(LOG_TAG, e.getMessage(), e);
                            this.recording = false;
                        }
                    }

                    numReadBytes = recorder.read(audioBuffer, 0, readBufferSize);

                    if (this.monitoring)
                        audioTrack.write(audioBuffer, 0, audioBuffer.length);

                    if (this.finishRecording) {
                        this.recording = false;
                        this.finishRecording = false;
                        total = 0;
                        if (wavOut != null) {
                            try {
                                wavOut.close();
                            } catch (IOException e) {
                                Log.e(LOG_TAG, e.getMessage(), e);
                            }
                        }

                        try {
                            updateWavHeader(outputFile);
                        } catch (IOException e) {
                            Log.e(LOG_TAG, e.getMessage(), e);
                        }
                    }

                    if (this.recording) {
                        try {
                            // WAVs cannot be > 4 GB due to the use of 32 bit unsigned integers.
                            if (total + numReadBytes > 4294967295L) {
                                // Write as many bytes as we can before hitting the max size
                                for (int i = 0; i < numReadBytes && total <= 4294967295L; i++, total++) {
                                    wavOut.write(audioBuffer[i]);
                                }
                                this.finishRecording = true;
                                this.recording = false;
                            } else {
                                // Write out the entire read buffer
                                wavOut.write(audioBuffer, 0, numReadBytes);
                                total += numReadBytes;
                            }
                        } catch (IOException e) {
                            Log.e(LOG_TAG, e.getMessage(), e);
                            this.recording = false;
                        }
                    }
                    if (numReadBytes > 0) {
                        try {

                            byte monitorBuffer[] = new byte[(int) (audioBuffer.length / monitorSampleRate)];
                            for (int i = 0; i < monitorBuffer.length; i++) {
                                if (audioBuffer.length > i * monitorSampleRate)
                                    monitorBuffer[i] = audioBuffer[i * monitorSampleRate];
                                else
                                    monitorBuffer[i] = 0;
                            }
                            String decoded = Arrays.toString(monitorBuffer);

                            message = handler.obtainMessage();
                            messageBundle.putString("data", decoded);
                            message.setData(messageBundle);
                            handler.sendMessage(message);
                        } catch (Exception ex) {
                            message = handler.obtainMessage();
                            messageBundle.putString("error", ex.toString());
                            message.setData(messageBundle);
                            handler.sendMessage(message);
                        }
                    }
                }
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.stop();
                audioTrack.release();
                recorder.stop();
                recorder.release();
                recorder = null;
            } catch (Exception e) {
                Log.e(LOG_TAG, e.getMessage(), e);
            }
        }
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream Two size
     * fields are left empty/null since we do not yet know the final stream size
     *
     * @param out
     *            The stream to write the header to
     * @param channelMask
     *            An AudioFormat.CHANNEL_* mask
     * @param sampleRate
     *            The sample rate in hertz
     * @param encoding
     *            An AudioFormat.ENCODING_PCM_* value
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, int channelMask, int sampleRate, int encoding)
            throws IOException {
        short channels;
        switch (channelMask) {
        case AudioFormat.CHANNEL_IN_MONO:
            channels = 1;
            break;
        case AudioFormat.CHANNEL_IN_STEREO:
            channels = 2;
            break;
        default:
            throw new IllegalArgumentException("Unacceptable channel mask");
        }

        short bitDepth;
        switch (encoding) {
        case AudioFormat.ENCODING_PCM_8BIT:
            bitDepth = 8;
            break;
        case AudioFormat.ENCODING_PCM_16BIT:
            bitDepth = 16;
            break;
        case AudioFormat.ENCODING_PCM_FLOAT:
            bitDepth = 32;
            break;
        default:
            throw new IllegalArgumentException("Unacceptable encoding");
        }

        writeWavHeader(out, channels, sampleRate, bitDepth);
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream Two size
     * fields are left empty/null since we do not yet know the final stream size
     *
     * @param out
     *            The stream to write the header to
     * @param channels
     *            The number of channels
     * @param sampleRate
     *            The sample rate in hertz
     * @param bitDepth
     *            The bit depth
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, short channels, int sampleRate, short bitDepth)
            throws IOException {
        // Convert the multi-byte integers to raw bytes in little endian format as
        // required by the spec
        byte[] littleBytes = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putShort(channels)
                .putInt(sampleRate).putInt(sampleRate * channels * (bitDepth / 8))
                .putShort((short) (channels * (bitDepth / 8))).putShort(bitDepth).array();

        // Not necessarily the best, but it's very easy to visualize this way
        out.write(new byte[] {
                // RIFF header
                'R', 'I', 'F', 'F', // ChunkID
                0, 0, 0, 0, // ChunkSize (must be updated later)
                'W', 'A', 'V', 'E', // Format
                // fmt subchunk
                'f', 'm', 't', ' ', // Subchunk1ID
                16, 0, 0, 0, // Subchunk1Size
                1, 0, // AudioFormat
                littleBytes[0], littleBytes[1], // NumChannels
                littleBytes[2], littleBytes[3], littleBytes[4], littleBytes[5], // SampleRate
                littleBytes[6], littleBytes[7], littleBytes[8], littleBytes[9], // ByteRate
                littleBytes[10], littleBytes[11], // BlockAlign
                littleBytes[12], littleBytes[13], // BitsPerSample
                // data subchunk
                'd', 'a', 't', 'a', // Subchunk2ID
                0, 0, 0, 0, // Subchunk2Size (must be updated later)
        });
    }

    /**
     * Updates the given wav file's header to include the final chunk sizes
     *
     * @param wav
     *            The wav file to update
     * @throws IOException
     */
    private static void updateWavHeader(File wav) throws IOException {
        byte[] sizes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                // There are probably a bunch of different/better ways to calculate
                // these two given your circumstances. Cast should be safe since if the WAV is
                // > 4 GB we've already made a terrible mistake.
                .putInt((int) (wav.length() - 8)) // ChunkSize
                .putInt((int) (wav.length() - 44)) // Subchunk2Size
                .array();

        RandomAccessFile accessWave = null;
        // noinspection CaughtExceptionImmediatelyRethrown
        try {
            accessWave = new RandomAccessFile(wav, "rw");
            // ChunkSize
            accessWave.seek(4);
            accessWave.write(sizes, 0, 4);

            // Subchunk2Size
            accessWave.seek(40);
            accessWave.write(sizes, 4, 4);
        } catch (IOException ex) {
            // Rethrow but we still close accessWave in our finally
            throw ex;
        } finally {
            if (accessWave != null) {
                try {
                    accessWave.close();
                } catch (IOException ex) {
                    //
                }
            }
        }
    }
}