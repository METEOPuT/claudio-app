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
import org.webrtc.audio.JavaAudioDeviceModule;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;

public class WebRTCClient {
    private static final String TAG = "WebRTC_Audio";
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private AudioTrack audioTrack;
    private MediaConstraints mediaConstraints;
    private Context context;
    private MediaStream currentMediaStream;
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

        RequestBody body = RequestBody.create(offer.description, MediaType.get("application/sdp"));
        Request request = new Request.Builder()
                .url(serverUrl + "/whep")  // укажи актуальный путь к ресурсу на WHEP-сервере
                .post(body)
                .addHeader("Content-Type", "application/sdp")
                .addHeader("Accept", "application/sdp")
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
                    String sdpAnswer = response.body().string();
                    SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, sdpAnswer.replace("\\n", "\n"));
                    //Log.d(TAG, "Получен SDP-ответ: " + sdpAnswer);

                    peerConnection.setRemoteDescription(new SdpObserverImpl(), answer);
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка обработки SDP ответа: " + e.getMessage());
                }
            }
        });
    }

    private void initializePeerConnectionFactory() {
        // 1. Инициализация WebRTC
        PeerConnectionFactory.InitializationOptions options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(options);

        // 2. Создание модуля аудиоустройств
        JavaAudioDeviceModule audioDeviceModule = JavaAudioDeviceModule.builder(context).createAudioDeviceModule();

        // 3. Создание фабрики
        PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(EglBase.create().getEglBaseContext()))
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(EglBase.create().getEglBaseContext(), true, true))
                .createPeerConnectionFactory();

        if (peerConnectionFactory == null) {
            Log.e(TAG, "Ошибка создания PeerConnectionFactory!");
        } else {
            Log.d(TAG, "PeerConnectionFactory успешно инициализирована.");
        }
    }

    public void createPeerConnection(String serverIpAddress) {
        // Настройка MediaConstraints
        mediaConstraints = new MediaConstraints();
        mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mediaConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>(); //Пустой список ICE серверов

        // Создаем RTCConfig с этим сервером
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;

        // Создаем PeerConnection
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, peerConnectionObserver);

        if (peerConnection == null) {
            Log.e(TAG, "Не удалось создать PeerConnection! Возвращено null.");
            return;
        }

        // Создание MediaStream и добавление AudioTrack
        currentMediaStream = peerConnectionFactory.createLocalMediaStream("stream1");
        AudioSource audioSource = peerConnectionFactory.createAudioSource(mediaConstraints);
        if (audioSource == null) {
            Log.e(TAG, "Ошибка создания AudioSource!");
            return;
        }

        audioTrack = peerConnectionFactory.createAudioTrack("audio", audioSource);
        if (audioTrack == null) {
            Log.e(TAG, "Ошибка создания AudioTrack!");
            return;
        }

        // Добавляем аудиотрек в поток
        currentMediaStream.addTrack(audioTrack);

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
            Log.d(TAG, "New media stream added: " + mediaStream.toString());
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
        audioTrack = remoteStream.audioTracks.get(0);
        audioTrack.setEnabled(true); // Включаем аудио
        Log.d(TAG, "Добавлен аудиопоток: " + audioTrack.id());
    }

    public void setAudioEnabled(boolean enabled) {
        if (audioTrack != null) {
            audioTrack.setEnabled(enabled);
            Log.d("WebRTC_Audio", "Аудио " + (enabled ? "включено" : "выключено"));
        }
    }

    public void closeConnection() {
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
            Log.d(TAG, "WebRTC соединение закрыто.");
        }
    }

    public MediaStream getMediaStream() {
        if (peerConnection == null) {
            return null;
        }
        return currentMediaStream;
    }
}
