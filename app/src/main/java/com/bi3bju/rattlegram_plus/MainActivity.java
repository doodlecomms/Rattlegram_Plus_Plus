/*
Rattlegram Plus

Copyright 2026 BI3BJU <guerilla1949@gmail.com>
*/

package com.bi3bju.rattlegram_plus;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.util.LruCache;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.NumberPicker;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import android.widget.CheckBox;

import com.bi3bju.rattlegram_plus.databinding.ActivityMainBinding;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.drawable.DrawableCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    // Used to load the 'rattlegram' library on application startup.
    static {
        System.loadLibrary("rattlegram");
    }

    private static class Message {
        public long time;
        public byte[] call;
        public byte[] data;

        public Message(byte[] call, byte[] data) {
            this.time = SystemClock.elapsedRealtime() / 1000;
            this.call = Arrays.copyOf(call, 10);
            this.data = Arrays.copyOf(data, 170);
        }
    }
    private ArrayList<Message> repeatedMessages;

    private final int permissionID = 1;
    private final int locationPermissionID = 100;
    private final int bluetoothPermissionID = 200;
    // Bluetooth radio (BTech UV-Pro / Vero VR-N76) audio link
    private RadioLink radioLink;
    // Optional Hamlib rig control (CAT frequency and PTT). Off by default: nothing Hamlib is loaded or opened
    // until the user turns it on under Rig Control (Hamlib)
    private RigSettings rigSettings = new RigSettings();
    private RigController rig;
    private List<HamlibRig.Model> rigModels;
    private Runnable rigDialogRefresh, tuneDialogRefresh;
    /** The transmission playing now was keyed through Hamlib and must be unkeyed when it ends. */
    private boolean catPttTransmission;
    /** Audio frames written to the AudioTrack for the transmission playing now. */
    private long framesWritten;
    /** Hamlib's Yaesu FT-857 model, for its setup reminder in the rig dialog. */
    private static final int RIG_MODEL_FT857 = 1022;
    /** How long the radio stays keyed after the last audio has played. */
    private static final long RIG_PTT_TAIL_MS = 100;
    private boolean activityResumed;
    // Encoder output at the radio's 32 kHz: symbol 5120 + guard 640 samples
    private static final int RADIO_EXTENDED_LENGTH = (1280 * RadioLink.SAMPLE_RATE / 8000) * 9 / 8;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final int sampleSize = 2;
    private final int spectrumWidth = 360, spectrumHeight = 128;
    private final int spectrogramWidth = 360, spectrogramHeight = 128;
    private Bitmap spectrumBitmap, spectrogramBitmap;
    private int[] spectrumPixels, spectrogramPixels;
    private ImageView spectrumView, spectrogramView;
    private TextView status;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private boolean fancyHeader;
    private boolean repeaterMode;
    private boolean encryptMessage;
    private boolean deleteMessagesOnClose;
    /** Covers the screen until the phone's own lock (fingerprint, face or PIN) confirms the user. */
    private View lockCover;
    private static final int LOCK_AUTHENTICATORS =
            BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
    private boolean hideCallSign;
    /** Set while wiping all data, so nothing gets saved again on the way out. */
    private boolean wiping;
    // Automatic acknowledgments: ACK_OFF, ACK_ADDRESSED (messages starting with @MYCALL) or ACK_ALL
    private int ackMode;
    private static final int ACK_OFF = 0, ACK_ADDRESSED = 1, ACK_ALL = 2;
    // ID -> list entry of recently sent messages, so an incoming ACK can mark the right bubble
    private final java.util.LinkedHashMap<String, String> sentMessagesById = new java.util.LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
            return size() > 50;
        }
    };
    // ---- Pictures
    private static final String PICTURE_TOKEN = "📷[pic:";
    /** Rough on-air time per picture part, including the pause before the next one. */
    private static final float SECONDS_PER_PICTURE_PART = 3.5f;
    /**
     * Pause between picture parts. A radio needs time to drop out of transmit and the receiving radio's
     * squelch time to close before the next key-up, or the start of the next part (its sync) gets clipped.
     */
    private static final long PICTURE_PART_GAP_RADIO_MS = 1500, PICTURE_PART_GAP_SPEAKER_MS = 500;
    private long pictureLastBusy;
    private boolean pictureEncrypt;
    private List<String> pictureQueue;
    private int pictureQueuePos;
    private String pictureSendingEntry, pictureSendingName;
    private final LruCache<String, Bitmap> pictureThumbs = new LruCache<>(40);

    /** A picture being received from one sender. */
    private static final class IncomingPicture {
        PictureCodec.Assembly assembly;
        String entry;
        boolean done, locked, wantAck;
        int lastIndex = -1;
        Runnable ackTask;
    }

    private final java.util.LinkedHashMap<String, IncomingPicture> incomingPictures = new java.util.LinkedHashMap<String, IncomingPicture>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, IncomingPicture> eldest) {
            return size() > 8;
        }
    };
    private static final java.util.regex.Pattern ACK_PATTERN = java.util.regex.Pattern.compile("^ACK ([0-9a-f]{4})$");
    private boolean showSpectrum;
    private boolean ultrasonicEnabled;
    private int spectrumTint;
    private int noiseSymbols;
    private int repeaterDelay;
    private int repeaterDebounce;
    private int recordRate;
    private int outputRate;
    private int recordChannel;
    private int outputChannel;
    private int audioSource;
    private int carrierFrequency;
    private int recordCount;
    private short[] recordBuffer;
    private short[] outputBuffer;
    private Menu menu;
    private Handler handler;
    private Runnable statusTimer;
    private String prevStatus;
    private byte[] payload;
    private ArrayAdapter<String> messages;
    private float[] stagedCFO;
    private int[] stagedMode;
    private byte[] stagedCall;
    private String callSign;
    private String draftText;
    private ListView messageListView;

    // ---------- Beacon-related variables ----------
    private Handler beaconHandler;
    private Runnable beaconRunnable;
    private boolean beaconRunning = false;
    private static final long BEACON_INTERVAL = 60 * 1000; // 1 minute

    // Call sign menu item reference
    private MenuItem callSignMenuItem;
    // Ping menu item reference (for tinting)
    private MenuItem pingMenuItem;

    // Bottom buttons
    private TextView locationButton;   // Location button (📍)
    private ImageView composeButton;

    // ========== Password in-memory variable ==========
    private String password = "";

    // ========== Location-related ==========
    private LocationManager locationManager;
    // Location message format: 📍[geo:latitude,longitude]
    private static final String LOCATION_PATTERN = "📍\\[geo:([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)\\]";
    private static final String LOCATION_FORMAT = "📍[geo:%.6f,%.6f]";
    // Maximum acceptable location age (5 minutes)
    private static final long MAX_LOCATION_AGE_MS = 5 * 60 * 1000;
    // SALUTE report form: remembered between uses
    private int saluteLocationFormat = GeoFormat.LAT_LONG;
    private boolean saluteTimeUtc = true;
    // Timeout for actively requesting a location (15 seconds)
    private static final long LOCATION_REQUEST_TIMEOUT_MS = 15 * 1000;

    // Location callback interface
    private interface LocationCallback {
        void onLocation(Location location);
    }

    // ========== Contact-related ==========
    private static final int MAX_CONTACTS = 100;
    private static final String CONTACTS_KEY = "contacts";
    private static final int COLOR_MENTION_SELF = 0xFF0000FF;  // blue

    private static class Contact {
        public String callSign;
        public String note;

        public Contact(String callSign, String note) {
            this.callSign = callSign;
            this.note = note;
        }
    }

    private ArrayList<Contact> contacts = new ArrayList<>();
    // Default bubble text color (lazily initialized)
    private int bubbleDefaultTextColor = -1;

    private native boolean createEncoder(int sampleRate);

    private native void configureEncoder(byte[] payload, byte[] callSign, int carrierFrequency, int noiseSymbols, boolean fancyHeader);

    private native boolean produceEncoder(short[] audioBuffer, int channelSelect);

    private native void destroyEncoder();

    private final AudioTrack.OnPlaybackPositionUpdateListener outputListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
        @Override
        public void onMarkerReached(AudioTrack ignore) {

        }

        @Override
        public void onPeriodicNotification(AudioTrack audioTrack) {
            if (produceEncoder(outputBuffer, outputChannel)) {
                writeOutput();
            } else {
                // Audio still in the output buffer, which stop() lets play out
                long remaining = framesWritten - (audioTrack.getPlaybackHeadPosition() & 0xffffffffL);
                audioTrack.stop();
                if (catPttTransmission) {
                    catPttTransmission = false;
                    rig.ptt().release(Math.max(0, remaining) * 1000 / audioTrack.getSampleRate() + RIG_PTT_TAIL_MS);
                }
                handler.postDelayed(() -> startListening(), 1000);
            }
        }
    };

    private void initAudioTrack() {
        if (audioTrack != null) {
            boolean rateChanged = audioTrack.getSampleRate() != outputRate;
            boolean channelChanged = audioTrack.getChannelCount() != (outputChannel == 0 ? 1 : 2);
            if (!rateChanged && !channelChanged)
                return;
            audioTrack.stop();
            audioTrack.release();
        }
        int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
        int channelCount = 1;
        if (outputChannel != 0) {
            channelCount = 2;
            channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
        }
        int symbolLength = (1280 * outputRate) / 8000;
        int guardLength = symbolLength / 8;
        int extendedLength = symbolLength + guardLength;
        int bufferSize = 5 * extendedLength * sampleSize * channelCount;
        audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, outputRate, channelConfig, audioFormat, bufferSize, AudioTrack.MODE_STREAM);
        outputBuffer = new short[extendedLength * channelCount];
        audioTrack.setPlaybackPositionUpdateListener(outputListener);
        audioTrack.setPositionNotificationPeriod(extendedLength);
        if (!createEncoder(outputRate))
            setStatus(getString(R.string.heap_error));
    }

    private native boolean feedDecoder(short[] audioBuffer, int sampleCount, int channelSelect);

    private native int processDecoder();

    private native void spectrumDecoder(int[] spectrumPixels, int[] spectrogramPixels, int spectrumTint);

    private native void stagedDecoder(float[] carrierFrequencyOffset, int[] operationMode, byte[] callSign);

    private native int fetchDecoder(byte[] payload);

    private native boolean createDecoder(int sampleRate);

    private native void destroyDecoder();

    private final AudioRecord.OnRecordPositionUpdateListener recordListener = new AudioRecord.OnRecordPositionUpdateListener() {
        @Override
        public void onMarkerReached(AudioRecord ignore) {

        }

        @Override
        public void onPeriodicNotification(AudioRecord audioRecord) {
            audioRecord.read(recordBuffer, 0, recordBuffer.length);
            decodeAudio(recordBuffer, recordCount, recordChannel);
        }
    };

    /** Feeds one chunk of received audio (microphone or Bluetooth radio) to the decoder and handles the result. */
    private void decodeAudio(short[] buffer, int count, int channel) {
        if (!feedDecoder(buffer, count, channel))
            return;
        int status = processDecoder();
        if (showSpectrum) {
            spectrumDecoder(spectrumPixels, spectrogramPixels, spectrumTint);
            spectrumBitmap.setPixels(spectrumPixels, 0, spectrumWidth, 0, 0, spectrumWidth, spectrumHeight);
            spectrogramBitmap.setPixels(spectrogramPixels, 0, spectrogramWidth, 0, 0, spectrogramWidth, spectrogramHeight);
            spectrumView.invalidate();
            spectrogramView.invalidate();
        }
        final int STATUS_OKAY = 0;
        final int STATUS_FAIL = 1;
        final int STATUS_SYNC = 2;
        final int STATUS_DONE = 3;
        final int STATUS_HEAP = 4;
        final int STATUS_NOPE = 5;
        final int STATUS_PING = 6;
        switch (status) {
            case STATUS_OKAY:
                break;
            case STATUS_FAIL:
                setStatus(getString(R.string.preamble_fail), true);
                break;
            case STATUS_NOPE:
                stagedDecoder(stagedCFO, stagedMode, stagedCall);
                fromStatus();
                addLine(parseCallSign(stagedCall), getString(R.string.preamble_nope, stagedMode[0]));
                break;
            case STATUS_PING:
                stagedDecoder(stagedCFO, stagedMode, stagedCall);
                fromStatus();
                // Ping also auto-saves the sender's call sign
                String pingSender = parseCallSign(stagedCall);
                autoSaveContact(pingSender);
                addLine(pingSender, getString(R.string.preamble_ping));
                break;
            case STATUS_HEAP:
                setStatus(getString(R.string.heap_error));
                stopListening();
                break;
            case STATUS_SYNC:
                stagedDecoder(stagedCFO, stagedMode, stagedCall);
                fromStatus();
                break;
            case STATUS_DONE:
                int result = fetchDecoder(payload);
                if (result < 0) {
                    addLine(parseCallSign(stagedCall), getString(R.string.decoding_failed));
                } else {
                    setStatus(getResources().getQuantityString(R.plurals.bits_flipped, result, result), true);
                    String sender = parseCallSign(stagedCall);
                    if (repeaterMode) {
                        repeatMessage();
                    } else {
                        String received = new String(payload).trim();
                        PictureCodec.Packet picturePart = PictureCodec.parse(received);
                        if (picturePart != null) {
                            handlePicturePart(sender, picturePart, received);
                        } else if (!handleAcknowledgment(sender, received)) {
                            String[] hidden = HiddenCall.unwrap(CryptoUtils.decrypt(received, password));
                            if (hidden != null) {
                                autoSaveContact(hidden[0]);
                                addMessage(hidden[0], getString(R.string.received_hidden), hidden[1]);
                            } else {
                                autoSaveContact(sender);
                                addMessage(sender, getString(R.string.received), received);
                            }
                            maybeAcknowledge(received);
                        }
                    }
                }
                break;
        }
    }

    private void setStatus(String str, boolean tmp) {
        if (statusTimer != null)
            handler.removeCallbacks(statusTimer);
        statusTimer = null;
        if (tmp) {
            statusTimer = () -> {
                statusTimer = null;
                status.setText(withRigFrequency(prevStatus));
            };
            handler.postDelayed(statusTimer, 10000);
        } else {
            prevStatus = str;
        }
        status.setText(withRigFrequency(str));
    }

    /** "Listening · 146.550 MHz" while a Hamlib radio is connected; the status unchanged otherwise. */
    private String withRigFrequency(String text) {
        if (rig == null || !rig.isConnected() || rig.lastFrequency() <= 0 || text == null)
            return text;
        return text + " · " + RigFrequencies.format(rig.lastFrequency());
    }

    /** Shows a new frequency on the status line, keeping whatever it says. */
    private void refreshStatusFrequency() {
        if (prevStatus != null && statusTimer == null)
            status.setText(withRigFrequency(prevStatus));
    }

    private void setStatus(String str) {
        setStatus(str, false);
    }

    private void fromStatus() {
        setStatus(getString(R.string.from_status, parseCallSign(stagedCall), stagedMode[0], stagedCFO[0]), true);
    }

    /**
     * Safely parse a call sign from the native-filled byte[10]:
     * - strip \0 padding
     * - strip leading/trailing whitespace
     * - strip full-width spaces, zero-width characters, etc.
     */
    private String parseCallSign(byte[] raw) {
        if (raw == null) return "";
        String s = new String(raw, StandardCharsets.US_ASCII);
        s = s.replace("\0", "");
        s = s.replace("\u3000", "");
        s = s.replace("\u200B", "");
        s = s.trim();
        return s;
    }

    /**
     * Check whether a call sign is blank (null, empty, all whitespace, full-width spaces, zero-width chars, etc.)
     */
    private boolean isBlankCallSign(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r'
                    && ch != '\0' && ch != '\u3000' && ch != '\u200B') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the string is entirely whitespace (null, empty, spaces, full-width spaces, tabs, newlines).
     * Used to reject "all whitespace" content in the compose dialog.
     */
    private static boolean isAllWhitespace(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * If the call sign exists in the contact list, return "callSign[note]"; otherwise return the original call sign.
     * If the note is empty, no square brackets are added.
     */
    private String appendContactNote(String call) {
        if (call == null) return null;
        for (Contact c : contacts) {
            if (c.callSign.equals(call)) {
                if (c.note != null && !c.note.isEmpty()) {
                    return call + "[" + c.note + "]";
                }
                break;
            }
        }
        return call;
    }

    private byte[] callTerm() {
        return Arrays.copyOf(callSign.getBytes(StandardCharsets.US_ASCII), callSign.length() + 1);
    }

    private String currentTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private void addLine(String call, String info) {
        addString(getString(R.string.title_line, currentTime(), appendContactNote(call), info));
    }

    // Decrypt when receiving a message. Only a message that passed decryption (so came from a station
    // with the password) is marked with the lock; plain "Received" means it was sent in the clear.
    private String addMessage(String call, String info, String mesg) {
        if (info.equals(getString(R.string.received))) {
            String decrypted = CryptoUtils.decrypt(mesg, password);
            if (decrypted != null) {
                mesg = decrypted;
                info = getString(R.string.received_decrypted);
            }
        }
        String entry = getString(R.string.title_message, currentTime(), appendContactNote(call), info, mesg);
        addString(entry);
        return entry;
    }

    private void addString(String str) {
        runOnUiThread(() -> {
            int count = 100;
            if (messages.getCount() >= count) {
                messages.remove(messages.getItem(0));
            }
            messages.add(str);
            messageListView.setSelection(messages.getCount() - 1);
            storeSettings();
        });
    }

    /**
     * Check whether the received message body starts with "@selfCallSign " (a trailing space is required).
     * - Case-sensitive match
     * - Only the very beginning of the message body is checked
     * - The message body is on the second line of title_message.
     * Caller (MessageAdapter) has already excluded sent messages.
     */
    private boolean isTargetingSelf(String item) {
        if (item == null || callSign == null || callSign.isEmpty()) return false;
        int nl = item.indexOf('\n');
        if (nl < 0 || nl + 1 >= item.length()) return false;
        String content = item.substring(nl + 1);
        return content.startsWith("@" + callSign + " ");
    }

    // -------- Custom adapter: bubble-style display --------
    private class MessageAdapter extends ArrayAdapter<String> {
        public MessageAdapter(Context context, int resource) {
            super(context, resource);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            FrameLayout layout;
            TextView textView;
            if (convertView == null) {
                layout = (FrameLayout) getLayoutInflater().inflate(R.layout.message_list_item, parent, false);
                textView = layout.findViewById(R.id.message_text);
                layout.setTag(textView);
            } else {
                layout = (FrameLayout) convertView;
                textView = (TextView) layout.getTag();
            }

            String item = getItem(position);
            String pictureName = pictureNameIn(item);
            if (pictureName != null) {
                textView.setText(item.replace(PICTURE_TOKEN + pictureName + "]", getString(R.string.picture_label)));
                Bitmap thumb = pictureThumbnail(pictureName);
                if (thumb != null) {
                    BitmapDrawable d = new BitmapDrawable(getResources(), thumb);
                    d.setFilterBitmap(false);
                    textView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, d);
                } else {
                    textView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                }
            } else {
                textView.setText(item);
                textView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            }

            // Lazily record the default text color
            if (bubbleDefaultTextColor == -1) {
                bubbleDefaultTextColor = textView.getCurrentTextColor();
            }

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) textView.getLayoutParams();
            if (isSentItem(item)) {
                // Sent by me: right-aligned bubble, always default text color
                textView.setBackgroundResource(R.drawable.bubble_sent);
                params.gravity = Gravity.END;
                textView.setTextColor(bubbleDefaultTextColor);
            } else {
                // Received: left-aligned bubble; blue only if body starts with "@selfCallSign "
                textView.setBackgroundResource(R.drawable.bubble_received);
                params.gravity = Gravity.START;
                if (isTargetingSelf(item)) {
                    textView.setTextColor(COLOR_MENTION_SELF);
                } else {
                    textView.setTextColor(bubbleDefaultTextColor);
                }
            }
            textView.setLayoutParams(params);

            return layout;
        }

        private boolean isSentItem(String item) {
            if (item == null) return false;
            return item.contains(getString(R.string.transmitted)) ||
                   item.contains(getString(R.string.repeated)) ||
                   item.contains(getString(R.string.sent_ping));
        }
    }

    // -------- Contact adapter: call sign + note --------
    private class ContactAdapter extends ArrayAdapter<Contact> {
        public ContactAdapter(Context context) {
            // Pass 0 and inflate ourselves to avoid super.getView casting to TextView
            super(context, 0);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            TextView text1 = v.findViewById(android.R.id.text1);
            TextView text2 = v.findViewById(android.R.id.text2);
            Contact c = getItem(position);
            if (c != null) {
                text1.setText(c.callSign);
                text2.setText(c.note == null || c.note.isEmpty() ? " " : c.note);
            }
            return v;
        }
    }

    private void startListening() {
        // Between the parts of a picture being sent the phone stays in transmit mode
        if (pictureQueue != null)
            return;
        if (isRadioConnected()) {
            setStatus(getString(R.string.radio_listening, radioLink.getDeviceName()));
            return;
        }
        if (audioRecord != null) {
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.read(recordBuffer, 0, recordBuffer.length);
                setStatus(getString(R.string.listening));
            } else {
                setStatus(getString(R.string.audio_recording_error));
            }
        }
    }

    private void stopListening() {
        if (audioRecord != null)
            audioRecord.stop();
    }

    private void initAudioRecord(boolean restart) {
        if (audioRecord != null) {
            boolean rateChanged = audioRecord.getSampleRate() != recordRate;
            boolean channelChanged = audioRecord.getChannelCount() != (recordChannel == 0 ? 1 : 2);
            boolean sourceChanged = audioRecord.getAudioSource() != audioSource;
            if (!rateChanged && !channelChanged && !sourceChanged)
                return;
            stopListening();
            audioRecord.release();
            audioRecord = null;
        }
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int channelCount = 1;
        if (recordChannel != 0) {
            channelCount = 2;
            channelConfig = AudioFormat.CHANNEL_IN_STEREO;
        }
        int frameSize = sampleSize * channelCount;
        int bufferSize = 2 * Integer.highestOneBit(3 * recordRate) * frameSize;
        try {
            AudioRecord testAudioRecord = new AudioRecord(audioSource, recordRate, channelConfig, audioFormat, bufferSize);
            if (testAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                if (createDecoder(recordRate)) {
                    audioRecord = testAudioRecord;
                    recordCount = recordRate / 50;
                    recordBuffer = new short[recordCount * channelCount];
                    audioRecord.setRecordPositionUpdateListener(recordListener);
                    audioRecord.setPositionNotificationPeriod(recordCount);
                    if (restart)
                        startListening();
                } else {
                    testAudioRecord.release();
                    setStatus(getString(R.string.heap_error));
                }
            } else {
                testAudioRecord.release();
                setStatus(getString(R.string.audio_init_failed));
            }
        } catch (IllegalArgumentException e) {
            setStatus(getString(R.string.audio_setup_failed));
        } catch (SecurityException e) {
            setStatus(getString(R.string.audio_permission_denied));
        }
    }

    private void setRecordRate(int newSampleRate) {
        if (radioBlocksAudioChange())
            return;
        if (recordRate == newSampleRate)
            return;
        recordRate = newSampleRate;
        updateRecordRateMenu();
        initAudioRecord(true);
    }

    private void setRecordChannel(int newChannelSelect) {
        if (radioBlocksAudioChange())
            return;
        if (recordChannel == newChannelSelect)
            return;
        recordChannel = newChannelSelect;
        updateRecordChannelMenu();
        initAudioRecord(true);
    }

    private void setAudioSource(int newAudioSource) {
        if (radioBlocksAudioChange())
            return;
        if (audioSource == newAudioSource)
            return;
        audioSource = newAudioSource;
        updateAudioSourceMenu();
        initAudioRecord(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == permissionID) {
            for (int i = 0; i < permissions.length; ++i) {
                if (permissions[i].equals(Manifest.permission.RECORD_AUDIO)
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    initAudioRecord(false);
                }
            }
            return;
        }

        if (requestCode == bluetoothPermissionID) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                showRadioDialog();
            else
                Toast.makeText(this, R.string.radio_permission_denied, Toast.LENGTH_SHORT).show();
            return;
        }

        if (requestCode == locationPermissionID) {
            boolean granted = false;
            for (int i = 0; i < permissions.length; i++) {
                if ((permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)
                        || permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION))
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                }
            }
            if (!granted) {
                Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
        state.putInt("outputRate", outputRate);
        state.putInt("outputChannel", outputChannel);
        state.putInt("recordRate", recordRate);
        state.putInt("recordChannel", recordChannel);
        state.putInt("audioSource", audioSource);
        state.putInt("carrierFrequency", carrierFrequency);
        state.putInt("noiseSymbols", noiseSymbols);
        state.putInt("repeaterDelay", repeaterDelay);
        state.putInt("repeaterDebounce", repeaterDebounce);
        state.putString("callSign", callSign);
        state.putString("draftText", draftText);
        state.putBoolean("fancyHeader", fancyHeader);
        state.putBoolean("repeaterMode", repeaterMode);
        state.putBoolean("encryptMessage", encryptMessage);
        state.putBoolean("deleteMessagesOnClose", deleteMessagesOnClose);
        state.putBoolean("hideCallSign", hideCallSign);
        state.putInt("ackMode", ackMode);
        for (int i = 0; i < messages.getCount(); ++i)
            state.putString("m" + i, messages.getItem(i));
        super.onSaveInstanceState(state);
    }

    private void storeSettings() {
        if (wiping)
            return;
        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        edit.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
        edit.putInt("outputRate", outputRate);
        edit.putInt("outputChannel", outputChannel);
        edit.putInt("recordRate", recordRate);
        edit.putInt("recordChannel", recordChannel);
        edit.putInt("audioSource", audioSource);
        edit.putInt("carrierFrequency", carrierFrequency);
        edit.putInt("noiseSymbols", noiseSymbols);
        edit.putInt("repeaterDelay", repeaterDelay);
        edit.putInt("repeaterDebounce", repeaterDebounce);
        edit.putString("callSign", callSign);
        edit.putString("draftText", draftText);
        edit.putBoolean("fancyHeader", fancyHeader);
        edit.putBoolean("repeaterMode", repeaterMode);
        edit.putBoolean("encryptMessage", encryptMessage);
        edit.putBoolean("deleteMessagesOnClose", deleteMessagesOnClose);
        edit.putBoolean("hideCallSign", hideCallSign);
        edit.putInt("ackMode", ackMode);
        for (int i = 0; i < messages.getCount(); ++i)
            edit.putString("m" + i, messages.getItem(i));
        edit.apply();
    }

    // ========== Contact persistence ==========

    private void loadContacts() {
        contacts.clear();
        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        String json = pref.getString(CONTACTS_KEY, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length() && i < MAX_CONTACTS; i++) {
                JSONObject obj = arr.getJSONObject(i);
                String cs = obj.optString("callSign", "");
                // Notes saved by earlier versions lost the space: "Auto-added at2026-10-19 ..."
                String note = obj.optString("note", "").replaceFirst("^(Auto-added at)(\\d)", "$1 $2");
                if (!isBlankCallSign(cs))
                    contacts.add(new Contact(cs, note));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void saveContacts() {
        try {
            JSONArray arr = new JSONArray();
            for (Contact c : contacts) {
                JSONObject obj = new JSONObject();
                obj.put("callSign", c.callSign);
                obj.put("note", c.note == null ? "" : c.note);
                arr.put(obj);
            }
            SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
            pref.edit().putString(CONTACTS_KEY, arr.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Automatically save the sender's call sign to contacts.
     * - Skip null, empty, whitespace-only call signs
     * - Skip ANONYMOUS
     * - Ignore if already present (exact match)
     * - Max 100 contacts
     * - Note is "Auto-added at " + current datetime yyyy-MM-dd HH:mm:ss
     */
    private void autoSaveContact(String senderCall) {
        if (senderCall == null) return;

        // Strip \0 padding, full-width spaces, zero-width chars, then trim
        senderCall = senderCall.replace("\0", "")
                               .replace("\u3000", "")
                               .replace("\u200B", "")
                               .trim();

        // Skip blank, empty, ANONYMOUS
        if (isBlankCallSign(senderCall)) return;
        if ("ANONYMOUS".equals(senderCall)) return;

        // Ignore if already present
        for (Contact c : contacts) {
            if (c.callSign.equals(senderCall)) {
                return;
            }
        }

        // Limit
        if (contacts.size() >= MAX_CONTACTS) {
            return;
        }

        contacts.add(new Contact(senderCall,
                getString(R.string.contact_auto_added_at, currentTime())));
        saveContacts();
    }

    /**
     * Extract "call sign" and "note" from the first line of a message.
     * First line format: time - callSign[note] - status
     * Without note: time - callSign - status
     * Returns String[2]: {callSign, note}
     */
    private String[] parseCallAndNote(String firstLine) {
        String call = "";
        String note = "";
        if (firstLine == null) return new String[]{call, note};
        String[] parts = firstLine.split(" - ");
        if (parts.length >= 2) {
            String seg = parts[1].trim();
            int bracket = seg.indexOf('[');
            if (bracket > 0 && seg.endsWith("]")) {
                call = seg.substring(0, bracket).trim();
                note = seg.substring(bracket + 1, seg.length() - 1).trim();
            } else {
                call = seg;
            }
        }
        return new String[]{call, note};
    }

    @Override
    protected void onCreate(Bundle state) {
        messages = new MessageAdapter(this, R.layout.message_list_item);
        final int defaultSampleRate = 8000;
        final int defaultChannelSelect = 0;
        final int defaultAudioSource = MediaRecorder.AudioSource.DEFAULT;
        final int defaultCarrierFrequency = 1500;
        final int defaultNoiseSymbols = 6;
        final int defaultRepeaterDelay = 1;
        final int defaultRepeaterDebounce = 60;
        final String defaultCallSign = "ANONYMOUS";
        final String defaultDraftText = "";
        final boolean defaultFancyHeader = false;
        final boolean defaultRepeaterMode = false;
        final boolean defaultEncryptMessage = false;
        final boolean defaultDeleteMessagesOnClose = false;
        if (state == null) {
            SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
            AppCompatDelegate.setDefaultNightMode(pref.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
            outputRate = pref.getInt("outputRate", defaultSampleRate);
            outputChannel = pref.getInt("outputChannel", defaultChannelSelect);
            recordRate = pref.getInt("recordRate", defaultSampleRate);
            recordChannel = pref.getInt("recordChannel", defaultChannelSelect);
            audioSource = pref.getInt("audioSource", defaultAudioSource);
            carrierFrequency = pref.getInt("carrierFrequency", defaultCarrierFrequency);
            noiseSymbols = pref.getInt("noiseSymbols", defaultNoiseSymbols);
            repeaterDelay = pref.getInt("repeaterDelay", defaultRepeaterDelay);
            repeaterDebounce = pref.getInt("repeaterDebounce", defaultRepeaterDebounce);
            callSign = pref.getString("callSign", defaultCallSign);
            draftText = pref.getString("draftText", defaultDraftText);
            fancyHeader = pref.getBoolean("fancyHeader", defaultFancyHeader);
            repeaterMode = pref.getBoolean("repeaterMode", defaultRepeaterMode);
            encryptMessage = pref.getBoolean("encryptMessage", defaultEncryptMessage);
            deleteMessagesOnClose = pref.getBoolean("deleteMessagesOnClose", defaultDeleteMessagesOnClose);
            hideCallSign = pref.getBoolean("hideCallSign", false);
            ackMode = pref.getInt("ackMode", ACK_OFF);
            // A fresh start means the app was closed since it was last used. Closing by swiping the
            // app away gives no reliable chance to clean up, so finish the job here.
            if (deleteMessagesOnClose)
                clearStoredMessages();
            for (int i = 0; i < 100; ++i) {
                String mesg = pref.getString("m" + i, null);
                if (mesg != null)
                    messages.add(mesg);
            }
        } else {
            AppCompatDelegate.setDefaultNightMode(state.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
            outputRate = state.getInt("outputRate", defaultSampleRate);
            outputChannel = state.getInt("outputChannel", defaultChannelSelect);
            recordRate = state.getInt("recordRate", defaultSampleRate);
            recordChannel = state.getInt("recordChannel", defaultChannelSelect);
            audioSource = state.getInt("audioSource", defaultAudioSource);
            carrierFrequency = state.getInt("carrierFrequency", defaultCarrierFrequency);
            noiseSymbols = state.getInt("noiseSymbols", defaultNoiseSymbols);
            repeaterDelay = state.getInt("repeaterDelay", defaultRepeaterDelay);
            repeaterDebounce = state.getInt("repeaterDebounce", defaultRepeaterDebounce);
            callSign = state.getString("callSign", defaultCallSign);
            draftText = state.getString("draftText", defaultDraftText);
            fancyHeader = state.getBoolean("fancyHeader", defaultFancyHeader);
            repeaterMode = state.getBoolean("repeaterMode", defaultRepeaterMode);
            encryptMessage = state.getBoolean("encryptMessage", defaultEncryptMessage);
            deleteMessagesOnClose = state.getBoolean("deleteMessagesOnClose", defaultDeleteMessagesOnClose);
            hideCallSign = state.getBoolean("hideCallSign", false);
            ackMode = state.getInt("ackMode", ACK_OFF);
            for (int i = 0; i < 100; ++i) {
                String mesg = state.getString("m" + i, null);
                if (mesg != null)
                    messages.add(mesg);
            }
        }
        ultrasonicEnabled = Math.abs(carrierFrequency) > 3000;
        super.onCreate(state);
        EdgeToEdge.enable(this);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        status = binding.status;
        // Only does anything while a Hamlib radio is connected: tap the frequency to tune
        status.setOnClickListener(v -> {
            if (rig != null && rig.isConnected())
                showTuneDialog(null);
        });
        handler = new Handler(getMainLooper());
        radioLink = new RadioLink(radioListener);
        // Optional remembered password (encrypted with an Android Keystore key)
        String rememberedPassword = PasswordStore.load(this);
        if (rememberedPassword != null)
            password = rememberedPassword;
        setContentView(binding.getRoot());
        setTitle(R.string.app_title);
        // Only the saved configuration: a radio is never connected (or keyed) without the user asking
        rigSettings = RigSettings.from(getPreferences(Context.MODE_PRIVATE).getAll());
        // Optional app lock: only asks when the app is opened fresh, not after a rotation
        if (isAppLockEnabled()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (state == null)
                lockApp();
        }
        handleInsets();
        stagedCFO = new float[1];
        stagedMode = new int[1];
        stagedCall = new byte[10];
        payload = new byte[170];
        repeatedMessages = new ArrayList<>();
        loadContacts();
        prunePictures();
        messageListView = binding.messages;
        binding.messages.setAdapter(messages);

        // ========== Message click listener: support opening maps for location messages ==========
        binding.messages.setOnItemClickListener((adapterView, view, i, l) -> {
            String item = messages.getItem(i);
            if (item != null) {
                String[] mesg = item.split("\n", 2);
                if (mesg.length == 2) {
                    String content = mesg[1];
                    if (handlePictureTap(item, content))
                        return;
                    // Match 📍[geo:latitude,longitude]
                    Pattern pattern = Pattern.compile(LOCATION_PATTERN);
                    Matcher matcher = pattern.matcher(content);
                    if (matcher.find()) {
                        try {
                            double lat = Double.parseDouble(matcher.group(1));
                            double lng = Double.parseDouble(matcher.group(2));
                            // Use the sender's call sign from the first line as the pin title
                            String[] cn = parseCallAndNote(mesg[0]);
                            openMap(lat, lng, cn[0]);
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, R.string.location_parse_failed, Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        composeMessage(content);
                    }
                }
            }
        });

        binding.messages.setOnItemLongClickListener((adapterView, view, i, l) -> {
            String item = messages.getItem(i);
            if (item != null)
                confirmResend(item);
            return true;
        });
        initAudioTrack();

        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
            setStatus(getString(R.string.audio_permission_denied));
        } else {
            initAudioRecord(false);
        }
        if (!permissions.isEmpty())
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), permissionID);

        // ========== Initialize location manager ==========
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        // ========== Request location permissions (both fine and coarse) ==========
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    locationPermissionID);
        }

        String message = extractIntent(getIntent());
        if (message != null)
            composeMessage(message);

        if (messages.getCount() > 0) {
            messageListView.setSelection(messages.getCount() - 1);
        }

        // ---------- Initialize beacon handler ----------
        beaconHandler = new Handler();

        // ---------- Initialize bottom buttons ----------
        locationButton = findViewById(R.id.location_button);
        locationButton.setOnClickListener(v -> sendLocationMessage());

        composeButton = findViewById(R.id.compose_button);
        composeButton.setOnClickListener(v -> composeMessage(null));
    }

    private void handleInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String message = extractIntent(intent);
        if (message != null)
            composeMessage(message);
    }

    private String extractIntent(Intent intent) {
        String action = intent.getAction();
        if (action == null)
            return null;
        if (!action.equals(Intent.ACTION_SEND))
            return null;
        String type = intent.getType();
        if (type == null)
            return null;
        if (!type.equals("text/plain"))
            return null;
        return intent.getStringExtra(Intent.EXTRA_TEXT);
    }

    // ========== Location implementation ==========

    /**
     * Whether location permission has been granted (either fine or coarse is enough).
     */
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether location services are enabled.
     */
    private boolean isLocationServiceEnabled() {
        if (locationManager == null) return false;
        try {
            return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether the location is fresh enough.
     */
    private boolean isLocationFresh(Location loc) {
        return loc != null && (System.currentTimeMillis() - loc.getTime()) <= MAX_LOCATION_AGE_MS;
    }

    /**
     * Get the last known location (GMS-free, iterate all available providers).
     */
    private Location getLastKnownLocation() {
        if (!hasLocationPermission() || locationManager == null)
            return null;
        Location best = null;
        try {
            for (String provider : locationManager.getProviders(true)) {
                Location loc = locationManager.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime())
                    best = loc;
            }
        } catch (SecurityException e) {
            return null;
        }
        return best;
    }

    /**
     * Actively request a current location (with timeout).
     */
    private void requestCurrentLocation(final LocationCallback callback) {
        if (!hasLocationPermission() || locationManager == null) {
            callback.onLocation(null);
            return;
        }

        String provider;
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            provider = LocationManager.GPS_PROVIDER;
        } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            provider = LocationManager.NETWORK_PROVIDER;
        } else {
            callback.onLocation(null);
            return;
        }

        final boolean[] delivered = {false};
        final Runnable timeout = () -> {
            if (!delivered[0]) {
                delivered[0] = true;
                callback.onLocation(null);
            }
        };
        handler.postDelayed(timeout, LOCATION_REQUEST_TIMEOUT_MS);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.getCurrentLocation(provider, null, getMainExecutor(), location -> {
                    if (!delivered[0]) {
                        delivered[0] = true;
                        handler.removeCallbacks(timeout);
                        callback.onLocation(location);
                    }
                });
            } else {
                // For Android 7.0 - 10 (API 24 - 29), LocationListener requires all methods.
                // Since API 30, default implementations are provided, but we implement them
                // explicitly to maintain compatibility with older versions.
                LocationListener listener = new LocationListener() {
                    @Override
                    public void onLocationChanged(@NonNull Location location) {
                        if (!delivered[0]) {
                            delivered[0] = true;
                            handler.removeCallbacks(timeout);
                            locationManager.removeUpdates(this);
                            callback.onLocation(location);
                        }
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {
                        // Not used
                    }

                    @Override
                    public void onProviderEnabled(@NonNull String provider) {
                        // Not used
                    }

                    @Override
                    public void onProviderDisabled(@NonNull String provider) {
                        // Not used
                    }
                };
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            }
        } catch (SecurityException e) {
            if (!delivered[0]) {
                delivered[0] = true;
                handler.removeCallbacks(timeout);
                callback.onLocation(null);
            }
        }
    }

    /**
     * Send location message: prefer a fresh last known location, otherwise actively request one.
     */
    private void sendLocationMessage() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isLocationServiceEnabled()) {
            Toast.makeText(this, R.string.location_service_disabled, Toast.LENGTH_SHORT).show();
            return;
        }

        final Location last = getLastKnownLocation();
        if (isLocationFresh(last)) {
            sendLocation(last);
            return;
        }

        // Location is too old or null; actively request a current location
        setStatus(getString(R.string.location_fetching), true);
        requestCurrentLocation(loc -> {
            if (loc != null) {
                sendLocation(loc);
            } else if (last != null) {
                // Active request failed; fall back to the old location
                sendLocation(last);
            } else {
                Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Fill the compose window with the location.
     */
    private void sendLocation(Location loc) {
        String locationStr = String.format(Locale.US, LOCATION_FORMAT,
                loc.getLatitude(), loc.getLongitude());
        composeMessage(locationStr);
    }

    /**
     * Open map:
     * 1. First try the system default map app (geo: URI) with a pin title.
     * 2. If no map app is available, open OpenStreetMap in a browser.
     * Pin title is the sender's call sign (without any contact note);
     * if the call sign is empty it falls back to the default location label.
     */
    private void openMap(double lat, double lng, String pinCall) {
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            Toast.makeText(this, R.string.location_format_error, Toast.LENGTH_SHORT).show();
            return;
        }

        // Pin title: just the call sign
        String label = pinCall != null && !pinCall.isEmpty() ? pinCall : getString(R.string.location_default_label);
        String encodedLabel = Uri.encode(label);

        // 1. Prefer opening the system default map app via geo: URI
        Uri geoUri = Uri.parse(String.format(Locale.US,
                "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
                lat, lng, lat, lng, encodedLabel));
        Intent geoIntent = new Intent(Intent.ACTION_VIEW, geoUri);

        try {
            startActivity(geoIntent);
            return;
        } catch (ActivityNotFoundException e) {
            // No map app; fall through to browser
        }

        // 2. No map app; open OpenStreetMap in a browser with a marker at the location.
        String url = String.format(Locale.US,
                "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f",
                lat, lng, lat, lng);

        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));

        try {
            startActivity(browserIntent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_map_or_browser, Toast.LENGTH_SHORT).show();
        }
    }

    // ========== Existing features below ==========

    private void setNoiseSymbols(int newNoiseSymbols) {
        if (noiseSymbols == newNoiseSymbols)
            return;
        noiseSymbols = newNoiseSymbols;
        updateNoiseSymbolsMenu();
    }

    private void updateNoiseSymbolsMenu() {
        switch (noiseSymbols) {
            case 0:
                menu.findItem(R.id.action_disable_noise).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.action_set_noise_quarter_second).setChecked(true);
                break;
            case 3:
                menu.findItem(R.id.action_set_noise_half_second).setChecked(true);
                break;
            case 6:
                menu.findItem(R.id.action_set_noise_one_second).setChecked(true);
                break;
            case 11:
                menu.findItem(R.id.action_set_noise_two_seconds).setChecked(true);
                break;
            case 22:
                menu.findItem(R.id.action_set_noise_four_seconds).setChecked(true);
                break;
        }
    }

    private void setRepeaterDelay(int newRepeaterDelay) {
        if (repeaterDelay == newRepeaterDelay)
            return;
        repeaterDelay = newRepeaterDelay;
        updateRepeaterDelayMenu();
    }

    private void updateRepeaterDelayMenu() {
        switch (repeaterDelay) {
            case 0:
                menu.findItem(R.id.action_set_repeater_no_delay).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.action_set_repeater_delay_one_second).setChecked(true);
                break;
            case 2:
                menu.findItem(R.id.action_set_repeater_delay_two_seconds).setChecked(true);
                break;
            case 4:
                menu.findItem(R.id.action_set_repeater_delay_four_seconds).setChecked(true);
                break;
            case 8:
                menu.findItem(R.id.action_set_repeater_delay_eight_seconds).setChecked(true);
                break;
        }
    }


    private void setRepeaterDebounce(int newRepeaterDebounce) {
        if (repeaterDebounce == newRepeaterDebounce)
            return;
        repeaterDebounce = newRepeaterDebounce;
        updateRepeaterDebounceMenu();
    }

    private void updateRepeaterDebounceMenu() {
        switch (repeaterDebounce) {
            case 0:
                menu.findItem(R.id.action_set_repeater_allow_bouncing).setChecked(true);
                break;
            case 15:
                menu.findItem(R.id.action_set_repeater_debounce_quarter_minute).setChecked(true);
                break;
            case 30:
                menu.findItem(R.id.action_set_repeater_debounce_half_minute).setChecked(true);
                break;
            case 60:
                menu.findItem(R.id.action_set_repeater_debounce_one_minute).setChecked(true);
                break;
            case 120:
                menu.findItem(R.id.action_set_repeater_debounce_two_minutes).setChecked(true);
                break;
        }
    }

    private void setFancyHeader(boolean newFancyHeader) {
        if (fancyHeader == newFancyHeader)
            return;
        fancyHeader = newFancyHeader;
        updateFancyHeaderMenu();
    }

    private void updateFancyHeaderMenu() {
        if (fancyHeader)
            menu.findItem(R.id.action_enable_fancy_header).setChecked(true);
        else
            menu.findItem(R.id.action_disable_fancy_header).setChecked(true);
    }

    private void updateDeleteMessagesOnCloseMenu() {
        menu.findItem(R.id.action_delete_on_close).setChecked(deleteMessagesOnClose);
        menu.findItem(R.id.action_app_lock).setChecked(isAppLockEnabled());
    }

    private void setRepeaterMode(boolean newRepeaterMode) {
        if (repeaterMode == newRepeaterMode)
            return;
        repeaterMode = newRepeaterMode;
        updateRepeaterModeMenu();
    }

    private void updateRepeaterModeMenu() {
        if (repeaterMode)
            menu.findItem(R.id.action_enable_repeater_mode).setChecked(true);
        else
            menu.findItem(R.id.action_disable_repeater_mode).setChecked(true);
    }

    private void setOutputRate(int newSampleRate) {
        if (radioBlocksAudioChange())
            return;
        if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
            return;
        if (outputRate == newSampleRate)
            return;
        outputRate = newSampleRate;
        updateOutputRateMenu();
        initAudioTrack();
    }

    private void updateOutputRateMenu() {
        switch (outputRate) {
            case 8000:
                menu.findItem(R.id.action_set_output_rate_8000).setChecked(true);
                break;
            case 16000:
                menu.findItem(R.id.action_set_output_rate_16000).setChecked(true);
                break;
            case 32000:
                menu.findItem(R.id.action_set_output_rate_32000).setChecked(true);
                break;
            case 44100:
                menu.findItem(R.id.action_set_output_rate_44100).setChecked(true);
                break;
            case 48000:
                menu.findItem(R.id.action_set_output_rate_48000).setChecked(true);
                break;
        }
    }

    private void updateRecordRateMenu() {
        switch (recordRate) {
            case 8000:
                menu.findItem(R.id.action_set_record_rate_8000).setChecked(true);
                break;
            case 16000:
                menu.findItem(R.id.action_set_record_rate_16000).setChecked(true);
                break;
            case 32000:
                menu.findItem(R.id.action_set_record_rate_32000).setChecked(true);
                break;
            case 44100:
                menu.findItem(R.id.action_set_record_rate_44100).setChecked(true);
                break;
            case 48000:
                menu.findItem(R.id.action_set_record_rate_48000).setChecked(true);
                break;
        }
    }

    private void updateRecordChannelMenu() {
        switch (recordChannel) {
            case 0:
                menu.findItem(R.id.action_set_record_channel_default).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.action_set_record_channel_first).setChecked(true);
                break;
            case 2:
                menu.findItem(R.id.action_set_record_channel_second).setChecked(true);
                break;
            case 3:
                menu.findItem(R.id.action_set_record_channel_summation).setChecked(true);
                break;
            case 4:
                menu.findItem(R.id.action_set_record_channel_analytic).setChecked(true);
                break;
        }
    }

    private void updateAudioSourceMenu() {
        switch (audioSource) {
            case MediaRecorder.AudioSource.DEFAULT:
                menu.findItem(R.id.action_set_source_default).setChecked(true);
                break;
            case MediaRecorder.AudioSource.MIC:
                menu.findItem(R.id.action_set_source_microphone).setChecked(true);
                break;
            case MediaRecorder.AudioSource.CAMCORDER:
                menu.findItem(R.id.action_set_source_camcorder).setChecked(true);
                break;
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                menu.findItem(R.id.action_set_source_voice_recognition).setChecked(true);
                break;
            case MediaRecorder.AudioSource.UNPROCESSED:
                menu.findItem(R.id.action_set_source_unprocessed).setChecked(true);
                break;
        }
    }

    private void setOutputChannel(int newChannelSelect) {
        if (radioBlocksAudioChange())
            return;
        if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
            return;
        if (outputChannel == newChannelSelect)
            return;
        outputChannel = newChannelSelect;
        updateOutputChannelMenu();
        initAudioTrack();
    }

    private void updateOutputChannelMenu() {
        switch (outputChannel) {
            case 0:
                menu.findItem(R.id.action_set_output_channel_default).setChecked(true);
                break;
            case 1:
                menu.findItem(R.id.action_set_output_channel_first).setChecked(true);
                break;
            case 2:
                menu.findItem(R.id.action_set_output_channel_second).setChecked(true);
                break;
            case 4:
                menu.findItem(R.id.action_set_output_channel_analytic).setChecked(true);
                break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        menu.findItem(R.id.action_set_source_unprocessed).setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        this.menu = menu;
        callSignMenuItem = menu.findItem(R.id.action_edit_call_sign);
        updateCallSignMenuItem();
        pingMenuItem = menu.findItem(R.id.action_ping);
        updatePingMenuItem();
        updateOutputRateMenu();
        updateOutputChannelMenu();
        updateRecordRateMenu();
        updateRecordChannelMenu();
        updateAudioSourceMenu();
        updateNoiseSymbolsMenu();
        updateRepeaterDelayMenu();
        updateRepeaterDebounceMenu();
        updateFancyHeaderMenu();
        updateRepeaterModeMenu();
        updateDeleteMessagesOnCloseMenu();
        menu.findItem(R.id.action_radio_mute_speaker).setChecked(isRadioMuteEnabled());
        updateAckMenu();
        return true;
    }

    private void updateCallSignMenuItem() {
        if (callSignMenuItem != null) {
            callSignMenuItem.setTitle(callSign);
        }
    }

    private void updatePingMenuItem() {
        if (pingMenuItem == null) return;
        Drawable icon = pingMenuItem.getIcon();
        if (icon == null) return;
        int color = beaconRunning ? ContextCompat.getColor(this, R.color.beacon_green)
                                  : ContextCompat.getColor(this, R.color.gray);
        DrawableCompat.setTint(icon.mutate(), color);
        pingMenuItem.setIcon(icon);
    }

    private void startBeacon() {
        if (beaconRunning) return;
        beaconRunning = true;
        transmitMessage("", false);
        beaconRunnable = new Runnable() {
            @Override
            public void run() {
                if (beaconRunning) {
                    transmitMessage("", false);
                    beaconHandler.postDelayed(this, BEACON_INTERVAL);
                }
            }
        };
        beaconHandler.postDelayed(beaconRunnable, BEACON_INTERVAL);
        setStatus(getString(R.string.beacon_started));
        updatePingMenuItem();
    }

    private void stopBeacon() {
        if (!beaconRunning) return;
        beaconRunning = false;
        if (beaconRunnable != null) {
            beaconHandler.removeCallbacks(beaconRunnable);
            beaconRunnable = null;
        }
        setStatus(getString(R.string.beacon_stopped));
        updatePingMenuItem();
    }

    private void toggleBeacon() {
        if (beaconRunning) {
            stopBeacon();
        } else {
            startBeacon();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (lockCover != null)
            return true;

        if (id == R.id.action_app_lock) {
            toggleAppLock();
            return true;
        }

        if (id == R.id.action_ping) {
            toggleBeacon();
            return true;
        }

        if (id == R.id.action_wipe_all) {
            confirmWipeAllData();
            return true;
        }

        if (id == R.id.action_ack_off) {
            setAckMode(ACK_OFF);
            return true;
        }
        if (id == R.id.action_ack_addressed) {
            setAckMode(ACK_ADDRESSED);
            return true;
        }
        if (id == R.id.action_ack_all) {
            setAckMode(ACK_ALL);
            return true;
        }
        if (id == R.id.action_bluetooth_radio) {
            showRadioDialog();
            return true;
        }
        if (id == R.id.action_rig_control) {
            showRigDialog();
            return true;
        }
        if (id == R.id.action_radio_mute_speaker) {
            toggleRadioMute(item);
            return true;
        }

        if (id == R.id.action_contacts) {
            showContactsDialog();
            return true;
        }

        if (id == R.id.action_delete_messages) {
            if (messages.getCount() > 0)
                deleteMessages();
            return true;
        }
        if (id == R.id.action_delete_on_close) {
            toggleDeleteMessagesOnClose();
            return true;
        }
        if (id == R.id.action_enable_ultrasonic) {
            enableUltrasonic();
            return true;
        }
        if (id == R.id.action_set_output_rate_8000) {
            setOutputRate(8000);
            return true;
        }
        if (id == R.id.action_set_output_rate_16000) {
            setOutputRate(16000);
            return true;
        }
        if (id == R.id.action_set_output_rate_32000) {
            setOutputRate(32000);
            return true;
        }
        if (id == R.id.action_set_output_rate_44100) {
            setOutputRate(44100);
            return true;
        }
        if (id == R.id.action_set_output_rate_48000) {
            setOutputRate(48000);
            return true;
        }
        if (id == R.id.action_set_output_channel_default) {
            setOutputChannel(0);
            return true;
        }
        if (id == R.id.action_set_output_channel_first) {
            setOutputChannel(1);
            return true;
        }
        if (id == R.id.action_set_output_channel_second) {
            setOutputChannel(2);
            return true;
        }
        if (id == R.id.action_set_output_channel_analytic) {
            setOutputChannel(4);
            return true;
        }
        if (id == R.id.action_set_record_rate_8000) {
            setRecordRate(8000);
            return true;
        }
        if (id == R.id.action_set_record_rate_16000) {
            setRecordRate(16000);
            return true;
        }
        if (id == R.id.action_set_record_rate_32000) {
            setRecordRate(32000);
            return true;
        }
        if (id == R.id.action_set_record_rate_44100) {
            setRecordRate(44100);
            return true;
        }
        if (id == R.id.action_set_record_rate_48000) {
            setRecordRate(48000);
            return true;
        }
        if (id == R.id.action_set_record_channel_default) {
            setRecordChannel(0);
            return true;
        }
        if (id == R.id.action_set_record_channel_first) {
            setRecordChannel(1);
            return true;
        }
        if (id == R.id.action_set_record_channel_second) {
            setRecordChannel(2);
            return true;
        }
        if (id == R.id.action_set_record_channel_summation) {
            setRecordChannel(3);
            return true;
        }
        if (id == R.id.action_set_record_channel_analytic) {
            setRecordChannel(4);
            return true;
        }
        if (id == R.id.action_set_source_default) {
            setAudioSource(MediaRecorder.AudioSource.DEFAULT);
            return true;
        }
        if (id == R.id.action_set_source_microphone) {
            setAudioSource(MediaRecorder.AudioSource.MIC);
            return true;
        }
        if (id == R.id.action_set_source_camcorder) {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            return true;
        }
        if (id == R.id.action_set_source_voice_recognition) {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            return true;
        }
        if (id == R.id.action_set_source_unprocessed) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setAudioSource(MediaRecorder.AudioSource.UNPROCESSED);
                return true;
            }
            return false;
        }
        if (id == R.id.action_show_spectrum) {
            spectrumAnalyzer();
            return true;
        }
        if (id == R.id.action_edit_call_sign) {
            editCallSign();
            return true;
        }
        if (id == R.id.action_set_carrier_frequency) {
            setCarrierFrequency();
            return true;
        }
        if (id == R.id.action_disable_noise) {
            setNoiseSymbols(0);
            return true;
        }
        if (id == R.id.action_set_noise_quarter_second) {
            setNoiseSymbols(1);
            return true;
        }
        if (id == R.id.action_set_noise_half_second) {
            setNoiseSymbols(3);
            return true;
        }
        if (id == R.id.action_set_noise_one_second) {
            setNoiseSymbols(6);
            return true;
        }
        if (id == R.id.action_set_noise_two_seconds) {
            setNoiseSymbols(11);
            return true;
        }
        if (id == R.id.action_set_noise_four_seconds) {
            setNoiseSymbols(22);
            return true;
        }
        if (id == R.id.action_set_repeater_no_delay) {
            setRepeaterDelay(0);
            return true;
        }
        if (id == R.id.action_set_repeater_delay_one_second) {
            setRepeaterDelay(1);
            return true;
        }
        if (id == R.id.action_set_repeater_delay_two_seconds) {
            setRepeaterDelay(2);
            return true;
        }
        if (id == R.id.action_set_repeater_delay_four_seconds) {
            setRepeaterDelay(4);
            return true;
        }
        if (id == R.id.action_set_repeater_delay_eight_seconds) {
            setRepeaterDelay(8);
            return true;
        }
        if (id == R.id.action_set_repeater_allow_bouncing) {
            setRepeaterDebounce(0);
            return true;
        }
        if (id == R.id.action_set_repeater_debounce_quarter_minute) {
            setRepeaterDebounce(15);
            return true;
        }
        if (id == R.id.action_set_repeater_debounce_half_minute) {
            setRepeaterDebounce(30);
            return true;
        }
        if (id == R.id.action_set_repeater_debounce_one_minute) {
            setRepeaterDebounce(60);
            return true;
        }
        if (id == R.id.action_set_repeater_debounce_two_minutes) {
            setRepeaterDebounce(120);
            return true;
        }
        if (id == R.id.action_enable_fancy_header) {
            setFancyHeader(true);
            return true;
        }
        if (id == R.id.action_disable_fancy_header) {
            setFancyHeader(false);
            return true;
        }
        if (id == R.id.action_enable_repeater_mode) {
            setRepeaterMode(true);
            return true;
        }
        if (id == R.id.action_disable_repeater_mode) {
            setRepeaterMode(false);
            return true;
        }
        if (id == R.id.action_enable_night_mode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
            return true;
        }
        if (id == R.id.action_disable_night_mode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
            return true;
        }
        if (id == R.id.action_force_quit) {
            forcedQuit();
            return true;
        }
        if (id == R.id.action_password) {
            showPasswordDialog();
            return true;
        }
        if (id == R.id.action_privacy_policy) {
            showTextPage(getString(R.string.privacy_policy), getString(R.string.privacy_policy_text));
            return true;
        }
        if (id == R.id.action_about) {
            showTextPage(getString(R.string.about), getString(R.string.about_text, BuildConfig.VERSION_NAME));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void deleteMessages() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.delete_messages)
                .setMessage(R.string.delete_messages_prompt)
                .setPositiveButton(R.string.delete, (dialog, which) -> clearStoredMessages())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void clearStoredMessages() {
        messages.clear();
        PictureUtil.prune(this, new java.util.HashSet<>());
        incomingPictures.clear();
        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = pref.edit();
        for (int i = 0; i < 100; ++i)
            editor.remove("m" + i);
        // commit rather than apply: this can run while the app is shutting down
        editor.commit();
    }

    private void toggleDeleteMessagesOnClose() {
        if (deleteMessagesOnClose) {
            deleteMessagesOnClose = false;
            updateDeleteMessagesOnCloseMenu();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.delete_messages_on_close)
                .setMessage(R.string.delete_messages_on_close_prompt)
                .setPositiveButton(R.string.enable, (dialog, which) -> {
                    deleteMessagesOnClose = true;
                    updateDeleteMessagesOnCloseMenu();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Applies the password dialog's "Hide my call sign" box; turning it on needs the warning accepted first. */
    private void setHideCallSign(boolean hide) {
        if (!hide || hideCallSign) {
            hideCallSign = hide;
            storeSettings();
            return;
        }
        new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.hide_call_sign)
                .setMessage(R.string.hide_call_sign_prompt)
                .setPositiveButton(R.string.enable, (dialog, which) -> {
                    hideCallSign = true;
                    storeSettings();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Shows the "call sign hidden" note under an Encrypt box while an encrypted message would hide it. */
    private void updateHiddenNote(TextView note, CheckBox encrypt) {
        note.setVisibility(encrypt.isChecked() && encrypt.isEnabled() && hidingCallSign() ? View.VISIBLE : View.GONE);
    }

    /** True when an encrypted transmission should carry the call sign inside the encryption instead of the header. */
    private boolean hidingCallSign() {
        return hideCallSign && password != null && !password.isEmpty() && hasRealCallSign();
    }

    /** Extra bytes the hidden call sign takes from an encrypted message, or 0 when it is not hidden. */
    private int hiddenCallOverhead() {
        return hidingCallSign() ? HiddenCall.overhead(callSign.trim()) : 0;
    }

    private void confirmWipeAllData() {
        new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.wipe_all_data)
                .setMessage(R.string.wipe_all_data_prompt)
                .setPositiveButton(R.string.wipe, (dialog, which) -> wipeAllData())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /**
     * Erases everything the app has stored (messages, pictures, locations, password, call sign, contacts and
     * settings), like "Clear data" in Android's app settings, then closes the app.
     */
    private void wipeAllData() {
        wiping = true;
        pictureQueue = null;
        stopBeacon();
        handler.removeCallbacksAndMessages(null);
        stopListening();
        if (audioTrack != null)
            audioTrack.stop();
        // Disconnecting gives the radio its speaker volume back
        if (radioLink != null)
            radioLink.disconnect();
        if (rig != null)
            rig.shutdownAndWait(1000);
        password = null;
        callSign = "";
        draftText = "";
        messages.clear();
        contacts.clear();
        sentMessagesById.clear();
        incomingPictures.clear();
        PasswordStore.destroy(this);
        getPreferences(Context.MODE_PRIVATE).edit().clear().commit();
        deleteRecursively(getFilesDir());
        deleteRecursively(getCacheDir());
        // Also leave no picture of the messages behind in the recent apps screen
        finishAndRemoveTask();
        android.app.ActivityManager am = (android.app.ActivityManager) getApplicationContext().getSystemService(Context.ACTIVITY_SERVICE);
        // Wipes whatever is left (all preferences files, databases) and ends the app
        if (am == null || !am.clearApplicationUserData())
            System.exit(0);
    }

    private static void deleteRecursively(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null)
            for (java.io.File c : children)
                deleteRecursively(c);
        f.delete();
    }

    private boolean isAppLockEnabled() {
        return getPreferences(Context.MODE_PRIVATE).getBoolean("appLock", false);
    }

    private boolean canUsePhoneLock() {
        return BiometricManager.from(this).canAuthenticate(LOCK_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /** Turning the lock on asks for the phone's lock once, so nobody enables a lock they cannot open. */
    private void toggleAppLock() {
        if (isAppLockEnabled()) {
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("appLock", false).apply();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            updateDeleteMessagesOnCloseMenu();
            return;
        }
        if (!canUsePhoneLock()) {
            Toast.makeText(this, R.string.app_lock_needs_screen_lock, Toast.LENGTH_LONG).show();
            updateDeleteMessagesOnCloseMenu();
            return;
        }
        askPhoneLock(() -> {
            getPreferences(Context.MODE_PRIVATE).edit().putBoolean("appLock", true).apply();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            updateDeleteMessagesOnCloseMenu();
            Toast.makeText(this, R.string.app_lock_enabled, Toast.LENGTH_LONG).show();
        });
    }

    private void askPhoneLock(Runnable onSuccess) {
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        onSuccess.run();
                    }
                });
        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.app_lock_title))
                .setSubtitle(getString(R.string.app_lock_subtitle))
                .setAllowedAuthenticators(LOCK_AUTHENTICATORS)
                .build());
    }

    /** Hides everything behind a cover with an Unlock button and asks for the phone's lock. */
    private void lockApp() {
        if (!canUsePhoneLock()) {
            // Never lock someone out: without a screen lock there is nothing to unlock with
            Toast.makeText(this, R.string.app_lock_no_screen_lock, Toast.LENGTH_LONG).show();
            return;
        }
        android.util.TypedValue bg = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorBackground, bg, true);
        LinearLayout cover = new LinearLayout(this);
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setGravity(Gravity.CENTER);
        cover.setBackgroundColor(bg.data);
        cover.setClickable(true);
        TextView title = new TextView(this);
        title.setText(R.string.app_locked);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        Button unlock = new Button(this);
        unlock.setText(R.string.unlock);
        unlock.setOnClickListener(v -> askPhoneLock(this::unlockApp));
        cover.addView(title);
        cover.addView(unlock);
        addContentView(cover, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockCover = cover;
        if (getSupportActionBar() != null)
            getSupportActionBar().hide();
        // Ask once the screen is up, not in the middle of starting the app
        cover.post(() -> askPhoneLock(this::unlockApp));
    }

    private void unlockApp() {
        if (lockCover == null)
            return;
        ((ViewGroup) lockCover.getParent()).removeView(lockCover);
        lockCover = null;
        if (getSupportActionBar() != null)
            getSupportActionBar().show();
    }

    private void forcedQuit() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.force_quit)
                .setMessage(R.string.force_quit_prompt)
                .setPositiveButton(R.string.quit, (dialog, which) -> {
                    storeSettings();
                    if (deleteMessagesOnClose)
                        clearStoredMessages();
                    if (rig != null)
                        rig.shutdownAndWait(1000);
                    System.exit(0);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void enableUltrasonic() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.enable_ultrasonic)
                .setMessage(R.string.enable_ultrasonic_prompt)
                .setPositiveButton(R.string.enable, (dialogInterface, i) -> ultrasonicEnabled = true)
                .setNegativeButton(R.string.disable, (dialogInterface, i) -> ultrasonicEnabled = false)
                .show();
    }

    private void spectrumAnalyzer() {
        View view = getLayoutInflater().inflate(R.layout.spectrum_analyzer, null);
        spectrogramView = view.findViewById(R.id.spectrogram);
        spectrumView = view.findViewById(R.id.spectrum);
        spectrumBitmap = Bitmap.createBitmap(spectrumWidth, spectrumHeight, Bitmap.Config.ARGB_8888);
        spectrogramBitmap = Bitmap.createBitmap(spectrogramWidth, spectrogramHeight, Bitmap.Config.ARGB_8888);
        spectrumView.setImageBitmap(spectrumBitmap);
        spectrogramView.setImageBitmap(spectrogramBitmap);
        spectrumPixels = new int[spectrumWidth * spectrumHeight];
        spectrogramPixels = new int[spectrogramWidth * spectrogramHeight];
        spectrumTint = ContextCompat.getColor(this, R.color.tint);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.spectrum_analyzer);
        builder.setView(view);
        builder.setNeutralButton(R.string.close, (dialogInterface, i) -> showSpectrum = false);
        builder.setOnCancelListener(dialogInterface -> showSpectrum = false);
        builder.show();
        showSpectrum = true;
    }

    // ========== composeMessage: encrypted thresholds 35/68/99, plain 85/128/170 ==========
    private void composeMessage(String temp) {
        View view = getLayoutInflater().inflate(R.layout.compose_message, null);
        EditText edit = view.findViewById(R.id.message);
        CheckBox encryptCheck = view.findViewById(R.id.encrypt_checkbox);
        TextView capacity = view.findViewById(R.id.capacity);
        view.findViewById(R.id.salute_button).setOnClickListener(
                v -> showSaluteDialog(edit, encryptCheck.isChecked() && encryptCheck.isEnabled()));

        // Location messages can be encrypted too: receivers decrypt before looking for 📍[geo:...],
        // so stations with the password still get a tappable location and others see ciphertext.
        boolean hasPassword = password != null && !password.isEmpty();
        encryptCheck.setEnabled(hasPassword);
        // Restore the last choice; the stored choice is only changed by the user toggling the box
        encryptCheck.setChecked(hasPassword && encryptMessage);

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.compose_message);
        builder.setView(view);

        builder.setNeutralButton(R.string.draft, (dialogInterface, i) -> draftText = edit.getText().toString());
        builder.setNegativeButton(R.string.discard, (dialogInterface, i) -> {
            if (temp == null) draftText = "";
        });
        builder.setPositiveButton(R.string.transmit, (dialogInterface, i) -> {
            if (temp == null) draftText = "";
            transmitMessage(edit.getText().toString(), encryptCheck.isChecked());
        });
        builder.setOnCancelListener(dialogInterface -> {
            if (temp == null) {
                String text = edit.getText().toString();
                draftText = text;
            }
        });

        AlertDialog dialog = builder.show();
        Context context = this;
        view.findViewById(R.id.picture_button).setOnClickListener(v -> {
            if (temp == null)
                draftText = edit.getText().toString();
            pictureEncrypt = encryptCheck.isChecked() && encryptCheck.isEnabled();
            dialog.dismiss();
            choosePictureSource();
        });

        final TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String raw = s.toString();
                int inputBytes = raw.getBytes(StandardCharsets.UTF_8).length;
                boolean encrypt = encryptCheck.isChecked() && encryptCheck.isEnabled();

                int[] thresholds;
                if (encrypt) {
                    // A hidden call sign travels inside the encryption and takes from the same space
                    int o = hiddenCallOverhead();
                    thresholds = new int[]{35 - o, 68 - o, 99 - o};
                } else {
                    thresholds = new int[]{85, 128, 170};
                }
                int maxBytes = thresholds[2];

                if (inputBytes > maxBytes) {
                    int over = inputBytes - maxBytes;
                    capacity.setText(getResources().getQuantityString(R.plurals.over_capacity, over, over));
                    capacity.setTextColor(Color.RED);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                    return;
                }

                int resId;
                int threshold;
                if (inputBytes <= thresholds[0]) {
                    threshold = thresholds[0];
                    resId = R.plurals.strong_bytes_left;
                } else if (inputBytes <= thresholds[1]) {
                    threshold = thresholds[1];
                    resId = R.plurals.medium_bytes_left;
                } else {
                    threshold = thresholds[2];
                    resId = R.plurals.normal_bytes_left;
                }
                int remaining = threshold - inputBytes;
                capacity.setText(getResources().getQuantityString(resId, remaining, remaining));
                capacity.setTextColor(ContextCompat.getColor(context, R.color.tint));

                // Enable send only when there is content and it is not all whitespace
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                      .setEnabled(inputBytes > 0 && !isAllWhitespace(raw));
            }

            @Override
            public void afterTextChanged(Editable s) {}
        };
        edit.addTextChangedListener(textWatcher);

        TextView hiddenNote = view.findViewById(R.id.call_sign_hidden_note);
        updateHiddenNote(hiddenNote, encryptCheck);
        encryptCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            encryptMessage = isChecked;
            updateHiddenNote(hiddenNote, encryptCheck);
            String text = edit.getText().toString();
            textWatcher.onTextChanged(text, 0, 0, 0);
        });

        edit.setText(temp == null ? draftText : temp);
    }

    // ========== SALUTE report ==========
    /**
     * Shows the SALUTE form. On OK the compact report is added to the compose box
     * (on a new line if the box already has text). A live counter shows the room left in
     * the message, which is smaller when it will be encrypted.
     */
    private void showSaluteDialog(EditText target, boolean encrypted) {
        View view = getLayoutInflater().inflate(R.layout.salute_dialog, null);
        EditText sizeEdit = view.findViewById(R.id.salute_size);
        EditText activityEdit = view.findViewById(R.id.salute_activity);
        EditText locationEdit = view.findViewById(R.id.salute_location);
        EditText unitEdit = view.findViewById(R.id.salute_unit);
        EditText timeEdit = view.findViewById(R.id.salute_time);
        EditText equipmentEdit = view.findViewById(R.id.salute_equipment);
        Spinner formatSpinner = view.findViewById(R.id.salute_location_format);
        RadioGroup zoneGroup = view.findViewById(R.id.salute_time_zone);
        Button gpsButton = view.findViewById(R.id.salute_gps_button);
        Button nowButton = view.findViewById(R.id.salute_now_button);
        TextView bytesView = view.findViewById(R.id.salute_bytes);
        EditText[] fields = {sizeEdit, activityEdit, locationEdit, unitEdit, timeEdit, equipmentEdit};

        // The last values this form filled in itself, so switching format/zone only
        // rewrites them while the user has not typed over them.
        final Location[] fix = {null};
        final String[] autoLocation = {null};
        final Date[] fixTime = {null};
        final String[] autoTime = {null};

        formatSpinner.setSelection(saluteLocationFormat);
        zoneGroup.check(saluteTimeUtc ? R.id.salute_time_utc : R.id.salute_time_local);

        Runnable fillLocation = () -> {
            if (fix[0] == null) return;
            String text = GeoFormat.format(saluteLocationFormat, fix[0].getLatitude(), fix[0].getLongitude());
            if (text == null) {
                Toast.makeText(this, R.string.salute_mgrs_unavailable, Toast.LENGTH_SHORT).show();
                text = GeoFormat.latLong(fix[0].getLatitude(), fix[0].getLongitude());
            }
            locationEdit.setText(text);
            autoLocation[0] = text;
        };
        Runnable fillTime = () -> {
            if (fixTime[0] == null) return;
            String text = saluteTimeUtc
                    ? SaluteReport.utcTime(fixTime[0])
                    : SaluteReport.localTime(fixTime[0], TimeZone.getDefault());
            timeEdit.setText(text);
            autoTime[0] = text;
        };

        // Same limits as the compose window: 170 bytes plain, 99 bytes before encryption
        final int limit = encrypted ? 99 - hiddenCallOverhead() : 170;
        Runnable updateBytes = () -> {
            String report = SaluteReport.build(sizeEdit.getText().toString(), activityEdit.getText().toString(),
                    locationEdit.getText().toString(), unitEdit.getText().toString(),
                    timeEdit.getText().toString(), equipmentEdit.getText().toString());
            String existing = target.getText().toString();
            String message = existing.trim().isEmpty() ? report : existing + "\n" + report;
            int left = limit - message.getBytes(StandardCharsets.UTF_8).length;
            if (left >= 0) {
                bytesView.setText(getString(encrypted ? R.string.salute_bytes_left_encrypted : R.string.salute_bytes_left, left));
                bytesView.setTextColor(ContextCompat.getColor(this, R.color.tint));
            } else {
                bytesView.setText(getString(encrypted ? R.string.salute_bytes_over_encrypted : R.string.salute_bytes_over, -left));
                bytesView.setTextColor(Color.RED);
            }
        };
        TextWatcher bytesWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateBytes.run();
            }
        };
        for (EditText field : fields)
            field.addTextChangedListener(bytesWatcher);
        updateBytes.run();

        formatSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                if (position == saluteLocationFormat) return;
                saluteLocationFormat = position;
                String current = locationEdit.getText().toString();
                if (current.equals(autoLocation[0])) fillLocation.run();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        zoneGroup.setOnCheckedChangeListener((group, checkedId) -> {
            saluteTimeUtc = checkedId == R.id.salute_time_utc;
            String current = timeEdit.getText().toString();
            if (current.equals(autoTime[0])) fillTime.run();
        });

        nowButton.setOnClickListener(v -> {
            fixTime[0] = new Date();
            fillTime.run();
        });

        gpsButton.setOnClickListener(v -> {
            if (!hasLocationPermission()) {
                Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isLocationServiceEnabled()) {
                Toast.makeText(this, R.string.location_service_disabled, Toast.LENGTH_SHORT).show();
                return;
            }
            final Location last = getLastKnownLocation();
            if (isLocationFresh(last)) {
                fix[0] = last;
                fillLocation.run();
                return;
            }
            Toast.makeText(this, R.string.location_fetching, Toast.LENGTH_SHORT).show();
            requestCurrentLocation(loc -> {
                Location best = loc != null ? loc : last;
                if (best == null) {
                    Toast.makeText(this, R.string.location_failed, Toast.LENGTH_SHORT).show();
                    return;
                }
                fix[0] = best;
                fillLocation.run();
            });
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.salute_title);
        builder.setView(view);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> {
            String report = SaluteReport.build(
                    sizeEdit.getText().toString(),
                    activityEdit.getText().toString(),
                    locationEdit.getText().toString(),
                    unitEdit.getText().toString(),
                    timeEdit.getText().toString(),
                    equipmentEdit.getText().toString());
            if (report.isEmpty()) return;
            String existing = target.getText().toString();
            target.setText(existing.trim().isEmpty() ? report : existing + "\n" + report);
            target.setSelection(target.getText().length());
        });
        builder.show();
    }

    // ========== Bluetooth radio ==========

    private boolean isRadioConnected() {
        return radioLink != null && radioLink.isConnected();
    }

    /** While the radio is connected the decoder and encoder run at its 32 kHz rate, so audio settings stay locked. */
    private boolean radioBlocksAudioChange() {
        if (!isRadioConnected())
            return false;
        Toast.makeText(this, R.string.radio_disconnect_first, Toast.LENGTH_SHORT).show();
        return true;
    }

    private final RadioLink.Listener radioListener = new RadioLink.Listener() {
        @Override
        public void onRadioAudio(short[] pcm, int count) {
            if (activityResumed && isRadioConnected() && !radioLink.isTransmitting())
                decodeAudio(pcm, count, 0);
        }

        @Override
        public void onRadioConnected(String name) {
            stopListening();
            if (!createDecoder(RadioLink.SAMPLE_RATE) || !createEncoder(RadioLink.SAMPLE_RATE)) {
                setStatus(getString(R.string.heap_error));
                radioLink.disconnect();
                return;
            }
            Toast.makeText(MainActivity.this,
                    getString(isRadioMuteEnabled() ? R.string.radio_connected_muted : R.string.radio_connected_tip, name),
                    Toast.LENGTH_LONG).show();
            setStatus(getString(R.string.radio_listening, name));
        }

        @Override
        public void onRadioDisconnected(String name, boolean wasConnected) {
            if (!createDecoder(recordRate) || !createEncoder(outputRate))
                setStatus(getString(R.string.heap_error));
            boolean volumePending = getPreferences(Context.MODE_PRIVATE).getInt("radioRestoreVolume", -1) >= 0;
            String message;
            if (!wasConnected)
                message = getString(R.string.radio_connect_failed, name);
            else if (volumePending)
                message = getString(R.string.radio_disconnected_muted, name);
            else
                message = getString(R.string.radio_disconnected, name);
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            if (activityResumed)
                startListening();
        }

        @Override
        public void onRadioTransmitDone() {
            if (isRadioConnected())
                setStatus(getString(R.string.radio_listening, radioLink.getDeviceName()));
        }

        @Override
        public void onRadioSpeakerMuted(int original) {
            // Remember the radio's volume so it can still be restored if the link drops unexpectedly.
            getPreferences(Context.MODE_PRIVATE).edit().putInt("radioRestoreVolume", original).apply();
        }

        @Override
        public void onRadioSpeakerRestored() {
            getPreferences(Context.MODE_PRIVATE).edit().remove("radioRestoreVolume").apply();
        }
    };

    private boolean isRadioMuteEnabled() {
        return getPreferences(Context.MODE_PRIVATE).getBoolean("radioMuteSpeaker", true);
    }

    private void toggleRadioMute(MenuItem item) {
        boolean mute = !isRadioMuteEnabled();
        getPreferences(Context.MODE_PRIVATE).edit().putBoolean("radioMuteSpeaker", mute).apply();
        item.setChecked(mute);
        radioLink.setSpeakerMuted(mute);
    }

    // ---------------- Optional Hamlib rig control (CAT frequency and PTT) ----------------

    private void writeOutput() {
        audioTrack.write(outputBuffer, 0, outputBuffer.length);
        framesWritten += outputBuffer.length / audioTrack.getChannelCount();
    }

    /** Whether this transmission must be keyed through Hamlib. A connected Bluetooth radio keys itself. */
    private boolean useCatPtt() {
        return rigSettings.requiresCatPtt() && !isRadioConnected();
    }

    /** Refuses to transmit, and says why, when Hamlib should key the radio but it is not connected. */
    private boolean rigBlocksTransmit() {
        if (!useCatPtt() || rig != null && rig.isConnected())
            return false;
        Toast.makeText(this, R.string.rig_needs_connection, Toast.LENGTH_LONG).show();
        return true;
    }

    /** Starts the audio already written to the output; with Hamlib CAT PTT only once the radio is keyed. */
    private void startPlayback() {
        if (!useCatPtt()) {
            audioTrack.play();
            setStatus(getString(R.string.transmitting));
            return;
        }
        if (rig == null || !rig.isConnected()) {
            abortTransmission(getString(R.string.rig_needs_connection));
            return;
        }
        catPttTransmission = true;
        setStatus(getString(R.string.rig_keying));
        rig.ptt().key(rigSettings.keyUpDelayMs, () -> {
            try {
                audioTrack.play();
                setStatus(getString(R.string.transmitting));
            } catch (IllegalStateException e) {
                abortTransmission(e.getMessage());
            }
        }, error -> abortTransmission(getString(R.string.rig_ptt_failed, error)));
    }

    /** Drops the audio waiting to be sent, unkeys the CAT radio and goes back to listening. */
    private void abortTransmission(String message) {
        catPttTransmission = false;
        if (rig != null)
            rig.ptt().releaseNow();
        try {
            audioTrack.pause();
            audioTrack.flush();
            audioTrack.stop();
        } catch (IllegalStateException ignored) {
        }
        pictureQueue = null;
        if (message != null)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        startListening();
    }

    private final RigController.Listener rigListener = new RigController.Listener() {
        @Override
        public void onRigState(RigController.State state, String message) {
            if (state == RigController.State.DISCONNECTED && catPttTransmission)
                abortTransmission(null);
            // Failures, and "connected but not in data mode"; while connecting the dialog shows the message
            if (message != null && state != RigController.State.CONNECTING)
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            if (rigDialogRefresh != null)
                rigDialogRefresh.run();
            refreshStatusFrequency();
            // Follow the radio's dial only while connected and on screen
            if (rig != null)
                rig.setPolling(state == RigController.State.CONNECTED && activityResumed);
        }

        @Override
        public void onFrequency(double hz) {
            refreshStatusFrequency();
            if (tuneDialogRefresh != null)
                tuneDialogRefresh.run();
            if (rigDialogRefresh != null)
                rigDialogRefresh.run();
        }

        @Override
        public void onPttProblem(String message) {
            if (catPttTransmission)
                abortTransmission(null);
            new AlertDialog.Builder(MainActivity.this, R.style.Theme_AlertDialog)
                    .setTitle(R.string.rig_control)
                    .setMessage(message == null ? getString(R.string.rig_watchdog) : getString(R.string.rig_unkey_failed, message))
                    .setPositiveButton(R.string.okay, null)
                    .show();
        }
    };

    /** Loads Hamlib and creates the controller the first time rig control is used. */
    private boolean ensureRig() {
        if (rig != null)
            return true;
        if (!HamlibRig.load()) {
            Toast.makeText(this, getString(R.string.rig_unavailable, HamlibRig.loadError()), Toast.LENGTH_LONG).show();
            return false;
        }
        rig = new RigController(this, handler, rigListener);
        rig.setPollAllowed(() -> !catPttTransmission && !isTransmitBusy());
        return true;
    }

    private void saveRigSettings() {
        getPreferences(Context.MODE_PRIVATE).edit()
                .putBoolean(RigSettings.KEY_ENABLED, rigSettings.enabled)
                .putInt(RigSettings.KEY_MODEL, rigSettings.model)
                .putString(RigSettings.KEY_DEVICE, rigSettings.device)
                .putInt(RigSettings.KEY_BAUD, rigSettings.baudRate)
                .putBoolean(RigSettings.KEY_CAT_PTT, rigSettings.catPtt)
                .putInt(RigSettings.KEY_KEY_UP_DELAY, rigSettings.keyUpDelayMs)
                .putBoolean(RigSettings.KEY_DATA_MODE, rigSettings.dataMode)
                .apply();
    }


    private void showRigDialog() {
        View view = getLayoutInflater().inflate(R.layout.rig_control, null);
        CheckBox enabledCheck = view.findViewById(R.id.rig_enabled);
        View options = view.findViewById(R.id.rig_options);
        AutoCompleteTextView modelEdit = view.findViewById(R.id.rig_model);
        TextView modelLabel = view.findViewById(R.id.rig_model_label);
        Spinner deviceSpinner = view.findViewById(R.id.rig_device);
        Spinner baudSpinner = view.findViewById(R.id.rig_baud);
        TextView baudHint = view.findViewById(R.id.rig_baud_hint);
        CheckBox pttCheck = view.findViewById(R.id.rig_cat_ptt);
        CheckBox dataModeCheck = view.findViewById(R.id.rig_data_mode);
        EditText delayEdit = view.findViewById(R.id.rig_delay);
        TextView statusText = view.findViewById(R.id.rig_status);
        Button connectButton = view.findViewById(R.id.rig_connect);
        View tools = view.findViewById(R.id.rig_connected_tools);
        TextView frequencyText = view.findViewById(R.id.rig_frequency);
        final RigSettings s = rigSettings;
        final int[] chosenModel = {s.model};
        // Radio-specific reminders, shown only for that model
        Runnable showBaudHint = () -> {
            boolean ft857 = chosenModel[0] == RIG_MODEL_FT857;
            baudHint.setVisibility(ft857 ? View.VISIBLE : View.GONE);
            if (ft857)
                baudHint.setText(R.string.rig_baud_hint_ft857);
        };
        showBaudHint.run();

        String[] baudLabels = new String[RigSettings.BAUD_RATES.length];
        for (int i = 0; i < baudLabels.length; ++i)
            baudLabels[i] = String.valueOf(RigSettings.BAUD_RATES[i]);
        baudSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, baudLabels));
        Runnable selectBaud = () -> {
            for (int i = 0; i < RigSettings.BAUD_RATES.length; ++i)
                if (RigSettings.BAUD_RATES[i] == s.baudRate)
                    baudSpinner.setSelection(i);
        };
        selectBaud.run();
        pttCheck.setChecked(s.catPtt);
        dataModeCheck.setChecked(s.dataMode);
        delayEdit.setText(String.valueOf(s.keyUpDelayMs));

        // USB serial adapters plugged in now; listing them needs no permission
        List<String> deviceKeys = new ArrayList<>();
        Runnable listDevices = () -> {
            deviceKeys.clear();
            List<String> labels = new ArrayList<>();
            for (com.hoho.android.usbserial.driver.UsbSerialDriver driver :
                    com.hoho.android.usbserial.driver.UsbSerialProber.getDefaultProber().findAllDrivers(
                            (android.hardware.usb.UsbManager) getSystemService(Context.USB_SERVICE))) {
                android.hardware.usb.UsbDevice d = driver.getDevice();
                String key = RigController.deviceKey(d);
                String name = Build.VERSION.SDK_INT >= 21 && d.getProductName() != null ? d.getProductName()
                        : driver.getClass().getSimpleName().replace("SerialDriver", "");
                deviceKeys.add(key);
                labels.add(name + " (" + key + ")");
            }
            if (labels.isEmpty())
                labels.add(getString(R.string.rig_no_devices));
            deviceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
            int saved = deviceKeys.indexOf(s.device);
            if (saved >= 0)
                deviceSpinner.setSelection(saved);
        };
        listDevices.run();

        // Pull what is on screen into the settings and save them (never while connected)
        Runnable readFields = () -> {
            s.model = chosenModel[0];
            int d = deviceSpinner.getSelectedItemPosition();
            if (d >= 0 && d < deviceKeys.size())
                s.device = deviceKeys.get(d);
            s.baudRate = RigSettings.BAUD_RATES[Math.max(0, baudSpinner.getSelectedItemPosition())];
            s.catPtt = pttCheck.isChecked();
            s.dataMode = dataModeCheck.isChecked();
            try {
                s.keyUpDelayMs = RigSettings.clampDelay(Integer.parseInt(delayEdit.getText().toString().trim()));
            } catch (NumberFormatException e) {
                s.keyUpDelayMs = RigSettings.DEFAULT_KEY_UP_DELAY_MS;
            }
            saveRigSettings();
        };

        Runnable refresh = () -> {
            options.setVisibility(s.enabled ? View.VISIBLE : View.GONE);
            RigController.State state = rig == null ? RigController.State.DISCONNECTED : rig.state();
            boolean editable = state == RigController.State.DISCONNECTED;
            modelEdit.setEnabled(editable);
            deviceSpinner.setEnabled(editable);
            baudSpinner.setEnabled(editable);
            pttCheck.setEnabled(editable);
            dataModeCheck.setEnabled(editable);
            delayEdit.setEnabled(editable);
            connectButton.setText(editable ? R.string.rig_connect : R.string.rig_disconnect);
            tools.setVisibility(state == RigController.State.CONNECTED ? View.VISIBLE : View.GONE);
            String text = state == RigController.State.CONNECTED
                    ? getString(R.string.rig_state_connected, modelEdit.getText())
                    : getString(state == RigController.State.CONNECTING ? R.string.rig_state_connecting : R.string.rig_state_disconnected);
            if (state == RigController.State.CONNECTED && isRadioConnected())
                text += "\n" + getString(R.string.rig_bt_radio_note);
            statusText.setText(text);
            double hz = rig == null ? -1 : rig.lastFrequency();
            frequencyText.setText(hz > 0 ? getString(R.string.rig_frequency, RigFrequencies.format(hz)) : getString(R.string.rig_frequency_unknown));
        };

        Runnable loadModels = () -> {
            if (rigModels != null) {
                showModels(modelEdit, modelLabel, chosenModel, s, () -> {
                    selectBaud.run();
                    showBaudHint.run();
                });
                return;
            }
            new Thread(() -> {
                List<HamlibRig.Model> models = HamlibRig.models();
                handler.post(() -> {
                    rigModels = models;
                    showModels(modelEdit, modelLabel, chosenModel, s, () -> {
                        selectBaud.run();
                        showBaudHint.run();
                    });
                    refresh.run();
                });
            }, "rig-models").start();
        };

        if (s.enabled) {
            if (ensureRig())
                loadModels.run();
            else
                s.enabled = false;
        }
        enabledCheck.setChecked(s.enabled);
        enabledCheck.setOnCheckedChangeListener((b, on) -> {
            if (on && !ensureRig()) {
                b.setChecked(false);
                return;
            }
            if (!on && rig != null && rig.state() != RigController.State.DISCONNECTED)
                rig.disconnect();
            s.enabled = on;
            saveRigSettings();
            if (on)
                loadModels.run();
            refresh.run();
        });

        connectButton.setOnClickListener(v -> {
            if (rig == null)
                return;
            if (rig.state() != RigController.State.DISCONNECTED) {
                rig.disconnect();
                return;
            }
            listDevices.run();
            readFields.run();
            if (s.model == RigSettings.NO_MODEL) {
                Toast.makeText(this, R.string.rig_choose_model, Toast.LENGTH_SHORT).show();
                return;
            }
            HamlibRig.ModelInfo info = HamlibRig.modelInfo(s.model);
            if (s.catPtt && info != null && !info.canSetPtt) {
                Toast.makeText(this, R.string.rig_ptt_unsupported, Toast.LENGTH_LONG).show();
                return;
            }
            rig.connect(s);
        });

        view.findViewById(R.id.rig_tune).setOnClickListener(v -> showTuneDialog(refresh));

        view.findViewById(R.id.rig_test_ptt).setOnClickListener(v -> {
            if (!s.catPtt) {
                Toast.makeText(this, R.string.rig_ptt_unsupported, Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                    .setTitle(R.string.rig_test_ptt)
                    .setMessage(R.string.rig_test_ptt_prompt)
                    .setPositiveButton(R.string.rig_test_ptt, (d, w) -> {
                        if (rig == null || !rig.isConnected() || isTransmitBusy())
                            return;
                        rig.ptt().key(0, () -> rig.ptt().release(2000),
                                error -> Toast.makeText(this, getString(R.string.rig_ptt_failed, error), Toast.LENGTH_LONG).show());
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        rigDialogRefresh = refresh;
        refresh.run();
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.rig_control)
                .setView(view)
                .setPositiveButton(R.string.okay, null)
                .create();
        dialog.setOnDismissListener(d -> {
            rigDialogRefresh = null;
            if (rig == null || rig.state() == RigController.State.DISCONNECTED)
                readFields.run();
        });
        dialog.show();
    }

    /**
     * The Tune window: the radio's frequency, step up/down, a typed frequency and the saved frequencies. Only
     * opened while a radio is connected; tunes only when the user taps, and never while transmitting.
     */
    private void showTuneDialog(Runnable onChange) {
        if (rig == null || !rig.isConnected())
            return;
        View view = getLayoutInflater().inflate(R.layout.rig_tune, null);
        TextView frequencyText = view.findViewById(R.id.tune_frequency);
        TextView messageText = view.findViewById(R.id.tune_message);
        Spinner stepSpinner = view.findViewById(R.id.tune_step);
        EditText entry = view.findViewById(R.id.tune_entry);
        LinearLayout savedList = view.findViewById(R.id.tune_saved_list);
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        HamlibRig.ModelInfo info = HamlibRig.modelInfo(rigSettings.model);
        boolean canTune = info == null || info.canSetFrequency;

        String[] stepLabels = new String[RigFrequencies.STEPS_HZ.length];
        for (int i = 0; i < stepLabels.length; ++i) {
            int st = RigFrequencies.STEPS_HZ[i];
            stepLabels[i] = getString(R.string.tune_step, st >= 1_000_000 ? st / 1_000_000 + " MHz"
                    : (st % 1000 == 0 ? String.valueOf(st / 1000) : String.valueOf(st / 1000.0)) + " kHz");
        }
        stepSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, stepLabels));
        int savedStep = prefs.getInt(RigSettings.KEY_STEP, RigFrequencies.DEFAULT_STEP_HZ);
        for (int i = 0; i < RigFrequencies.STEPS_HZ.length; ++i)
            if (RigFrequencies.STEPS_HZ[i] == savedStep)
                stepSpinner.setSelection(i);

        Runnable show = () -> {
            double hz = rig.lastFrequency();
            frequencyText.setText(hz > 0 ? RigFrequencies.format(hz) : "—");
        };
        RigController.Result<Double> done = (hz, error) -> {
            messageText.setText(error == null ? "" : getString(R.string.rig_frequency_failed, error));
            show.run();
            refreshStatusFrequency();
            if (onChange != null)
                onChange.run();
        };
        // Tunes only when allowed: not while keyed or transmitting, and only on radios Hamlib can tune
        java.util.function.LongConsumer tuneTo = hz -> {
            if (!rig.isConnected())
                return;
            if (!canTune) {
                messageText.setText(R.string.rig_set_frequency_unsupported);
                return;
            }
            if (isTransmitBusy() || rig.ptt().isKeyed()) {
                messageText.setText(R.string.tune_busy);
                return;
            }
            messageText.setText(R.string.tune_tuning);
            rig.setFrequency(hz, done);
        };

        List<RigFrequencies.Saved>[] saved = new List[]{
                RigFrequencies.parseSaved(prefs.getString(RigSettings.KEY_SAVED_FREQUENCIES, ""))};
        Runnable[] listSaved = new Runnable[1];
        listSaved[0] = () -> {
            savedList.removeAllViews();
            if (saved[0].isEmpty()) {
                TextView none = new TextView(this);
                none.setText(R.string.tune_saved_none);
                savedList.addView(none);
            }
            for (RigFrequencies.Saved f : saved[0]) {
                Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
                b.setAllCaps(false);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setText(f.name.isEmpty() ? RigFrequencies.format(f.hz) : RigFrequencies.format(f.hz) + "  " + f.name);
                b.setOnClickListener(v -> tuneTo.accept(f.hz));
                b.setOnLongClickListener(v -> {
                    new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                            .setMessage(getString(R.string.tune_delete_prompt, RigFrequencies.format(f.hz)))
                            .setPositiveButton(R.string.delete, (d, w) -> {
                                saved[0] = RigFrequencies.without(saved[0], f.hz);
                                prefs.edit().putString(RigSettings.KEY_SAVED_FREQUENCIES, RigFrequencies.serializeSaved(saved[0])).apply();
                                listSaved[0].run();
                            })
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    return true;
                });
                savedList.addView(b);
            }
        };
        listSaved[0].run();

        view.findViewById(R.id.tune_up).setOnClickListener(v -> {
            if (rig.lastFrequency() > 0)
                tuneTo.accept(RigFrequencies.step(rig.lastFrequency(),
                        RigFrequencies.STEPS_HZ[Math.max(0, stepSpinner.getSelectedItemPosition())], +1));
        });
        view.findViewById(R.id.tune_down).setOnClickListener(v -> {
            if (rig.lastFrequency() > 0)
                tuneTo.accept(RigFrequencies.step(rig.lastFrequency(),
                        RigFrequencies.STEPS_HZ[Math.max(0, stepSpinner.getSelectedItemPosition())], -1));
        });
        stepSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View v, int position, long id) {
                prefs.edit().putInt(RigSettings.KEY_STEP, RigFrequencies.STEPS_HZ[position]).apply();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        view.findViewById(R.id.tune_set).setOnClickListener(v -> {
            long hz = RigFrequencies.parseMhz(entry.getText().toString());
            if (hz < 0) {
                messageText.setText(R.string.rig_bad_frequency);
                return;
            }
            tuneTo.accept(hz);
        });
        view.findViewById(R.id.tune_save).setOnClickListener(v -> {
            double hz = rig.lastFrequency();
            if (hz <= 0)
                return;
            EditText name = new EditText(this);
            name.setHint(R.string.tune_save_name);
            name.setSingleLine(true);
            FrameLayout box = new FrameLayout(this);
            int pad = Math.round(20 * getResources().getDisplayMetrics().density);
            box.setPadding(pad, 0, pad, 0);
            box.addView(name);
            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                    .setTitle(RigFrequencies.format(hz))
                    .setView(box)
                    .setPositiveButton(R.string.okay, (d, w) -> {
                        saved[0] = RigFrequencies.withSaved(saved[0], Math.round(hz), name.getText().toString());
                        prefs.edit().putString(RigSettings.KEY_SAVED_FREQUENCIES, RigFrequencies.serializeSaved(saved[0])).apply();
                        listSaved[0].run();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });

        show.run();
        // A fresh reading straight away; while the window is open the light polling keeps it current
        messageText.setText(R.string.tune_reading);
        rig.readFrequency(done);
        tuneDialogRefresh = show;
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.tune_title)
                .setView(view)
                .setPositiveButton(R.string.okay, null)
                .create();
        dialog.setOnDismissListener(d -> tuneDialogRefresh = null);
        dialog.show();
    }

    /** Fills the model picker (search by any part of the name) and shows the chosen model. */
    private void showModels(AutoCompleteTextView modelEdit, TextView modelLabel, int[] chosenModel, RigSettings s, Runnable selectBaud) {
        RigModelAdapter adapter = new RigModelAdapter(this, rigModels);
        modelEdit.setAdapter(adapter);
        for (HamlibRig.Model m : rigModels)
            if (m.id == chosenModel[0])
                modelEdit.setText(m.toString(), false);
        // Tapping shows every model, not just the ones matching what is already in the box; typing still searches
        View.OnClickListener showAll = v -> {
            if (modelEdit.isEnabled())
                adapter.getFilter().filter(null, count -> modelEdit.showDropDown());
        };
        modelEdit.setOnClickListener(showAll);
        modelEdit.setOnFocusChangeListener((v, focused) -> {
            if (focused)
                showAll.onClick(v);
        });
        if (modelLabel != null)
            modelLabel.setText(getString(R.string.rig_model_count, rigModels.size()));
        modelEdit.setOnItemClickListener((parent, v, position, id) -> {
            HamlibRig.Model m = (HamlibRig.Model) parent.getItemAtPosition(position);
            chosenModel[0] = m.id;
            // A newly chosen model starts at a baud rate it supports
            HamlibRig.ModelInfo info = HamlibRig.modelInfo(m.id);
            if (info != null) {
                s.baudRate = RigSettings.defaultBaud(info.rateMin, info.rateMax);
                selectBaud.run();
            }
        });
    }

    /** Renders the configured transmission at 32 kHz and hands it to the radio after delayMs. */
    private void transmitViaRadio(long delayMs) {
        short[] symbol = new short[RADIO_EXTENDED_LENGTH];
        List<short[]> symbols = new ArrayList<>();
        // A full-length message is well under 100 symbols; the cap only guards against a runaway loop.
        while (symbols.size() < 400 && produceEncoder(symbol, 0))
            symbols.add(symbol.clone());
        short[] pcm = new short[symbols.size() * RADIO_EXTENDED_LENGTH];
        for (int i = 0; i < symbols.size(); ++i)
            System.arraycopy(symbols.get(i), 0, pcm, i * RADIO_EXTENDED_LENGTH, RADIO_EXTENDED_LENGTH);
        handler.postDelayed(() -> {
            if (radioLink.transmit(pcm, pcm.length))
                setStatus(getString(R.string.transmitting));
            else
                Toast.makeText(this, R.string.radio_busy, Toast.LENGTH_SHORT).show();
        }, delayMs);
    }

    @SuppressWarnings("MissingPermission")
    private void showRadioDialog() {
        if (isRadioConnected()) {
            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                    .setTitle(R.string.bluetooth_radio)
                    .setMessage(getString(R.string.radio_connected_to, radioLink.getDeviceName()))
                    .setPositiveButton(R.string.radio_disconnect, (dialog, which) -> radioLink.disconnect())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, bluetoothPermissionID);
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, R.string.radio_bluetooth_off, Toast.LENGTH_LONG).show();
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.radio_permission_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            showRadioPairingHelp();
            return;
        }
        String lastAddress = getPreferences(Context.MODE_PRIVATE).getString("radioAddress", "");
        List<BluetoothDevice> devices = new ArrayList<>(bonded);
        String[] names = new String[devices.size()];
        int selected = 0;
        for (int i = 0; i < devices.size(); ++i) {
            BluetoothDevice d = devices.get(i);
            String name = d.getName() != null ? d.getName() : d.getAddress();
            names[i] = name;
            String upper = name.toUpperCase(Locale.US);
            if (d.getAddress().equals(lastAddress)
                    || (lastAddress.isEmpty() && (upper.contains("UV-PRO") || upper.contains("VR-N76"))))
                selected = i;
        }
        final int[] choice = {selected};
        new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.radio_choose)
                .setSingleChoiceItems(names, selected, (dialog, which) -> choice[0] = which)
                .setPositiveButton(R.string.radio_connect, (dialog, which) -> {
                    BluetoothDevice device = devices.get(choice[0]);
                    getPreferences(Context.MODE_PRIVATE).edit().putString("radioAddress", device.getAddress()).apply();
                    setStatus(getString(R.string.radio_connecting, names[choice[0]]));
                    radioLink.configureSpeakerMute(isRadioMuteEnabled(),
                            getPreferences(Context.MODE_PRIVATE).getInt("radioRestoreVolume", -1));
                    radioLink.connect(device);
                })
                .setNeutralButton(R.string.radio_pair_new, (dialog, which) -> showRadioPairingHelp())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Explains how to pair the radio, with a shortcut to the system Bluetooth settings. */
    private void showRadioPairingHelp() {
        new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.radio_pair_title)
                .setMessage(R.string.radio_pair_help)
                .setPositiveButton(R.string.radio_open_bluetooth_settings, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, R.string.radio_bluetooth_off, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }



    // ========== Pictures ==========

    private final ActivityResultLauncher<PickVisualMediaRequest> pickPicture =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri == null)
                    return;
                try {
                    showPicturePreview(PictureUtil.loadUri(this, uri, 640));
                } catch (Exception e) {
                    Toast.makeText(this, R.string.picture_load_failed, Toast.LENGTH_SHORT).show();
                }
            });

    private final ActivityResultLauncher<Void> takePicture =
            registerForActivityResult(new ActivityResultContracts.TakePicturePreview(), bitmap -> {
                if (bitmap != null)
                    showPicturePreview(bitmap);
            });

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted)
                    takePicture.launch(null);
                else
                    Toast.makeText(this, R.string.picture_camera_denied, Toast.LENGTH_SHORT).show();
            });

    private void choosePictureSource() {
        String[] choices = {getString(R.string.picture_take), getString(R.string.picture_choose)};
        new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.picture_send_title)
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                            takePicture.launch(null);
                        else
                            cameraPermission.launch(Manifest.permission.CAMERA);
                    } else {
                        pickPicture.launch(new PickVisualMediaRequest.Builder()
                                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                                .build());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Lets the user pick size, robustness and encryption, showing exactly what will be sent. */
    private void showPicturePreview(Bitmap source) {
        View view = getLayoutInflater().inflate(R.layout.picture_preview, null);
        ImageView preview = view.findViewById(R.id.picture_preview);
        TextView info = view.findViewById(R.id.picture_info);
        RadioGroup sizeGroup = view.findViewById(R.id.picture_size);
        CheckBox robustCheck = view.findViewById(R.id.picture_robust);
        CheckBox encryptCheck = view.findViewById(R.id.picture_encrypt);
        SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
        int[] sizeIds = {R.id.picture_size_small, R.id.picture_size_medium, R.id.picture_size_large};
        sizeGroup.check(sizeIds[Math.max(0, Math.min(2, pref.getInt("pictureSize", 0)))]);
        robustCheck.setChecked(pref.getBoolean("pictureRobust", false));
        boolean hasPassword = password != null && !password.isEmpty();
        encryptCheck.setEnabled(hasPassword);
        encryptCheck.setChecked(hasPassword && pictureEncrypt);
        if (!hasPassword)
            encryptCheck.setText(R.string.picture_encrypt_no_password);

        final byte[][] current = {null};
        final int[] sizeIndex = {0};
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.picture_send_title)
                .setView(view)
                .setPositiveButton(R.string.transmit, (d, which) -> {
                    pref.edit().putInt("pictureSize", sizeIndex[0]).putBoolean("pictureRobust", robustCheck.isChecked()).apply();
                    sendPicture(current[0], encryptCheck.isChecked() && encryptCheck.isEnabled(), robustCheck.isChecked());
                })
                .setNegativeButton(R.string.cancel, null)
                .show();

        Runnable update = () -> {
            int checked = sizeGroup.getCheckedRadioButtonId();
            sizeIndex[0] = checked == R.id.picture_size_large ? 2 : checked == R.id.picture_size_medium ? 1 : 0;
            byte[] bytes = PictureUtil.compress(source, PictureUtil.MAX_SIDE[sizeIndex[0]], PictureUtil.QUALITY[sizeIndex[0]]);
            current[0] = bytes;
            preview.setImageBitmap(PictureUtil.decode(bytes));
            boolean encrypt = encryptCheck.isChecked() && encryptCheck.isEnabled();
            int chars = robustCheck.isChecked() ? PictureCodec.ROBUST_PACKET_CHARS : PictureCodec.FAST_PACKET_CHARS;
            int parts = PictureCodec.packetCount(bytes.length, encrypt, chars);
            int dataParts = PictureCodec.dataPartCount(bytes.length, encrypt, chars);
            if (parts > PictureCodec.MAX_PARTS) {
                info.setText(R.string.picture_too_large);
                info.setTextColor(Color.RED);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            } else {
                int seconds = Math.round(parts * SECONDS_PER_PICTURE_PART);
                info.setText(getString(R.string.picture_info, bytes.length / 1024f, dataParts, parts - dataParts, seconds / 60, seconds % 60));
                info.setTextColor(ContextCompat.getColor(this, R.color.tint));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            }
        };
        sizeGroup.setOnCheckedChangeListener((group, id) -> update.run());
        robustCheck.setOnCheckedChangeListener((b, c) -> update.run());
        TextView hiddenNote = view.findViewById(R.id.call_sign_hidden_note);
        encryptCheck.setOnCheckedChangeListener((b, c) -> {
            updateHiddenNote(hiddenNote, encryptCheck);
            update.run();
        });
        updateHiddenNote(hiddenNote, encryptCheck);
        update.run();
    }

    private void sendPicture(byte[] image, boolean encrypt, boolean robust) {
        if (image == null)
            return;
        if (pictureQueue != null || isTransmitBusy()) {
            Toast.makeText(this, R.string.picture_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (rigBlocksTransmit())
            return;
        List<String> packets;
        try {
            int id = new java.security.SecureRandom().nextInt(0x10000);
            // The hidden call sign rides inside the encrypted picture
            byte[] content = encrypt && hidingCallSign() ? HiddenCall.wrap(callSign.trim(), image) : image;
            packets = PictureCodec.buildPackets(content, encrypt ? password : null,
                    robust ? PictureCodec.ROBUST_PACKET_CHARS : PictureCodec.FAST_PACKET_CHARS, id);
        } catch (Exception e) {
            Toast.makeText(this, R.string.picture_too_large, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "s" + Long.toString(System.currentTimeMillis(), 36);
        try {
            PictureUtil.save(this, name + ".webp", image);
            PictureUtil.saveText(this, name + ".pkt", android.text.TextUtils.join("\n", packets));
        } catch (Exception e) {
            Toast.makeText(this, R.string.picture_load_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String entry = addMessage(callSign.trim(), getString(R.string.transmitted),
                pictureBody(name, encrypt, getString(R.string.picture_sending, 0, packets.size())));
        startPictureQueue(packets, entry, name);
    }

    private void startPictureQueue(List<String> packets, String entry, String name) {
        pictureQueue = packets;
        pictureQueuePos = 0;
        pictureSendingEntry = entry;
        pictureSendingName = name;
        handler.post(pictureSender);
    }

    /** Sends the queued picture parts one after another, waiting for each to finish. */
    private final Runnable pictureSender = new Runnable() {
        @Override
        public void run() {
            List<String> queue = pictureQueue;
            if (queue == null)
                return;
            if (isTransmitBusy()) {
                pictureLastBusy = System.currentTimeMillis();
                handler.postDelayed(this, 100);
                return;
            }
            long gap = isRadioConnected() ? PICTURE_PART_GAP_RADIO_MS : PICTURE_PART_GAP_SPEAKER_MS;
            long wait = pictureLastBusy + gap - System.currentTimeMillis();
            if (pictureQueuePos > 0 && wait > 0) {
                handler.postDelayed(this, wait);
                return;
            }
            boolean encrypted = PictureCodec.isEncrypted(queue.get(0));
            if (pictureQueuePos >= queue.size()) {
                String entry = replaceBody(pictureSendingEntry,
                        pictureBody(pictureSendingName, encrypted, getString(R.string.picture_sent, queue.size())));
                // A station acknowledging the whole picture uses the picture's ID
                PictureCodec.Packet first = PictureCodec.parse(queue.get(0));
                if (first != null)
                    sentMessagesById.put(acknowledgmentId(pictureAckSource(first.id)), entry);
                pictureQueue = null;
                startListening();
                return;
            }
            if (rigBlocksTransmit()) {
                pictureQueue = null;
                startListening();
                return;
            }
            String part = queue.get(pictureQueuePos++);
            sendOnAir(part, PictureCodec.isEncrypted(part) && hidingCallSign());
            pictureLastBusy = System.currentTimeMillis();
            pictureSendingEntry = replaceBody(pictureSendingEntry,
                    pictureBody(pictureSendingName, encrypted, getString(R.string.picture_sending, pictureQueuePos, queue.size())));
            handler.postDelayed(this, 1000);
        }
    };

    private void handlePicturePart(String sender, PictureCodec.Packet part, String text) {
        String key = sender + "/" + part.id;
        IncomingPicture in = incomingPictures.get(key);
        if (in != null && in.done && in.assembly.matches(part)) {
            // Spare parts still arriving after the picture is complete: the sender is still transmitting
            in.lastIndex = Math.max(in.lastIndex, part.index);
            scheduleCompleteAck(in);
            return;
        }
        if (in == null || !in.assembly.matches(part)) {
            in = new IncomingPicture();
            in.assembly = new PictureCodec.Assembly(part);
            autoSaveContact(sender);
            in.entry = addMessage(sender, getString(R.string.received),
                    getString(R.string.picture_receiving, 0, part.dataParts));
            incomingPictures.put(key, in);
        }
        in.lastIndex = Math.max(in.lastIndex, part.index);
        if (!in.assembly.add(part))
            return;
        if (!in.assembly.complete()) {
            in.entry = replaceBody(in.entry, getString(R.string.picture_receiving, in.assembly.received(), in.assembly.dataParts));
            return;
        }
        finishIncomingPicture(in);
    }

    private void finishIncomingPicture(IncomingPicture in) {
        byte[] image = in.assembly.assemble(password);
        String hiddenCall = image == null || !in.assembly.encrypted ? null : HiddenCall.callIn(image);
        if (hiddenCall != null) {
            image = HiddenCall.strip(image, hiddenCall);
            autoSaveContact(hiddenCall);
            in.entry = replaceTitle(in.entry, hiddenCall, getString(R.string.received_hidden));
        } else if (image != null && in.assembly.encrypted) {
            String first = in.entry.substring(0, Math.max(0, in.entry.indexOf('\n')));
            in.entry = replaceTitle(in.entry, parseCallAndNote(first)[0], getString(R.string.received_decrypted));
        }
        if (image == null || PictureUtil.decode(image) == null) {
            if (in.assembly.encrypted) {
                in.locked = true;
                in.entry = replaceBody(in.entry, getString(R.string.picture_locked));
            } else {
                in.done = true;
                in.entry = replaceBody(in.entry, getString(R.string.picture_corrupt));
            }
            return;
        }
        String name = "r" + Long.toString(System.currentTimeMillis(), 36);
        try {
            PictureUtil.save(this, name + ".webp", image);
        } catch (Exception e) {
            return;
        }
        in.done = true;
        in.locked = false;
        in.entry = replaceBody(in.entry, pictureBody(name, in.assembly.encrypted, null));
        // Pictures are never addressed to a call sign, so only "All messages" acknowledges them
        if (ackMode == ACK_ALL && hasRealCallSign()) {
            in.wantAck = true;
            scheduleCompleteAck(in);
        }
    }

    /** The text both sides derive a picture's acknowledgment ID from: "~P" plus its 4-digit ID. */
    private static String pictureAckSource(int id) {
        return String.format(Locale.US, "%s%04x", PictureCodec.PREFIX, id & 0xffff);
    }

    /**
     * Acknowledges a complete picture once the sender should have finished transmitting its remaining
     * (spare) parts, so the ACK is not sent while the sender cannot listen. Later parts push it back.
     */
    private void scheduleCompleteAck(IncomingPicture in) {
        if (!in.wantAck)
            return;
        if (in.ackTask != null)
            handler.removeCallbacks(in.ackTask);
        int remaining = Math.max(0, in.assembly.total - 1 - in.lastIndex);
        long delay = (long) (remaining * SECONDS_PER_PICTURE_PART * 1000) + 1500 + new java.util.Random().nextInt(4500);
        String reply = "ACK " + acknowledgmentId(pictureAckSource(in.assembly.id));
        in.ackTask = () -> {
            in.wantAck = false;
            in.ackTask = null;
            sendAcknowledgment(reply, 3);
        };
        handler.postDelayed(in.ackTask, delay);
    }

    private static String pictureBody(String name, boolean encrypted, String status) {
        StringBuilder sb = new StringBuilder(PICTURE_TOKEN).append(name).append(']');
        if (encrypted)
            sb.append(" 🔒");
        if (status != null)
            sb.append('\n').append(status);
        return sb.toString();
    }

    /** The stored picture name inside a list entry, or null. */
    private static String pictureNameIn(String item) {
        if (item == null)
            return null;
        int start = item.indexOf(PICTURE_TOKEN);
        if (start < 0)
            return null;
        int end = item.indexOf(']', start);
        return end < 0 ? null : item.substring(start + PICTURE_TOKEN.length(), end);
    }

    /** Replaces the message text below the title line of a list entry; returns the new entry. */
    /**
     * Decrypts received messages that arrived while the password was missing or wrong, and opens locked
     * pictures, now that a new password is set. Messages that still do not decrypt are left as they are.
     */
    private void unlockStoredMessages() {
        if (password == null || password.isEmpty())
            return;
        String receivedTitle = " - " + getString(R.string.received) + ":";
        int unlocked = 0;
        for (String entry : new ArrayList<>(Arrays.asList(storedEntries()))) {
            int nl = entry.indexOf('\n');
            if (nl < 0 || !entry.substring(0, nl).endsWith(receivedTitle))
                continue;
            String body = entry.substring(nl + 1).trim();
            String plain = CryptoUtils.decrypt(body, password);
            if (plain == null)
                continue;
            String[] hidden = HiddenCall.unwrap(plain);
            java.util.regex.Matcher ack = ACK_PATTERN.matcher(hidden != null ? hidden[1] : plain);
            if (ack.matches()) {
                // An acknowledgment that arrived before the password: a matched one becomes the ✓ on our
                // sent message, any other one a plain line, never a message bubble
                String call = hidden != null ? hidden[0] : parseCallAndNote(entry.substring(0, nl))[0];
                int position = messages.getPosition(entry);
                messages.remove(entry);
                if (!markAcknowledged(call, ack.group(1), new Date())) {
                    String time = entry.substring(0, entry.indexOf(" - "));
                    messages.insert(getString(R.string.title_line, time, appendContactNote(call), getString(R.string.ack_other)), position);
                }
                storeSettings();
                continue;
            }
            if (hidden != null) {
                autoSaveContact(hidden[0]);
                replaceBody(replaceTitle(entry, hidden[0], getString(R.string.received_hidden)), hidden[1]);
            } else {
                String call = parseCallAndNote(entry.substring(0, nl))[0];
                replaceBody(replaceTitle(entry, call, getString(R.string.received_decrypted)), plain);
            }
            ++unlocked;
        }
        for (IncomingPicture in : new ArrayList<>(incomingPictures.values())) {
            if (!in.locked)
                continue;
            finishIncomingPicture(in);
            if (!in.locked)
                ++unlocked;
        }
        if (unlocked > 0)
            Toast.makeText(this, getResources().getQuantityString(R.plurals.messages_unlocked, unlocked, unlocked),
                    Toast.LENGTH_SHORT).show();
    }

    private String[] storedEntries() {
        String[] all = new String[messages.getCount()];
        for (int i = 0; i < all.length; ++i)
            all[i] = messages.getItem(i);
        return all;
    }

    /** Replaces the call sign and status in an entry's first line, keeping its time and body. */
    private String replaceTitle(String entry, String call, String info) {
        int nl = entry.indexOf('\n');
        String first = nl < 0 ? entry : entry.substring(0, nl);
        int dash = first.indexOf(" - ");
        String time = dash < 0 ? currentTime() : first.substring(0, dash);
        String updated = getString(R.string.title_line, time, appendContactNote(call), info) + ":" + (nl < 0 ? "" : entry.substring(nl));
        int position = messages.getPosition(entry);
        if (position >= 0) {
            messages.remove(entry);
            messages.insert(updated, position);
            storeSettings();
        }
        return updated;
    }

    private String replaceBody(String entry, String body) {
        int nl = entry.indexOf('\n');
        String updated = (nl < 0 ? entry : entry.substring(0, nl)) + "\n" + body;
        int position = messages.getPosition(entry);
        if (position >= 0) {
            messages.remove(entry);
            messages.insert(updated, position);
            storeSettings();
        }
        return updated;
    }

    private Bitmap pictureThumbnail(String name) {
        Bitmap cached = pictureThumbs.get(name);
        if (cached != null)
            return cached;
        Bitmap bitmap = PictureUtil.load(this, name + ".webp");
        if (bitmap == null)
            return null;
        int width = Math.round(200 * getResources().getDisplayMetrics().density);
        int height = Math.max(1, width * bitmap.getHeight() / bitmap.getWidth());
        Bitmap thumb = Bitmap.createScaledBitmap(bitmap, width, height, true);
        pictureThumbs.put(name, thumb);
        return thumb;
    }

    private void prunePictures() {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (int i = 0; i < messages.getCount(); ++i) {
            String name = pictureNameIn(messages.getItem(i));
            if (name != null)
                keep.add(name);
        }
        PictureUtil.prune(this, keep);
    }

    /** Handles a tap on a picture-related list entry; returns false for other entries. */
    /**
     * Long-press on a bubble: asks before transmitting its text again, encrypted by default when a password
     * is set, so a message that arrived encrypted is never repeated in the clear by accident.
     */
    private void confirmResend(String item) {
        String[] mesg = item.split("\n", 2);
        if (mesg.length < 2)
            return;
        // Leave out the "✓ Acknowledged by" lines added under sent messages
        StringBuilder sb = new StringBuilder();
        for (String line : mesg[1].split("\n", -1))
            if (!line.startsWith("✓ "))
                sb.append(sb.length() > 0 ? "\n" : "").append(line);
        String body = sb.toString().trim();
        if (body.isEmpty())
            return;
        if (body.startsWith("📷") || body.startsWith("🔒")) {
            Toast.makeText(this, R.string.resend_picture, Toast.LENGTH_LONG).show();
            return;
        }
        if (looksEncrypted(body)) {
            Toast.makeText(this, R.string.resend_unreadable, Toast.LENGTH_LONG).show();
            return;
        }
        boolean canEncrypt = password != null && !password.isEmpty();
        CheckBox encrypt = new CheckBox(this);
        encrypt.setText(R.string.encrypt_message);
        encrypt.setChecked(canEncrypt);
        encrypt.setEnabled(canEncrypt);
        FrameLayout box = new FrameLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(encrypt);
        new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.resend_title)
                // Without a password it can only go out in the clear, so say so plainly
                .setMessage(canEncrypt ? body : body + "\n\n" + getString(R.string.resend_unencrypted_warning))
                .setView(box)
                .setPositiveButton(R.string.resend, (d, w) -> transmitMessage(body, encrypt.isChecked()))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** True for text that is still encryption output (a message this phone could not decrypt). */
    private static boolean looksEncrypted(String text) {
        if (!text.matches("[A-Za-z0-9+/=\\n]{36,}"))
            return false;
        try {
            // AES-GCM output: 12-byte nonce plus 16-byte tag at least
            return android.util.Base64.decode(text, android.util.Base64.DEFAULT).length >= 28;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean handlePictureTap(String item, String content) {
        String name = pictureNameIn(item);
        if (pictureQueue != null && item.equals(pictureSendingEntry)) {
            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                    .setMessage(R.string.picture_stop_prompt)
                    .setPositiveButton(R.string.picture_stop, (d, w) -> {
                        String sending = pictureSendingEntry;
                        List<String> queue = pictureQueue;
                        pictureQueue = null;
                        if (sending != null && queue != null)
                            replaceBody(sending, pictureBody(pictureSendingName, PictureCodec.isEncrypted(queue.get(0)),
                                    getString(R.string.picture_stopped, pictureQueuePos, queue.size())));
                        startListening();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        }
        if (name != null) {
            showPictureViewer(item, name);
            return true;
        }
        for (IncomingPicture in : incomingPictures.values()) {
            if (!item.equals(in.entry))
                continue;
            if (in.locked) {
                finishIncomingPicture(in);
                if (in.locked)
                    Toast.makeText(this, R.string.picture_still_locked, Toast.LENGTH_LONG).show();
            } else if (!in.done) {
                Toast.makeText(this, getString(R.string.picture_missing, in.assembly.needed()), Toast.LENGTH_LONG).show();
            }
            return true;
        }
        if (content.startsWith("📷") || content.startsWith("🔒")) {
            Toast.makeText(this, R.string.picture_incomplete_old, Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    private void showPictureViewer(String item, String name) {
        Bitmap bitmap = PictureUtil.load(this, name + ".webp");
        if (bitmap == null) {
            Toast.makeText(this, R.string.picture_missing_file, Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int width = getResources().getDisplayMetrics().widthPixels;
        image.setImageBitmap(Bitmap.createScaledBitmap(bitmap, width, Math.max(1, width * bitmap.getHeight() / bitmap.getWidth()), true));
        boolean sent = item.contains(getString(R.string.transmitted));
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setView(image)
                .setNegativeButton(R.string.close, null);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            builder.setPositiveButton(R.string.picture_save, (d, w) -> Toast.makeText(this,
                    PictureUtil.saveToGallery(this, name + ".webp") ? R.string.picture_saved : R.string.picture_save_failed,
                    Toast.LENGTH_SHORT).show());
        String packets = sent ? PictureUtil.readText(this, name + ".pkt") : null;
        if (packets != null)
            builder.setNeutralButton(R.string.picture_send_again, (d, w) -> {
                if (pictureQueue != null || isTransmitBusy()) {
                    Toast.makeText(this, R.string.picture_busy, Toast.LENGTH_SHORT).show();
                    return;
                }
                startPictureQueue(Arrays.asList(packets.split("\n")), item, name);
            });
        builder.show();
    }

    // ========== Acknowledgments ==========

    /** Short message ID shared by sender and receiver: the low 16 bits of the CRC-32 of the sent text. */
    private static String acknowledgmentId(String transmittedText) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(transmittedText.getBytes(StandardCharsets.UTF_8));
        return String.format(Locale.US, "%04x", crc.getValue() & 0xffff);
    }

    /** If text is an "ACK xxxx" reply, marks the matching sent message and returns true. */
    private boolean handleAcknowledgment(String sender, String text) {
        // An acknowledgment from a station hiding its call sign is encrypted, with the call sign inside
        String[] hidden = HiddenCall.unwrap(CryptoUtils.decrypt(text, password));
        if (hidden != null) {
            sender = hidden[0];
            text = hidden[1];
        }
        java.util.regex.Matcher m = ACK_PATTERN.matcher(text);
        if (!m.matches())
            return false;
        if (!markAcknowledged(sender, m.group(1), new Date()))
            addLine(sender, getString(R.string.ack_other));
        return true;
    }

    /** Adds "Acknowledged by" to the sent message with this ID; false if it is not (or no longer) in the list. */
    private boolean markAcknowledged(String sender, String id, Date when) {
        String entry = sentMessagesById.get(id);
        int position = entry == null ? -1 : messages.getPosition(entry);
        if (position < 0)
            return false;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(when);
        String updated = entry + "\n" + getString(R.string.ack_received, sender, time);
        messages.remove(entry);
        messages.insert(updated, position);
        sentMessagesById.put(id, updated);
        storeSettings();
        return true;
    }

    /** Sends an "ACK xxxx" reply after a short random pause, if the acknowledgment setting asks for it. */
    private void maybeAcknowledge(String received) {
        if (ackMode == ACK_OFF || received.isEmpty() || !hasRealCallSign())
            return;
        if (ackMode == ACK_ADDRESSED) {
            String text = received;
            String decrypted = CryptoUtils.decrypt(received, password);
            if (decrypted != null)
                text = decrypted;
            String[] hidden = HiddenCall.unwrap(text);
            if (hidden != null)
                text = hidden[1];
            if (!text.startsWith("@" + callSign.trim() + " "))
                return;
        }
        scheduleAcknowledgment(received);
    }

    /** Sends "ACK <id of idText>" after a short random pause. */
    private void scheduleAcknowledgment(String idText) {
        String reply = "ACK " + acknowledgmentId(idText);
        // Give the sender time to switch back to receive; spread replies more when everyone may answer
        int maxDelay = ackMode == ACK_ALL ? 6000 : 3500;
        long delay = 1500 + new java.util.Random().nextInt(maxDelay - 1500);
        handler.postDelayed(() -> sendAcknowledgment(reply, 3), delay);
    }

    private void sendAcknowledgment(String reply, int attemptsLeft) {
        if (isTransmitBusy() || pictureQueue != null) {
            if (attemptsLeft > 1)
                handler.postDelayed(() -> sendAcknowledgment(reply, attemptsLeft - 1), 1500);
            return;
        }
        // While hiding the call sign, acknowledgments are encrypted too so the call sign can travel inside
        transmitMessage(reply, hidingCallSign(), true);
    }

    private boolean hasRealCallSign() {
        String call = callSign == null ? "" : callSign.trim();
        return !call.isEmpty() && !call.equals("ANONYMOUS");
    }

    private void setAckMode(int mode) {
        if (mode == ackMode) {
            updateAckMenu();
            return;
        }
        if (mode == ACK_OFF) {
            ackMode = ACK_OFF;
            updateAckMenu();
            return;
        }
        if (!hasRealCallSign()) {
            Toast.makeText(this, R.string.ack_needs_call_sign, Toast.LENGTH_LONG).show();
            updateAckMenu();
            return;
        }
        new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.acknowledgments)
                .setMessage(mode == ACK_ALL ? R.string.ack_enable_all_prompt : R.string.ack_enable_addressed_prompt)
                .setPositiveButton(R.string.enable, (dialog, which) -> {
                    ackMode = mode;
                    updateAckMenu();
                })
                .setNegativeButton(R.string.cancel, (dialog, which) -> updateAckMenu())
                .show();
    }

    private void updateAckMenu() {
        if (menu == null)
            return;
        int id = ackMode == ACK_ALL ? R.id.action_ack_all : ackMode == ACK_ADDRESSED ? R.id.action_ack_addressed : R.id.action_ack_off;
        menu.findItem(id).setChecked(true);
    }

    private void transmitMessage(String message, boolean encrypt) {
        transmitMessage(message, encrypt, false);
    }

    private void transmitMessage(String message, boolean encrypt, boolean isAcknowledgment) {
        if (isRadioConnected() && radioLink.isTransmitting()) {
            Toast.makeText(this, R.string.radio_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        if (rigBlocksTransmit())
            return;
        if (encrypt && (password == null || password.isEmpty())) {
            Toast.makeText(this, R.string.encryption_disabled_no_password, Toast.LENGTH_LONG).show();
            return;
        }

        String finalMessage = message;
        boolean hidden = encrypt && message.length() > 0 && hidingCallSign();
        if (encrypt) {
            String encrypted = CryptoUtils.encrypt(hidden ? HiddenCall.wrap(callSign.trim(), message) : message, password);
            if (encrypted == null) {
                Toast.makeText(this, R.string.encryption_failed, Toast.LENGTH_SHORT).show();
                return;
            }
            if (encrypted.getBytes(StandardCharsets.UTF_8).length > 170) {
                Toast.makeText(this, R.string.encrypted_message_too_long, Toast.LENGTH_SHORT).show();
                return;
            }
            finalMessage = encrypted;
        }

        boolean wasBeaconRunning = beaconRunning;
        if (wasBeaconRunning) {
            if (beaconRunnable != null) {
                beaconHandler.removeCallbacks(beaconRunnable);
            }
        }

        if (isAcknowledgment) {
            addLine(callSign.trim(), getString(R.string.ack_sent));
        } else if (message.length() == 0) {
            addLine(callSign.trim(), getString(R.string.sent_ping));
        } else {
            String displayMessage = encrypt ? message : finalMessage;
            String entry = addMessage(callSign.trim(), getString(encrypt ? R.string.transmitted_encrypted : R.string.transmitted), displayMessage);
            // The receiver computes the same ID from what it receives (the ciphertext when encrypted)
            sentMessagesById.put(acknowledgmentId(finalMessage.trim()), entry);
        }

        sendOnAir(finalMessage, hidden);

        if (wasBeaconRunning) {
            beaconRunnable = new Runnable() {
                @Override
                public void run() {
                    if (beaconRunning) {
                        transmitMessage("", false);
                        beaconHandler.postDelayed(this, BEACON_INTERVAL);
                    }
                }
            };
            beaconHandler.postDelayed(beaconRunnable, BEACON_INTERVAL);
        }
    }

    /** Transmits text as one Rattlegram message (speaker or Bluetooth radio), without touching the message list. */
    private void sendOnAir(String text, boolean hideCall) {
        stopListening();
        byte[] mesg = Arrays.copyOf(text.getBytes(StandardCharsets.UTF_8), payload.length);
        byte[] call = hideCall ? Arrays.copyOf(HiddenCall.PLACEHOLDER.getBytes(StandardCharsets.US_ASCII), HiddenCall.PLACEHOLDER.length() + 1) : callTerm();
        configureEncoder(mesg, call, carrierFrequency, noiseSymbols, fancyHeader);
        if (isRadioConnected()) {
            transmitViaRadio(0);
        } else {
            framesWritten = 0;
            for (int i = 0; i < 5; ++i) {
                produceEncoder(outputBuffer, outputChannel);
                writeOutput();
            }
            startPlayback();
        }
    }

    private boolean isTransmitBusy() {
        return isRadioConnected() ? radioLink.isTransmitting()
                : audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                || rig != null && rig.ptt().isKeying();
    }

    private void repeatMessage() {
        Message message = new Message(stagedCall, payload);
        Iterator<Message> iterator = repeatedMessages.iterator();
        while (iterator.hasNext()) {
            Message repeated = iterator.next();
            if (message.time - repeated.time < repeaterDebounce) {
                if (Arrays.equals(repeated.call, message.call) && Arrays.equals(repeated.data, message.data)) {
                    setStatus(getString(R.string.ignoring), true);
                    return;
                }
            } else {
                iterator.remove();
            }
        }
        if (repeaterDebounce > 0)
            repeatedMessages.add(message);
        if (rigBlocksTransmit())
            return;
        stopListening();
        String sender = parseCallSign(stagedCall);
        autoSaveContact(sender);
        addMessage(sender, getString(R.string.repeated), new String(payload).trim());
        configureEncoder(payload, stagedCall, carrierFrequency, noiseSymbols, fancyHeader);
        if (isRadioConnected()) {
            transmitViaRadio(1000L * repeaterDelay);
            return;
        }
        framesWritten = 0;
        for (int i = 0; i < 5; ++i) {
            produceEncoder(outputBuffer, outputChannel);
            writeOutput();
        }
        handler.postDelayed(this::startPlayback, 1000L * repeaterDelay);
    }

    private void setInputType(ViewGroup np, int it) {
        int count = np.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = np.getChildAt(i);
            if (child instanceof ViewGroup) {
                setInputType((ViewGroup) child, it);
            } else if (child instanceof EditText) {
                EditText et = (EditText) child;
                et.setInputType(it);
                break;
            }
        }
    }

    private String[] carrierValues(int minCarrierFrequency, int maxCarrierFrequency) {
        int count = (maxCarrierFrequency - minCarrierFrequency) / 50 + 1;
        String[] values = new String[count];
        for (int i = 0; i < count; ++i)
            values[i] = String.format(Locale.US, "%d", i * 50 + minCarrierFrequency);
        return values;
    }

    private void setCarrierFrequency() {
        View view = getLayoutInflater().inflate(R.layout.carrier_frequency, null);
        NumberPicker picker = view.findViewById(R.id.carrier);
        int bandWidth = 1600;
        int maxCarrierFrequency = ultrasonicEnabled ? (outputRate - bandWidth) / 2 : 3000;
        int minCarrierFrequency = outputChannel == 4 ? -maxCarrierFrequency : 1000;
        if (carrierFrequency < minCarrierFrequency || carrierFrequency > maxCarrierFrequency)
            carrierFrequency = 1500;
        picker.setMinValue(0);
        picker.setDisplayedValues(null);
        picker.setMaxValue((maxCarrierFrequency - minCarrierFrequency) / 50);
        picker.setValue((carrierFrequency - minCarrierFrequency) / 50);
        picker.setDisplayedValues(carrierValues(minCarrierFrequency, maxCarrierFrequency));
        setInputType(picker, InputType.TYPE_CLASS_NUMBER);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.carrier_frequency);
        builder.setView(view);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> carrierFrequency = picker.getValue() * 50 + minCarrierFrequency);
        builder.show();
    }

    private void editCallSign() {
        View view = getLayoutInflater().inflate(R.layout.call_sign, null);
        EditText edit = view.findViewById(R.id.call);
        edit.setText(callSign);
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.call_sign);
        builder.setView(view);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.okay, (dialogInterface, i) -> {
            callSign = edit.getText().toString();
            updateCallSignMenuItem();
        });
        builder.show();
    }

    private void showTextPage(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setNeutralButton(R.string.close, null);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.show();
    }

    // ================================================================
    // ========== Password management ==================================
    // ================================================================

    // Password field of the open Change Password dialog, filled in by the QR scanner
    private EditText passwordScanTarget;

    private final ActivityResultLauncher<ScanOptions> passwordScanner = registerForActivityResult(new ScanContract(), result -> {
        String contents = result.getContents();
        if (contents == null || passwordScanTarget == null)
            return;
        passwordScanTarget.setText(contents);
        passwordScanTarget.setSelection(contents.length());
        Toast.makeText(this, R.string.password_scanned, Toast.LENGTH_LONG).show();
    });

    /** Shows the password as a QR code so another phone can scan it instead of typing it. */
    private void showPasswordQr(String text) {
        Bitmap qr;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 2);
            qr = new BarcodeEncoder().encodeBitmap(text, BarcodeFormat.QR_CODE, 720, 720, hints);
        } catch (Exception e) {
            Toast.makeText(this, R.string.password_qr_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        ImageView image = new ImageView(this);
        image.setImageBitmap(qr);
        image.setAdjustViewBounds(true);
        image.setPadding(32, 32, 32, 0);
        image.setBackgroundColor(Color.WHITE);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.password_qr_title)
                .setMessage(R.string.password_qr_warning)
                .setView(image)
                .setPositiveButton(R.string.close, null)
                .create();
        // Keep the password out of screenshots and the recent-apps preview
        if (dialog.getWindow() != null)
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }

    private void showPasswordDialog() {
        View view = getLayoutInflater().inflate(R.layout.password_dialog, null);
        EditText editText = view.findViewById(R.id.password_edit);
        TextView errorText = view.findViewById(R.id.password_error);
        Button generateButton = view.findViewById(R.id.generate_password_button);
        Button cancelButton = view.findViewById(R.id.button_cancel);
        Button okButton = view.findViewById(R.id.button_okay);
        CheckBox rememberCheck = view.findViewById(R.id.remember_password);
        rememberCheck.setChecked(PasswordStore.isRemembered(this));
        CheckBox hideCheck = view.findViewById(R.id.hide_call_sign);
        hideCheck.setChecked(hideCallSign);

        editText.setText(password);
        editText.setSelection(password.length());

        generateButton.setOnClickListener(v -> {
            byte[] randomBytes = new byte[32];
            new java.security.SecureRandom().nextBytes(randomBytes);
            String hex = bytesToHex(randomBytes);
            editText.setText(hex);
            editText.setSelection(hex.length());
            errorText.setVisibility(View.GONE);
        });

        view.findViewById(R.id.password_show_qr).setOnClickListener(v -> {
            String text = editText.getText().toString();
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < 8 || bytes > 256) {
                errorText.setText(R.string.password_length_error);
                errorText.setVisibility(View.VISIBLE);
                return;
            }
            errorText.setVisibility(View.GONE);
            showPasswordQr(text);
        });

        view.findViewById(R.id.password_scan_qr).setOnClickListener(v -> {
            passwordScanTarget = editText;
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt(getString(R.string.password_scan_prompt));
            options.setBeepEnabled(false);
            options.setOrientationLocked(false);
            passwordScanner.launch(options);
        });

        view.findViewById(R.id.password_paste).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence pasted = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(this) : null;
            if (pasted == null || pasted.toString().trim().isEmpty()) {
                Toast.makeText(this, R.string.password_clipboard_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            // Camera apps sometimes add a trailing newline to scanned text
            String text = pasted.toString().trim();
            editText.setText(text);
            editText.setSelection(text.length());
            errorText.setVisibility(View.GONE);
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
        builder.setTitle(R.string.password_dialog_title);
        builder.setView(view);
        builder.setCancelable(true);

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> passwordScanTarget = null);

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        okButton.setOnClickListener(v -> {
            String newPassword = editText.getText().toString();

            if (newPassword.isEmpty()) {
                password = "";
                PasswordStore.clear(this);
                setHideCallSign(hideCheck.isChecked());
                errorText.setVisibility(View.GONE);
                Toast.makeText(this, R.string.password_updated, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                return;
            }

            byte[] bytes = newPassword.getBytes(StandardCharsets.UTF_8);
            if (bytes.length < 8 || bytes.length > 256) {
                errorText.setText(R.string.password_length_error);
                errorText.setVisibility(View.VISIBLE);
                return;
            }

            password = newPassword;
            unlockStoredMessages();
            setHideCallSign(hideCheck.isChecked());
            if (!rememberCheck.isChecked()) {
                PasswordStore.clear(this);
                Toast.makeText(this, R.string.password_updated, Toast.LENGTH_SHORT).show();
            } else if (PasswordStore.save(this, newPassword)) {
                Toast.makeText(this, R.string.password_remembered, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.password_remember_failed, Toast.LENGTH_LONG).show();
            }
            dialog.dismiss();
        });

        dialog.show();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ================================================================
    // ========== Contact management ===================================
    // ================================================================

    private void showContactsDialog() {
        View view = getLayoutInflater().inflate(R.layout.contact, null);
        ListView listView = view.findViewById(R.id.contact_list);
        LinearLayout editPanel = view.findViewById(R.id.contact_edit_panel);
        EditText editCall = view.findViewById(R.id.contact_call_sign);
        EditText editNote = view.findViewById(R.id.contact_note);
        Button okBtn = view.findViewById(R.id.contact_edit_ok);
        Button cancelBtn = view.findViewById(R.id.contact_edit_cancel);

        final ContactAdapter adapter = new ContactAdapter(this);
        adapter.addAll(contacts);
        listView.setAdapter(adapter);

        // Current edit index: -1 means new contact
        final int[] editingIndex = {-1};

        final Runnable hideEdit = () -> {
            editPanel.setVisibility(View.GONE);
            editCall.setText("");
            editNote.setText("");
            editingIndex[0] = -1;
        };

        okBtn.setOnClickListener(v -> {
            String cs = editCall.getText().toString().trim();
            String note = editNote.getText().toString().trim();
            if (isBlankCallSign(cs)) {
                Toast.makeText(this, R.string.contact_call_sign_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            if (editingIndex[0] >= 0) {
                Contact c = contacts.get(editingIndex[0]);
                c.callSign = cs;
                c.note = note;
            } else {
                if (contacts.size() >= MAX_CONTACTS) {
                    Toast.makeText(this, R.string.contact_limit_reached, Toast.LENGTH_SHORT).show();
                    return;
                }
                contacts.add(new Contact(cs, note));
            }
            saveContacts();
            adapter.clear();
            adapter.addAll(contacts);
            adapter.notifyDataSetChanged();
            hideEdit.run();
        });

        cancelBtn.setOnClickListener(v -> hideEdit.run());

        // Long-press item: edit / delete
        listView.setOnItemLongClickListener((parent, v, position, id) -> {
            final int pos = position;
            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                    .setItems(new String[]{
                            getString(R.string.contact_edit),
                            getString(R.string.delete)
                    }, (dialog, which) -> {
                        if (which == 0) {
                            Contact c = contacts.get(pos);
                            editCall.setText(c.callSign);
                            editNote.setText(c.note);
                            editingIndex[0] = pos;
                            editPanel.setVisibility(View.VISIBLE);
                        } else {
                            new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                                    .setTitle(R.string.delete)
                                    .setMessage(R.string.contact_delete_prompt)
                                    .setPositiveButton(R.string.delete, (d, w) -> {
                                        contacts.remove(pos);
                                        saveContacts();
                                        adapter.clear();
                                        adapter.addAll(contacts);
                                        adapter.notifyDataSetChanged();
                                    })
                                    .setNegativeButton(R.string.cancel, null)
                                    .show();
                        }
                    })
                    .show();
            return true;
        });

        // Long-press empty area: add new
        final GestureDetector detector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(MotionEvent e) {
                        int pos = listView.pointToPosition((int) e.getX(), (int) e.getY());
                        if (pos == ListView.INVALID_POSITION) {
                            if (contacts.size() >= MAX_CONTACTS) {
                                Toast.makeText(MainActivity.this,
                                        R.string.contact_limit_reached,
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            editCall.setText("");
                            editNote.setText("");
                            editingIndex[0] = -1;
                            editPanel.setVisibility(View.VISIBLE);
                        }
                    }
                });

        listView.setOnTouchListener((v, event) -> {
            detector.onTouchEvent(event);
            return false;
        });

        // Click item: close dialog, open compose window prefilled with "@callSign "
        final AlertDialog contactDialog = new AlertDialog.Builder(this, R.style.Theme_AlertDialog)
                .setTitle(R.string.contacts)
                .setView(view)
                .setNegativeButton(R.string.close, null)
                .show();

        listView.setOnItemClickListener((parent, v, position, id) -> {
            Contact c = contacts.get(position);
            contactDialog.dismiss();
            composeMessage("@" + c.callSign + " ");
        });
    }

    @Override
    protected void onResume() {
        activityResumed = true;
        startListening();
        if (rig != null)
            rig.setPolling(rig.isConnected());
        super.onResume();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        stopListening();
        storeSettings();
        if (rig != null)
            rig.setPolling(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // isFinishing() is false for a screen rotation, so only a real close wipes the messages
        if (isFinishing() && deleteMessagesOnClose)
            clearStoredMessages();
        if (radioLink != null)
            radioLink.disconnect();
        // Unkey and close the CAT radio before anything else goes away
        if (rig != null)
            rig.shutdownAndWait(1000);
        stopBeacon();
        if (audioTrack != null) audioTrack.stop();
        destroyEncoder();
        destroyDecoder();
        super.onDestroy();
    }
}