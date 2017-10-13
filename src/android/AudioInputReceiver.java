package com.exelerus.cordova.audioinputcapture;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.content.Context;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import android.util.Base64;

public class AudioInputReceiver extends Thread {

	private final int RECORDING_BUFFER_FACTOR = 5;
	private int inputChannelConfig = AudioFormat.CHANNEL_IN_MONO;
	private int outputChannelConfig = AudioFormat.CHANNEL_OUT_DEFAULT;
	private int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
	private int sampleRateInHz = 44100;
	private int audioSource = 0;

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

	public AudioInputReceiver(int sampleRate, int bufferSizeInBytes, int channels, String format, int audioSource) {
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

		recorder = new AudioRecord(audioSource, sampleRateInHz, inputChannelConfig, audioFormat, recordingBufferSize);
		audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRateInHz, outputChannelConfig, audioFormat,
				recordingBufferSize, AudioTrack.MODE_STREAM);
	}

	public void setHandler(Handler handler) {
		this.handler = handler;
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
		int numReadBytes = 0;
		short audioBuffer[] = new short[readBufferSize];

		synchronized (this) {
			recorder.startRecording();
			audioTrack.play();

			while (!isInterrupted()) {
				numReadBytes = recorder.read(audioBuffer, 0, readBufferSize);
				audioTrack.write(audioBuffer, 0, audioBuffer.length);

				if (numReadBytes > 0) {
					try {
						String decoded = Arrays.toString(audioBuffer);

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

			if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
				recorder.stop();
			}

			recorder.release();
			recorder = null;
		}
	}
}