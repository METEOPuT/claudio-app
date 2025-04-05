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
import java.util.ArrayList;
import java.util.List;
import io.socket.client.IO;
import io.socket.client.Socket;
import org.json.JSONException;
import org.json.JSONObject;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.graphics.Color;
import android.content.res.ColorStateList;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "WebRTC_Audio";
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private AudioTrack remoteAudioTrack;
    private Socket mSocket;
    private TextView connectionStatus; // TextView для отображения состояния подключения
    private Handler mainHandler;
    private boolean isAudioEnabled = true; // Флаг состояния аудиовыхода




    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Кнопка для запуска WebRTC
        Button startCallButton = findViewById(R.id.startCallButton);
        startCallButton.setOnClickListener(v -> initializeWebRTC());

        //Кнопка аудиовыхода
        Button audioOutputButton = findViewById(R.id.AudioOutputButton);
        audioOutputButton.setOnClickListener(v -> toggleAudioOutput());

        connectionStatus = findViewById(R.id.connectionStatus);
        mainHandler = new Handler(Looper.getMainLooper());

        // Код для подключения к серверу через Socket.IO
        try {
            // Укажите IP-адрес и порт вашего сервера Node.js
            mSocket = IO.socket("http://192.168.0.118:8080");
            mSocket.connect();

            // Логируем успешное подключение
            mSocket.on(Socket.EVENT_CONNECT, args -> {
                runOnUiThread(() -> {
                    connectionStatus.setText("Connected");
                    Log.d("Socket.IO", "Connected to server!");
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
            ;


            // Добавляем обработчик для кастомного события
            mSocket.on("some_event", args -> {
                Log.d("Socket.IO", "Event received: " + args[0]);
            });

        } catch (Exception e) {
            Log.e("Socket.IO", "Connection error", e);
            updateConnectionStatus("Error: " + e.getMessage());
        }
    }

    private void toggleAudioOutput() {
        Button audioOutputButton = findViewById(R.id.AudioOutputButton);

        if (remoteAudioTrack != null) {
            // Переключаем состояние аудиовыхода
            isAudioEnabled = !isAudioEnabled;
            remoteAudioTrack.setEnabled(isAudioEnabled);

            // Изменяем фон кнопки в зависимости от состояния
            int color = isAudioEnabled ? R.color.green : R.color.red; // зелёный или красный
            audioOutputButton.setBackgroundColor(getResources().getColor(color));

            // Логируем состояние
            Log.d("WebRTC_Audio", "Audio output: " + (isAudioEnabled ? "ON" : "OFF"));
        }
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


    private void updateConnectionStatus(String status) {
        mainHandler.post(() -> connectionStatus.setText("Status: " + status));
    }

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
        // Шаг 1: Инициализация PeerConnectionFactoryd

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
        AudioTrack localAudioTrack = peerConnectionFactory.createAudioTrack("LOCAL_AUDIO", audioSource);

        // Шаг 3: Настройка PeerConnection
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        PeerConnection.IceServer iceServer = PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer();
        iceServers.add(iceServer);

        peerConnection = peerConnectionFactory.createPeerConnection(iceServers, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "Signaling state: " + signalingState);
            }




            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "ICE connection state: " + iceConnectionState);
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            }

            @Override
            public void onIceCandidate(org.webrtc.IceCandidate iceCandidate) {
                peerConnection.addIceCandidate(iceCandidate);
                sendSignalToServer("candidate", iceCandidate.sdp);
            }

            @Override
            public void onIceCandidatesRemoved(org.webrtc.IceCandidate[] iceCandidates) {
            }

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
            }

            @Override
            public void onDataChannel(org.webrtc.DataChannel dataChannel) {
            }

            @Override
            public void onRenegotiationNeeded() {
            }

            @Override
            public void onAddTrack(org.webrtc.RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
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
