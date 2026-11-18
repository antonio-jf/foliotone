package com.linguareader

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.TextUtils
import java.util.Locale
import java.util.MissingResourceException

/**
 * The part of TextToSpeech that FolioTone uses, so an engine can be reached directly when Android's
 * TextToSpeech refuses it.
 *
 * Why: TextToSpeech only accepts engines whose service declares the DEFAULT category
 * (TtsEngines.isEngineInstalled queries with MATCH_DEFAULT_ONLY). On some phones Samsung's engine
 * doesn't, so TextToSpeech silently falls back to Google's even when Samsung's is chosen or is the
 * phone's default. [Speech.create] uses TextToSpeech whenever it accepts the engine; only for such an
 * engine it binds the engine's service itself ([EngineSpeech]) and switches to TextToSpeech if that
 * fails.
 */
interface Speech {
    val voices: Set<Voice>?
    val voice: Voice?
    fun setLanguage(locale: Locale): Int
    fun setVoice(voice: Voice): Int
    fun setSpeechRate(rate: Float): Int
    fun setPitch(pitch: Float): Int
    fun setAudioAttributes(attributes: AudioAttributes): Int
    fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int

    companion object {
        private val intent get() = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        /** Engines whose direct connection failed in this process: use TextToSpeech for them. */
        private val failed = mutableSetOf<String>()

        /** The phone's preferred engine (Settings → Text-to-speech), if set. */
        fun defaultEngine(context: Context): String? =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)?.takeIf { it.isNotBlank() }

        @Suppress("DEPRECATION")
        private fun services(context: Context, engine: String, flags: Int) = runCatching {
            context.packageManager.queryIntentServices(intent.setPackage(engine), flags)
        }.getOrDefault(emptyList())

        /** Whether Android's TextToSpeech will accept this engine (same check as TtsEngines.isEngineInstalled). */
        fun platformAccepts(context: Context, engine: String) =
            services(context, engine, PackageManager.MATCH_DEFAULT_ONLY).size == 1

        /**
         * A speech connection to [engine], or to the phone's default engine when null. [onInit] gets
         * TextToSpeech.SUCCESS or ERROR, on any thread, like TextToSpeech.OnInitListener.
         */
        fun create(context: Context, engine: String?, onInit: (Int) -> Unit): Speech {
            val target = engine ?: defaultEngine(context)
            val service = target?.takeIf { it !in failed && !platformAccepts(context, it) }
                ?.let { services(context, it, 0).firstOrNull()?.serviceInfo }
            if (target != null && service != null) {
                return EngineSpeech(context.applicationContext, ComponentName(service.packageName, service.name), onInit) {
                    synchronized(failed) { failed += target }
                }
            }
            return PlatformSpeech.create(context, engine, onInit)
        }
    }
}

/** Android's TextToSpeech, unchanged. */
class PlatformSpeech private constructor() : Speech {
    lateinit var tts: TextToSpeech

    companion object {
        fun create(context: Context, engine: String?, onInit: (Int) -> Unit): PlatformSpeech = PlatformSpeech().apply {
            val listener = TextToSpeech.OnInitListener { onInit(it) }
            tts = if (engine == null) TextToSpeech(context, listener) else TextToSpeech(context, listener, engine)
        }
    }

    override val voices: Set<Voice>? get() = tts.voices
    override val voice: Voice? get() = tts.voice
    override fun setLanguage(locale: Locale) = tts.setLanguage(locale)
    override fun setVoice(voice: Voice) = tts.setVoice(voice)
    override fun setSpeechRate(rate: Float) = tts.setSpeechRate(rate)
    override fun setPitch(pitch: Float) = tts.setPitch(pitch)
    override fun setAudioAttributes(attributes: AudioAttributes) = tts.setAudioAttributes(attributes)
    override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String) =
        tts.speak(text, queueMode, params, utteranceId)
    override fun stop() = tts.stop()
    override fun shutdown() = tts.shutdown()
    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) =
        tts.setOnUtteranceProgressListener(listener)
}

/**
 * Talks to a speech engine's service over Binder, writing the same calls TextToSpeech makes
 * (android.speech.tts.ITextToSpeechService / ITextToSpeechCallback, unchanged since Android 5).
 * Uses only public Parcel and Binder APIs. If binding or setup fails it hands everything to
 * [PlatformSpeech], so the app keeps working with whatever TextToSpeech picks.
 */
