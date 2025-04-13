package com.example.mobileapp;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.widget.Button;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SdpObserver;
import org.webrtc.DataChannel;
import java.util.ArrayList;
import java.util.List;
import io.socket.client.Socket;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.media.AudioManager;
import android.content.Context;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WebRTC_Audio";
    private PeerConnectionFactory peerConnectionFactory; //фабрика для создания объектов WebRTC
    private PeerConnection peerConnection; //соединение между двумя участниками
    private AudioTrack remoteAudioTrack; //аудиотрек, получаемый от удаленного участника
    private Socket mSocket; //клиент Socket.IO для связи с сервером
    private TextView connectionStatus; // TextView для отображения состояния подключения
    private TextView uid; // TextView для отображения UID пропуска
    private Handler mainHandler; //нужен для обновлений UI из других потоков
    private boolean isAudioEnabled = true; // Флаг состояния аудиовыхода
    private boolean isSocketConnected = false;
    private NfcAdapter nfcAdapter;
    private AudioTrack localAudioTrack;
    private DataChannel dataChannel;
    private WebSocket webSocket;
    private OkHttpClient client;
    private String NumberDevice;
    private boolean InNumberDevice = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (nfcAdapter == null) {
            Log.e("NFC", "NFC не поддерживается на этом устройстве.");
            return;
        }

        if (!nfcAdapter.isEnabled()) {
            Log.e("NFC", "NFC выключен. Пожалуйста, включите NFC в настройках.");
        }

        // Кнопка для запуска WebRTC
        Button startCallButton = findViewById(R.id.startCallButton);
        startCallButton.setOnClickListener(v -> {
            connectToServer();      // Сначала подключаемся к Socket.IO
        });

        //Кнопка аудиовыхода
        Button audioOutputButton = findViewById(R.id.AudioOutputButton);
        audioOutputButton.setOnClickListener(v -> toggleAudioOutput());

        connectionStatus = findViewById(R.id.connectionStatus);
        uid = findViewById(R.id.uid);
        mainHandler = new Handler(Looper.getMainLooper());

        //Кнопка разрыва соединения
        Button stopCallButton = findViewById(R.id.stopCallButton);
        stopCallButton.setOnClickListener(v -> {
            stopCall(); // вызываем готовую функцию
            connectionStatus.setText("Call Stopped");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            // Указываем флаг FLAG_IMMUTABLE для PendingIntent
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE // Для совместимости с Android 12+
            );

            IntentFilter tagFilter = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, new IntentFilter[]{tagFilter}, null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            nfcAdapter.disableForegroundDispatch(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d("NFC", "Intent action: " + intent.getAction());
        if (intent.getExtras() != null) {
            Log.d("NFC", "Intent extras: " + intent.getExtras().toString());
        }
        if (NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction()) ||
                NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction()) ||
                NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                NfcA nfcA = NfcA.get(tag);
                try {
                    nfcA.connect();
                    if (nfcA != null && nfcA.isConnected()) {
                        Log.d("NFC", "Successfully connected to tag");
                    } else {
                        Log.e("NFC", "Failed to connect to tag");
                    }
                    byte[] id = nfcA.getTag().getId(); // Получаем уникальный ID карты
                    String cardNumber = bytesToHex(id); // Преобразуем байты в строку

                    Log.d("NFC", "Card ID: " + cardNumber);
                    updateUID(cardNumber);

                    // Отправка номера карты через WebRTC
                    sendCardNumberThroughWebSocket(cardNumber);

                    nfcA.close();
                } catch (Exception e) {
                    Log.e("NFC", "Ошибка чтения NFC", e);
                }
            } else {
                Log.e("NFC", "Tag is null");
            }
        } else {
            Log.e("NFC", "Invalid intent action or intent is null");
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder stringBuilder = new StringBuilder();
        for (byte b : bytes) {
            stringBuilder.append(String.format("%02X", b));
        }
        return stringBuilder.toString();
    }

    //Подключение к серверу через WebSocket
    private void connectToServer() {
        if (webSocket != null) {
            Log.d("WebSocket", "Уже подключено");
            return;
        }

        client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("ws://192.168.0.118:8080") // Поменяй на свой адрес
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                runOnUiThread(() -> {
                    isSocketConnected = true;
                    connectionStatus.setText("Connected (WebSocket)");
                    Log.d("WebSocket", "Соединение открыто");
                    initializeWebRTC();
                });
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                Log.d("WebSocket", "Получено: " + text);
                if ((NumberDevice != null) && (InNumberDevice == false)) {
                    AudioMute(text);

                }
                if ((NumberDevice == null) && (InNumberDevice = true)) {
                    NumberDevice = text;
                    InNumberDevice = false;
                    Log.d("WebSocket", "Присвоен номер устройства: "+NumberDevice);
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, okhttp3.Response response) {
                Log.e("WebSocket", "Ошибка соединения", t);
                if (response != null) {
                    Log.e("WebSocket", "Ответ сервера: " + response.code() + " " + response.message());
                }
                isSocketConnected = false;
                webSocket = null;
                runOnUiThread(() -> connectionStatus.setText("Connection Error"));
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                Log.d("WebSocket", "Соединение закрывается: " + reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                Log.d("WebSocket", "Соединение закрыто: " + reason);
                runOnUiThread(() -> connectionStatus.setText("Disconnected"));
            }
        });
    }

    //Регулировщик аудиовыхода
    private void AudioMute(String text) {
        if (NumberDevice != null && text.equals("0")) { //Если включил или обратился ко всем
            isAudioEnabled = true;
            runOnUiThread(() -> {
                enableAudioOutput(true, true);
            });
        }
        if (NumberDevice != null && text.equals(NumberDevice)) { //Если обратились ко мне
            isAudioEnabled = true;
            runOnUiThread(() -> {
                enableAudioOutput(false, true);
            });
        }
        if (NumberDevice != null && !text.equals(NumberDevice) && !text.equals("-1") && !text.equals("0")) { //Если обратились к другому
            isAudioEnabled = false;
            runOnUiThread(() -> {
                enableAudioOutput(true, false);
            });
        }
        if (NumberDevice != null && text.equals("-1")) { //Если замутили всех
            isAudioEnabled = false;
            runOnUiThread(() -> {
                enableAudioOutput(false, false);
            });
        }
    }

    private void enableAudioOutput(boolean enable, boolean enable1) {
        Button audioOutputButton = findViewById(R.id.AudioOutputButton);
        runOnUiThread(() -> {
            // Всё, что меняет UI — кнопки, тексты, анимации и т.д.
            audioOutputButton.setEnabled(enable);

            // Обновляем визуальное состояние кнопки
            if (!enable1) {
                audioOutputButton.setBackgroundResource(R.drawable.rounded_button_red);  // Блокируем
            } else {
                audioOutputButton.setBackgroundResource(R.drawable.rounded_button_green);  // Разблокируем
            }

            Log.d("WebRTC_Audio", "Audio output: " + (enable ? "Enabled" : "Disabled"));
            Log.d("WebRTC_Audio", "Audio output: " + (enable1 ? "ON" : "OFF"));
        });
    }

    //Завершает соединение с сервером и отключает аудиовыход
    private void stopCall() {
        if (webSocket != null) {
            webSocket.close(1000, "Manual disconnect");
            webSocket = null;
        }

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        if (remoteAudioTrack != null) {
            remoteAudioTrack.setEnabled(false);
            remoteAudioTrack = null;
        }

        if (mSocket != null && mSocket.connected()) {
            mSocket.emit("manual_disconnect"); // Сначала сообщаем серверу
            mSocket.disconnect();              // Затем отключаемся
            mSocket.close();                   // Закрываем
            mSocket = null;
        }
        isSocketConnected = false;
        Log.d(TAG, "Call stopped, peerConnection and socket closed.");
    }
    //Меняет состояние включения аудиотрека
    private void toggleAudioOutput() {
        Button audioOutputButton = findViewById(R.id.AudioOutputButton);

        isAudioEnabled = !isAudioEnabled;

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (isAudioEnabled) {
            // Включаем вывод в наушники или динамики (например, в наушники)
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            audioManager.setSpeakerphoneOn(false);  // Используем наушники
        } else {
            // Отключаем вывод звука
            audioManager.setMode(AudioManager.MODE_NORMAL);  // Отправляем звук в телефон, но не воспроизводим
            audioManager.setSpeakerphoneOn(false);  // Отключаем динамики
        }

        int backgroundRes = isAudioEnabled ? R.drawable.rounded_button_green : R.drawable.rounded_button_red;
        audioOutputButton.setBackgroundResource(backgroundRes);

        Log.d("WebRTC_Audio", "Audio output: " + (isAudioEnabled ? "ON" : "OFF"));
    }

    //Обновление текстового поля о состоянии соединения
    private void updateConnectionStatus(String status) {
        mainHandler.post(() -> connectionStatus.setText("Status: " + status));
    }

    //Обновление текстового поля о UID пропуска
    private void updateUID(String cardNumber) {
        Log.d("NFC", "Updating UID: " + cardNumber);
        mainHandler.post(() -> {
            if (uid != null) {
                uid.setText("UID: " + cardNumber);
                Log.d("NFC", "UID updated on UI");

            } else {
                Log.e("NFC", "UID TextView is null");
            }
        });
    }
    //Отправка сигнального сообщения на сервер через Socket.IO
    private void sendSignalToServer(String type, String data) {
        if (webSocket != null) {
            try {
                JSONObject message = new JSONObject();
                message.put("type", type);
                message.put("data", data);
                webSocket.send(message.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Ошибка при отправке сигнала", e);
            }
        }
    }

    private void sendCardNumberThroughWebSocket(String cardNumber) {
        if (webSocket != null) {
            String message = cardNumber;
            webSocket.send("UID:"+message);
            InNumberDevice = true;
            Log.d("WebSocket", "UID отправлен: " + cardNumber);
        } else {
            Log.e("WebSocket", "WebSocket не подключен");
        }
    }

    private void initializeWebRTC() {
        // Шаг 1: Инициализация PeerConnectionFactory

        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory();

        // Шаг 2: Создание MediaStream и AudioTrack
        MediaConstraints audioConstraints = new MediaConstraints();
        AudioSource audioSource = peerConnectionFactory.createAudioSource(audioConstraints);
        localAudioTrack = peerConnectionFactory.createAudioTrack("LOCAL_AUDIO", audioSource);

        // Шаг 3: Настройка PeerConnection
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        PeerConnection.IceServer iceServer = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer();
        iceServers.add(iceServer);

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "Signaling state: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "ICE connection state: " + iceConnectionState);
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) { }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) { }

            @Override
            public void onIceCandidate(org.webrtc.IceCandidate iceCandidate) {
                peerConnection.addIceCandidate(iceCandidate);
                sendSignalToServer("candidate", iceCandidate.sdp);
            }

            @Override
            public void onIceCandidatesRemoved(org.webrtc.IceCandidate[] iceCandidates) { }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.d(TAG, "Stream added: " + mediaStream);
                if (!mediaStream.audioTracks.isEmpty()) {
                    remoteAudioTrack = mediaStream.audioTracks.get(0);
                    remoteAudioTrack.setEnabled(true);
                }
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "Stream removed: " + mediaStream);
            }

            @Override
            public void onRenegotiationNeeded() { }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "DataChannel created: " + dataChannel);
                // Сохраняем ссылку на dataChannel
                MainActivity.this.dataChannel = dataChannel;
            }
        });
    }
}

