package com.manbear.client;

import com.manbear.ManbearClientConfig;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Plays one of your own recorded phrases from a spot in the world using OpenAL directly, so it is properly 3D:
 * you hear which direction it comes from. Must only be called on the client main thread.
 */
public final class VoicePlayer {
    private static final List<int[]> PLAYING = new ArrayList<>();   // {source, buffer}

    private VoicePlayer() { }

    /** one of YOUR OWN phrases */
    public static void play(double x, double y, double z) {
        if (!ManbearClientConfig.MIC_SAMPLING.get()) return;
        short[] clip = MicSampler.randomClip();
        if (clip == null) return;
        playPcm(clip, MicSampler.RATE, x, y, z);
    }

    /** another player's shared phrase (8 kHz mu-law) */
    public static void playShared(double x, double y, double z, byte[] ulaw) {
        if (ulaw == null || ulaw.length == 0) return;
        playPcm(com.manbear.voice.VoiceCodec.decode(ulaw), com.manbear.voice.VoiceCodec.SHARE_RATE, x, y, z);
    }

    private static void playPcm(short[] clip, int rate, double x, double y, double z) {
        try {
            AL10.alGetError();
            int buffer = AL10.alGenBuffers();
            ShortBuffer data = BufferUtils.createShortBuffer(clip.length);
            data.put(clip).flip();
            AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, data, rate);

            int source = AL10.alGenSources();
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(source, AL10.AL_POSITION, (float) x, (float) y, (float) z);
            AL10.alSourcef(source, AL10.AL_GAIN, ManbearClientConfig.VOICE_VOLUME.get().floatValue());
            AL10.alSourcef(source, AL10.AL_PITCH, ManbearClientConfig.VOICE_PITCH.get().floatValue());
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 4.0F);
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, 56.0F);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0F);
            AL10.alSourcePlay(source);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alDeleteSources(source);
                AL10.alDeleteBuffers(buffer);
                return;
            }
            PLAYING.add(new int[]{source, buffer});
        } catch (Throwable ignored) {
            // no sound device / no OpenAL context: simply stay silent
        }
    }

    /** frees finished sounds; call every client tick */
    public static void tick() {
        if (PLAYING.isEmpty()) return;
        try {
            for (Iterator<int[]> it = PLAYING.iterator(); it.hasNext(); ) {
                int[] s = it.next();
                if (AL10.alGetSourcei(s[0], AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                    AL10.alDeleteSources(s[0]);
                    AL10.alDeleteBuffers(s[1]);
                    it.remove();
                }
            }
        } catch (Throwable ignored) {
            PLAYING.clear();
        }
    }
}