class EngineSpeech(
    private val context: Context,
    private val component: ComponentName,
    private val onInit: (Int) -> Unit,
    private val onDirectFailed: () -> Unit
) : Speech {
    private companion object {
        const val SERVICE = "android.speech.tts.ITextToSpeechService"
        const val CALLBACK = "android.speech.tts.ITextToSpeechCallback"
        // Transaction codes: FIRST_CALL_TRANSACTION (1) + position in the AIDL interface.
        const val SPEAK = 1
        const val STOP = 6
        const val GET_CLIENT_DEFAULT_LANGUAGE = 8
        const val IS_LANGUAGE_AVAILABLE = 9
        const val SET_CALLBACK = 12
        const val GET_VOICES = 13
        const val LOAD_VOICE = 14
        const val GET_DEFAULT_VOICE_NAME = 15
        const val CONNECT_TIMEOUT_MS = 5_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val params = Bundle()
    @Volatile private var service: IBinder? = null
    @Volatile private var listener: UtteranceProgressListener? = null
    @Volatile private var fallback: PlatformSpeech? = null
    @Volatile private var initialised = false
    @Volatile private var closed = false
    private var bound = false

    /** Our identity for the engine (TextToSpeech uses its callback object the same way). */
    private val callback = object : Binder() {
        init { attachInterface(null, CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..7) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK)
            val id = data.readString()
            val target = listener ?: return true
            when (code) {
                1 -> target.onStart(id)
                2 -> target.onDone(id)
                3 -> target.onStop(id, data.readInt() != 0)
                4 -> target.onError(id, data.readInt())
                5 -> target.onBeginSynthesis(id, data.readInt(), data.readInt(), data.readInt())
                6 -> target.onAudioAvailable(id, data.createByteArray())
                7 -> target.onRangeStart(id, data.readInt(), data.readInt(), data.readInt())
            }
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) { failDirect(); return }
            service = binder
            Thread {
                val ready = runCatching { setUp() }.getOrDefault(false)
                if (!initialised) {
                    if (ready) { initialised = true; onInit(TextToSpeech.SUCCESS) } else failDirect()
                }
            }.start()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
        override fun onBindingDied(name: ComponentName?) { service = null; if (!initialised) failDirect() }
        override fun onNullBinding(name: ComponentName?) { failDirect() }
    }

    init {
        bound = runCatching {
            context.bindService(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setComponent(component),
                connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) failDirect()
        else main.postDelayed({ if (!initialised && fallback == null) failDirect() }, CONNECT_TIMEOUT_MS)
    }

    /** Register for callbacks and pick the engine's default language and voice, as TextToSpeech does. */
    private fun setUp(): Boolean {
        call(SET_CALLBACK, { writeStrongBinder(callback); writeStrongBinder(callback) }) { true } ?: return false
        val language = call(GET_CLIENT_DEFAULT_LANGUAGE, {}) { createStringArray() }
        if (language != null && language.size >= 3) {
            synchronized(params) {
                params.putString(KEY_LANGUAGE, language[0])
                params.putString(KEY_COUNTRY, language[1])
                params.putString(KEY_VARIANT, language[2])
            }
            defaultVoiceName(language[0], language[1], language[2])?.let { name ->
                synchronized(params) { params.putString(KEY_VOICE_NAME, name) }
            }
        }
        return true
    }

    private fun failDirect() {
        if (fallback != null || closed) return
        synchronized(this) {
            if (fallback != null) return
            onDirectFailed()
            unbind()
            service = null
            val replacement = PlatformSpeech.create(context, component.packageName) { status ->
                if (!initialised) { initialised = true; onInit(status) }
            }
            // Carry over what the app already set.
            listener?.let { replacement.setOnUtteranceProgressListener(it) }
            fallback = replacement
        }
    }

    private fun unbind() {
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
    }

    /** One synchronous call to the engine, written like the AIDL proxy would. Null on any failure. */
    private fun <T> call(code: Int, write: Parcel.() -> Unit, read: Parcel.() -> T): T? {
        val binder = service ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(SERVICE)
            data.write()
            if (!binder.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.read()
        } catch (error: Exception) {
            null
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    private fun currentParams(extra: Bundle?): Bundle = synchronized(params) {
        Bundle(params).apply { if (extra != null) putAll(extra) }
    }

    private fun defaultVoiceName(language: String, country: String, variant: String) =
        call(GET_DEFAULT_VOICE_NAME, { writeString(language); writeString(country); writeString(variant) }) { readString() }
            ?.takeIf { it.isNotEmpty() }

    private fun loadVoice(name: String) =
        call(LOAD_VOICE, { writeStrongBinder(callback); writeString(name) }) { readInt() } ?: TextToSpeech.ERROR

    private fun rememberVoice(voice: Voice) = synchronized(params) {
        params.putString(KEY_VOICE_NAME, voice.name)
        params.putString(KEY_LANGUAGE, runCatching { voice.locale.isO3Language }.getOrDefault(""))
        params.putString(KEY_COUNTRY, runCatching { voice.locale.isO3Country }.getOrDefault(""))
        params.putString(KEY_VARIANT, voice.locale.variant)
    }

    override val voices: Set<Voice>?
        get() = fallback?.voices ?: call(GET_VOICES, {}) { createTypedArrayList(Voice.CREATOR) }?.toSet()

    override val voice: Voice?
        get() {
            fallback?.let { return it.voice }
            val name = synchronized(params) { params.getString(KEY_VOICE_NAME) } ?: return null
            return voices?.firstOrNull { it.name == name }
        }

    override fun setLanguage(locale: Locale): Int {
        fallback?.let { return it.setLanguage(locale) }
        val language = try { locale.isO3Language } catch (e: MissingResourceException) { return TextToSpeech.LANG_NOT_SUPPORTED }
        val country = try { locale.isO3Country } catch (e: MissingResourceException) { return TextToSpeech.LANG_NOT_SUPPORTED }
        val variant = locale.variant
        val available = call(IS_LANGUAGE_AVAILABLE, { writeString(language); writeString(country); writeString(variant) }) { readInt() }
            ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (available < TextToSpeech.LANG_AVAILABLE) return available
        val name = defaultVoiceName(language, country, variant) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (loadVoice(name) == TextToSpeech.ERROR) return TextToSpeech.LANG_NOT_SUPPORTED
        voices?.firstOrNull { it.name == name }?.let { rememberVoice(it) }
            ?: synchronized(params) {
                params.putString(KEY_VOICE_NAME, name)
                params.putString(KEY_LANGUAGE, language)
                params.putString(KEY_COUNTRY, country)
                params.putString(KEY_VARIANT, variant)
            }
        return available
    }

    override fun setVoice(voice: Voice): Int {
        fallback?.let { return it.setVoice(voice) }
        val result = loadVoice(voice.name)
        if (result == TextToSpeech.SUCCESS) rememberVoice(voice)
        return result
    }

    override fun setSpeechRate(rate: Float): Int {
        fallback?.let { return it.setSpeechRate(rate) }
        val value = (rate * 100).toInt()
        if (rate <= 0 || value <= 0) return TextToSpeech.ERROR
        synchronized(params) { params.putInt(KEY_RATE, value) }
        return TextToSpeech.SUCCESS
    }

    override fun setPitch(pitch: Float): Int {
        fallback?.let { return it.setPitch(pitch) }
        val value = (pitch * 100).toInt()
        if (pitch <= 0 || value <= 0) return TextToSpeech.ERROR
        synchronized(params) { params.putInt(KEY_PITCH, value) }
        return TextToSpeech.SUCCESS
    }

    override fun setAudioAttributes(attributes: AudioAttributes): Int {
        fallback?.let { return it.setAudioAttributes(attributes) }
        synchronized(params) { params.putParcelable(KEY_AUDIO_ATTRIBUTES, attributes) }
        return TextToSpeech.SUCCESS
    }

    override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        fallback?.let { return it.speak(text, queueMode, params, utteranceId) }
        val bundle = currentParams(params)
        return call(SPEAK, {
            writeStrongBinder(callback)
            writeInt(1); TextUtils.writeToParcel(text, this, 0)
            writeInt(queueMode)
            writeInt(1); bundle.writeToParcel(this, 0)
            writeString(utteranceId)
        }) { readInt() } ?: TextToSpeech.ERROR
    }

    override fun stop(): Int {
        fallback?.let { return it.stop() }
        return call(STOP, { writeStrongBinder(callback) }) { readInt() } ?: TextToSpeech.ERROR
    }

    override fun shutdown() {
        closed = true
        main.removeCallbacksAndMessages(null)
        fallback?.let { it.shutdown(); return }
        call(SET_CALLBACK, { writeStrongBinder(callback); writeStrongBinder(null) }) { true }
        call(STOP, { writeStrongBinder(callback) }) { readInt() }
        unbind()
        service = null
    }

    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int {
        this.listener = listener
        fallback?.setOnUtteranceProgressListener(listener)
        return TextToSpeech.SUCCESS
    }
}

// Parameter names TextToSpeech sends to engines (TextToSpeech.Engine keeps most of them hidden).
private const val KEY_RATE = "rate"
private const val KEY_PITCH = "pitch"
private const val KEY_VOICE_NAME = "voiceName"
private const val KEY_LANGUAGE = "language"
private const val KEY_COUNTRY = "country"
private const val KEY_VARIANT = "variant"
private const val KEY_AUDIO_ATTRIBUTES = "audioAttributes"
