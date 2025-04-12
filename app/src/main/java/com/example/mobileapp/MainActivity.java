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
import io.socket.client.IO;
import io.socket.client.Socket;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcA;
import java.nio.ByteBuffer;

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
    private NfcAdapter nfcAdapter;
    private AudioTrack localAudioTrack;
    private DataChannel dataChannel;


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
            if (peerConnection != null) {
                peerConnection.close();
                peerConnection = null;
                Log.d(TAG, "PeerConnection closed.");
                connectionStatus.setText("Call Stopped");
            }
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
            Log.d("NFC", "Условие выполнилось");
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                NfcA nfcA = NfcA.get(tag);
                try {
                    if (nfcA != null && nfcA.isConnected()) {
                        Log.d("NFC", "Successfully connected to tag");
                    } else {
                        Log.e("NFC", "Failed to connect to tag");
                    }

                    nfcA.connect();
                    byte[] id = nfcA.getTag().getId(); // Получаем уникальный ID карты
                    String cardNumber = bytesToHex(id); // Преобразуем байты в строку

                    Log.d("NFC", "Card ID: " + cardNumber);
                    updateUID(cardNumber);

                    // Отправка номера карты через WebRTC
                    sendCardNumberThroughWebRTC(cardNumber);

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

    private void sendCardNumberThroughWebRTC(String cardNumber) {
        if (dataChannel != null) {
            // Преобразуем строку в массив байт
            byte[] cardNumberBytes = cardNumber.getBytes();

            // Создаем ByteBuffer из массива байт
            ByteBuffer byteBuffer = ByteBuffer.wrap(cardNumberBytes);

            // Создаем DataChannel.Buffer с ByteBuffer
            DataChannel.Buffer buffer = new DataChannel.Buffer(byteBuffer, false);

            // Отправляем данные через DataChannel
            dataChannel.send(buffer);
            Log.d("WebRTC", "Card number sent: " + cardNumber);
        }
    }

    //Подключение к серверу через Socket.IO
    private void connectToServer() {
        try {
            mSocket = IO.socket("http://192.168.0.118:8080");
            mSocket.connect();

            mSocket.on(Socket.EVENT_CONNECT, args -> {
                runOnUiThread(() -> {
                    connectionStatus.setText("Connected");
                    Log.d("Socket.IO", "Connected to server!");
                    initializeWebRTC(); // Запускаем WebRTC только после соединения
                });
            });

            mSocket.on(Socket.EVENT_DISCONNECT, args -> {
                runOnUiThread(() -> {
                    connectionStatus.setText("Disconnected");
                    Log.d("Socket.IO", "Disconnected from server");
                });
            });

            mSocket.on(Socket.EVENT_CONNECT_ERROR, args -> {
                runOnUiThread(() -> {
                    connectionStatus.setText("Connection Error");
                    Log.e("Socket.IO", "Connection error", (Throwable) args[0]);
                });
            });

            mSocket.on("signal", args -> {
                JSONObject data = (JSONObject) args[0];
                try {
                    String type = data.getString("type");
                    String message = data.getString("data");
                    handleIncomingSignal(type, message);
                } catch (JSONException e) {
                    Log.e(TAG, "Signal error", e);
                }
            });

        } catch (Exception e) {
            Log.e("Socket.IO", "Connection error", e);
            updateConnectionStatus("Error: " + e.getMessage());
        }
    }
    //Завершает соединение с сервером и отключает аудиовыход
    private void stopCall() {
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

        Log.d(TAG, "Call stopped, peerConnection and socket closed.");
    }
    //Меняет состояние включения аудиотрека
    private void toggleAudioOutput() {
        Button audioOutputButton = findViewById(R.id.AudioOutputButton);

        isAudioEnabled = !isAudioEnabled;

        // Если уже есть треки — применяем к ним
        if (remoteAudioTrack != null) {
            remoteAudioTrack.setEnabled(isAudioEnabled);
        }
        if (localAudioTrack != null) {
            localAudioTrack.setEnabled(isAudioEnabled);
        }

        int backgroundRes = isAudioEnabled ? R.drawable.rounded_button_green : R.drawable.rounded_button_red;
        audioOutputButton.setBackgroundResource(backgroundRes);

        Log.d("WebRTC_Audio", "Audio output: " + (isAudioEnabled ? "ON" : "OFF"));
    }

    private void handleIncomingSignal(String type, String data) {
        if (type.equals("offer")) {
            SessionDescription offer = new SessionDescription(SessionDescription.Type.OFFER, data);
            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onSetSuccess() {
                    Log.d(TAG, "Remote SDP set successfully");
                    // Создаем ответ (answer) после установки удаленного описания
                    peerConnection.createAnswer(new SdpObserver() {
                        @Override
                        public void onCreateSuccess(SessionDescription sessionDescription) {
                            peerConnection.setLocalDescription(this, sessionDescription);
                            sendSignalToServer("answer", sessionDescription.description);
                        }

                        @Override
                        public void onSetSuccess() {}

                        @Override
                        public void onCreateFailure(String s) {
                            Log.e(TAG, "Failed to create answer: " + s);
                        }

                        @Override
                        public void onSetFailure(String s) {}
                    }, new MediaConstraints());
                }

                @Override
                public void onSetFailure(String s) {
                    Log.e(TAG, "Failed to set remote SDP: " + s);
                }

                @Override
                public void onCreateFailure(String s) {}

                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {}
            }, offer);
        }
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
        try {
            JSONObject message = new JSONObject();
            message.put("type", type);
            message.put("data", data);
            mSocket.emit("signal", message);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending signal to server", e);
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

        // Шаг 4: Сигнализация (Установка SDP)
        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(this, sessionDescription);
                sendSignalToServer("offer", sessionDescription.description);
                Log.d(TAG, "Local SDP set: " + sessionDescription.description);
            }

            @Override
            public void onSetSuccess() {
            }

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "Failed to create SDP: " + s);
            }

            @Override
            public void onSetFailure(String s) {
            }
        }, new MediaConstraints());
    }
}

