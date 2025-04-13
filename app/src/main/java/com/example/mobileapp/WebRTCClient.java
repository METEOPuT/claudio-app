package com.example.mobileapp;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;

public class WebRTCClient {
    private static final String TAG = "WebRTC_Audio";
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private AudioTrack audioTrack;
    private MediaConstraints mediaConstraints;
    private Context context;

    private PeerConnection.Observer peerConnectionObserver = new PeerConnectionObserver();

    public WebRTCClient(Context context) {
        this.context = context;
        initializePeerConnectionFactory();
    }

    public void startConnectionViaHttp(String serverUrl) {
        createPeerConnection(serverUrl);

        peerConnection.createOffer(new SdpObserverImpl() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SdpObserverImpl(), sessionDescription);

                // Отправка offer на сервер через HTTP
                sendSdpToServer(sessionDescription, serverUrl);
            }
        }, mediaConstraints);
    }

    private void sendSdpToServer(SessionDescription offer, String serverUrl) {
        OkHttpClient client = new OkHttpClient();

        String json = "{\"sdp\": \"" + offer.description.replace("\n", "\\n") + "\", \"type\": \"offer\"}";

        RequestBody body = RequestBody.create(json, MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(serverUrl + "/offer")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Ошибка при отправке SDP offer: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e(TAG, "Сервер вернул ошибку: " + response.code());
                    return;
                }

                try {
                    String responseJson = response.body().string();
                    JSONObject jsonObject = new JSONObject(responseJson);
                    String sdpAnswer = jsonObject.getString("sdp");
                    SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer.replace("\\n", "\n"));

                    peerConnection.setRemoteDescription(new SdpObserverImpl(), answer);
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка обработки SDP ответа: " + e.getMessage());
                }
            }
        });
    }

    private void initializePeerConnectionFactory() {
        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(); // Используем context, переданный в конструктор
        PeerConnectionFactory.initialize(options);

        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory();
    }

    public void createPeerConnection(String serverIpAddress) {
        // Настройка MediaConstraints
        mediaConstraints = new MediaConstraints();
        mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        // Создаем список ICE серверов
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();

        // Добавляем сервер STUN с IP-адресом вашего сервера
        iceServers.add(new PeerConnection.IceServer("stun:" + serverIpAddress + ":3478"));

        // Создаем RTCConfig с этим сервером
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);

        // Создаем PeerConnection
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, mediaConstraints, peerConnectionObserver);

        // Создание MediaStream и добавление AudioTrack
        MediaStream mediaStream = peerConnectionFactory.createLocalMediaStream("stream1");
        AudioSource audioSource = peerConnectionFactory.createAudioSource(mediaConstraints);
        audioTrack = peerConnectionFactory.createAudioTrack("audio", audioSource);
        mediaStream.addTrack(audioTrack);

        Log.d(TAG, "WebRTC соединение создано.");
    }
    private class PeerConnectionObserver implements PeerConnection.Observer {

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            Log.d(TAG, "ICE candidates removed");
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }

        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.d(TAG, "New ICE candidate: " + iceCandidate.toString());
            // Здесь можно отправить ICE кандидат на сервер
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            setRemoteStream(mediaStream);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }

        @Override
        public void onRenegotiationNeeded() {
        }
    }
    private static class SdpObserverImpl implements SdpObserver {
        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            // Реализовать логику при успешном создании SDP
        }
        @Override
        public void onSetSuccess() {
            // Реализовать логику при успешной установке SDP
        }
        @Override
        public void onCreateFailure(String s) {
            Log.e(TAG, "SDP Create failed: " + s);
        }
        @Override
        public void onSetFailure(String s) {
            Log.e(TAG, "SDP Set failed: " + s);
        }
    }
    public void setRemoteStream(MediaStream remoteStream) {
        if (remoteStream != null && remoteStream.audioTracks.size() > 0) {
            audioTrack = remoteStream.audioTracks.get(0);
            audioTrack.setEnabled(true); // Включаем аудио
            Log.d(TAG, "Добавлен аудиопоток: " + audioTrack.id());
        }
    }
    public void closeConnection() {
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
            Log.d(TAG, "WebRTC соединение закрыто.");
        }
    }
}

